package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads configured Task scope; never accepts a plan or authorizes execution. */
@Service
@ConditionalOnProperty(name = "agentos.mission-plan-review.enabled", havingValue = "true")
public class MissionPlanReviewService {
  public record Task(
      UUID id,
      String title,
      String status,
      UUID ownerId,
      String ownerName,
      String ownerStatus,
      String editRevision,
      String ownerRevision,
      String dependencyRevision,
      String description,
      String acceptanceCriteria,
      String priority) {}

  public record Edge(UUID taskId, UUID dependencyId) {}

  public record Review(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String title,
      String status,
      long taskCount,
      long edgeCount,
      List<Task> tasks,
      List<Edge> edges,
      List<UUID> order,
      List<String> blockers,
      boolean canReviewConfiguration,
      boolean executionAuthorized,
      String fingerprint) {}

  private final JdbcTemplate jdbc;
  private final MissionCreateService authorization;
  private final ObjectMapper json;
  private final org.springframework.core.env.Environment environment;

  public MissionPlanReviewService(
      JdbcTemplate jdbc,
      MissionCreateService authorization,
      ObjectMapper json,
      org.springframework.core.env.Environment environment) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.json = json;
    this.environment = environment;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Review current(UUID org, UUID team, UUID mission, HumanIdentity human) {
    authorization.authorize(org, human, true, false);
    var parents =
        jdbc.query(
            "select m.title,m.status,t.status,o.status from missions m join teams t on"
                + " t.organization_id=m.organization_id and t.id=m.team_id join organizations o on"
                + " o.id=m.organization_id where m.organization_id=? and m.team_id=? and m.id=?",
            (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
            org,
            team,
            mission);
    if (parents.isEmpty()) throw new EntityNotFoundException("Mission not found");
    var parent = parents.getFirst();
    var blockers = new ArrayList<String>();
    if (!("CREATED".equals(parent.get(1))
        || ("READY".equals(parent.get(1))
            && environment.getProperty(
                "agentos.mission-plan-acceptance.enabled", Boolean.class, false))))
      blockers.add("MISSION_STATE_UNAVAILABLE");
    if (!"ACTIVE".equals(parent.get(2)) || !"ACTIVE".equals(parent.get(3)))
      blockers.add("PARENT_UNAVAILABLE");
    long count =
        jdbc.queryForObject(
            "select count(*) from tasks where organization_id=? and team_id=? and mission_id=?",
            Long.class,
            org,
            team,
            mission);
    long edges =
        jdbc.queryForObject(
            "select count(*) from task_dependencies where organization_id=? and team_id=? and"
                + " mission_id=?",
            Long.class,
            org,
            team,
            mission);
    List<Task> tasks = List.of();
    List<Edge> graph = List.of();
    List<UUID> order = List.of();
    if (count == 0) blockers.add("NO_TASKS");
    if (count > 200 || edges > 1000) blockers.add("SCOPE_LIMIT_EXCEEDED");
    else {
      tasks =
          jdbc.query(
              """
select t.id,t.title,t.status,t.owner_agent_id,a.name,a.status,t.task_edit_revision,t.task_owner_revision,t.dependency_revision,
  substring(t.description,1,8001),substring(t.acceptance_criteria,1,4001),t.priority
from tasks t left join agents a on a.id=t.owner_agent_id and a.organization_id=t.organization_id and a.team_id=t.team_id
where t.organization_id=? and t.team_id=? and t.mission_id=? order by t.id
""",
              (rs, n) ->
                  new Task(
                      rs.getObject(1, UUID.class),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getObject(4, UUID.class),
                      rs.getString(5),
                      rs.getString(6),
                      rs.getString(7),
                      rs.getString(8),
                      rs.getString(9),
                      rs.getString(10),
                      rs.getString(11),
                      rs.getString(12)),
              org,
              team,
              mission);
      graph =
          jdbc.query(
              "select task_id,dependency_task_id from task_dependencies where organization_id=? and"
                  + " team_id=? and mission_id=? order by task_id,dependency_task_id",
              (rs, n) -> new Edge(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
              org,
              team,
              mission);
      if (tasks.stream().anyMatch(t -> !"DRAFT".equals(t.status())))
        blockers.add("TASK_STATE_UNAVAILABLE");
      if (tasks.stream().anyMatch(t -> t.ownerId() == null || !"ACTIVE".equals(t.ownerStatus())))
        blockers.add("OWNER_UNAVAILABLE");
      if (tasks.stream()
          .anyMatch(
              t ->
                  t.description() == null
                      || t.description().isBlank()
                      || t.acceptanceCriteria() == null
                      || t.acceptanceCriteria().isBlank()
                      || !List.of("LOW", "NORMAL", "HIGH", "URGENT")
                          .contains(t.priority() == null ? "" : t.priority())))
        blockers.add("TASK_INPUT_UNAVAILABLE");
      long size = 0;
      for (var t : tasks) size += bytes(t.description()) + bytes(t.acceptanceCriteria());
      if (size > 1048576
          || tasks.stream()
              .anyMatch(
                  t ->
                      t.description() != null && t.description().length() > 8000
                          || t.acceptanceCriteria() != null
                              && t.acceptanceCriteria().length() > 4000)) {
        blockers.add("INPUT_LIMIT_EXCEEDED");
        tasks = List.of();
        graph = List.of();
      } else {
        Map<UUID, Integer> pending = new HashMap<>();
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        for (var t : tasks) {
          pending.put(t.id(), 0);
          outgoing.put(t.id(), new ArrayList<>());
        }
        boolean invalid = false;
        for (var e : graph) {
          if (!pending.containsKey(e.taskId()) || !pending.containsKey(e.dependencyId())) {
            invalid = true;
            break;
          }
          pending.merge(e.taskId(), 1, Integer::sum);
          outgoing.get(e.dependencyId()).add(e.taskId());
        }
        var ready = new PriorityQueue<UUID>(Comparator.comparing(UUID::toString));
        pending.forEach(
            (id, n) -> {
              if (n == 0) ready.add(id);
            });
        var sorted = new ArrayList<UUID>();
        while (!ready.isEmpty()) {
          var id = ready.remove();
          sorted.add(id);
          for (var child : outgoing.get(id))
            if (pending.merge(child, -1, Integer::sum) == 0) ready.add(child);
        }
        if (invalid || sorted.size() != tasks.size()) blockers.add("INVALID_DEPENDENCY_GRAPH");
        else order = List.copyOf(sorted);
      }
    }
    String digest;
    try {
      digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          json.writeValueAsBytes(
                              List.of(
                                  org, team, mission, parent, count, edges, tasks, graph, order,
                                  blockers))));
    } catch (JsonProcessingException | java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Plan assessment unavailable");
    }
    return new Review(
        org,
        team,
        mission,
        parent.get(0),
        parent.get(1),
        count,
        edges,
        tasks,
        graph,
        order,
        List.copyOf(blockers),
        blockers.isEmpty(),
        false,
        digest);
  }

  private static int bytes(String text) {
    return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
  }
}

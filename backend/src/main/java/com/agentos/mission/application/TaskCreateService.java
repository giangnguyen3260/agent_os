package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.api.dto.TaskCreateRequest;
import com.agentos.mission.application.MissionCreateService.Conflict;
import com.agentos.taskdependencies.TaskDependenciesGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCreateService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final MissionCreateService authorization;
  private final TaskDetailService details;

  public TaskCreateService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      MissionCreateService authorization,
      TaskDetailService details) {
    this.jdbc = jdbc;
    this.json = json;
    this.authorization = authorization;
    this.details = details;
  }

  public record Context(
      UUID organizationId,
      UUID teamId,
      String teamName,
      UUID missionId,
      String missionTitle,
      String missionStatus,
      long intakeVersion,
      boolean intakePaused,
      boolean canCreate) {}

  public record Created(TaskDetailService.Detail response, boolean replay) {}

  public record Owner(UUID id, String name, String level, String status) {}

  public record Dependency(UUID id, String title, String status) {}

  public record Candidates<T>(List<T> items, int page, int size, long total) {}

  private Context parent(UUID org, UUID team, UUID mission, boolean lock) {
    var teams =
        jdbc.query(
            "select name,status from teams where organization_id=? and id=?"
                + (lock ? " for update" : ""),
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            org,
            team);
    if (teams.isEmpty()) throw new EntityNotFoundException("Team not found");
    var rows =
        jdbc.query(
            "select title,status,task_intake_version,task_intake_paused from missions where"
                + " organization_id=? and team_id=? and id=?"
                + (lock ? " for update" : ""),
            (rs, n) ->
                new Context(
                    org,
                    team,
                    teams.getFirst()[0],
                    mission,
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getBoolean(4),
                    List.of("DRAFT", "ACTIVE").contains(teams.getFirst()[1])
                        && List.of("CREATED", "READY", "RUNNING", "WAITING_APPROVAL")
                            .contains(rs.getString(2))
                        && !rs.getBoolean(4)
                        && rs.getLong(3) < Long.MAX_VALUE),
            org,
            team,
            mission);
    if (rows.isEmpty()) throw new EntityNotFoundException("Mission not found");
    return rows.getFirst();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID team, UUID mission, HumanIdentity human) {
    authorization.authorize(org, human, true, false);
    return parent(org, team, mission, false);
  }

  private String required(String text, int max) {
    if (text == null || text.strip().isEmpty() || text.strip().length() > max)
      throw new IllegalArgumentException("Invalid required text");
    return text.strip();
  }

  private TaskCreateRequest canonical(TaskCreateRequest r) {
    if (r == null
        || r.expectedIntakeVersion() == null
        || r.expectedIntakeVersion() < 0
        || r.dependencyIds() == null
        || r.dependencyIds().size() > 20
        || r.dependencyIds().stream().anyMatch(Objects::isNull)
        || new HashSet<>(r.dependencyIds()).size() != r.dependencyIds().size()
        || !List.of("LOW", "NORMAL", "HIGH", "URGENT")
            .contains(r.priority() == null ? "" : r.priority()))
      throw new IllegalArgumentException("Invalid task configuration");
    return new TaskCreateRequest(
        required(r.title(), 255),
        required(r.description(), 8000),
        required(r.acceptanceCriteria(), 4000),
        r.priority(),
        r.ownerAgentId(),
        r.dependencyIds().stream().sorted().toList(),
        r.expectedIntakeVersion());
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(UUID org, UUID team, UUID mission, TaskCreateRequest r) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(List.of(org, team, mission, r)).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Created create(
      UUID org, UUID team, UUID mission, HumanIdentity human, UUID key, TaskCreateRequest input) {
    authorization.authorize(org, human, true, true);
    if (key == null) throw new IllegalArgumentException("Idempotency-Key required");
    var r = canonical(input);
    String digest = hash(org, team, mission, r);
    var receipts =
        jdbc.query(
            "select body_sha256,original_response from task_create_receipts where organization_id=?"
                + " and account_id=? and idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            org,
            human.id(),
            key);
    authorization.authorize(org, human, true, false);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!digest.equals(receipt[0])) throw new Conflict("IDEMPOTENCY_CONFLICT");
      try {
        return new Created(json.readValue(receipt[1], TaskDetailService.Detail.class), true);
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
    var context = parent(org, team, mission, true);
    if (!context.canCreate()) throw new Conflict("TASK_SETUP_CONFLICT");
    if (context.intakeVersion() != r.expectedIntakeVersion())
      throw new Conflict("TASK_INTAKE_CONFLICT");
    if (r.ownerAgentId() != null) {
      var owners =
          jdbc.query(
              "select team_id,status from agents where organization_id=? and id=? for update",
              (rs, n) ->
                  team.equals(rs.getObject(1, UUID.class))
                      && List.of("DRAFT", "ACTIVE").contains(rs.getString(2)),
              org,
              r.ownerAgentId());
      if (owners.isEmpty() || !owners.getFirst()) throw new Conflict("TASK_SETUP_CONFLICT");
    }
    for (UUID dependency :
        r.dependencyIds().stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
      if (jdbc.query(
              "select id from tasks where organization_id=? and team_id=? and mission_id=? and id=?"
                  + " for update",
              (rs, n) -> rs.getObject(1, UUID.class),
              org,
              team,
              mission,
              dependency)
          .isEmpty()) throw new Conflict("TASK_SETUP_CONFLICT");
    }
    authorization.authorize(org, human, true, false);
    TaskDependenciesGraph.validate(jdbc, org, team, mission);
    // Lock sorting does not alter the historical canonical body/digest order.
    authorization.authorize(org, human, true, false);
    // A server-generated new task can only depend on existing tasks, preventing cycles.
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority,owner_agent_id)"
            + " values (?,?,?,?,?,'DRAFT',?,?,?,?)",
        id,
        org,
        team,
        mission,
        r.title(),
        r.description(),
        r.acceptanceCriteria(),
        r.priority(),
        r.ownerAgentId());
    for (UUID dependency : r.dependencyIds())
      jdbc.update(
          "insert into"
              + " task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)"
              + " values (?,?,?,?,?)",
          org,
          team,
          mission,
          id,
          dependency);
    jdbc.update(
        "update missions set task_intake_version=task_intake_version+1 where organization_id=? and"
            + " team_id=? and id=?",
        org,
        team,
        mission);
    var response = details.get(org, team, mission, id, human);
    jdbc.update(
        "insert into"
            + " task_create_receipts(organization_id,account_id,idempotency_key,body_sha256,original_response)"
            + " values (?,?,?,?,?)",
        org,
        human.id(),
        key,
        digest,
        encode(response));
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,?,?,'HUMAN',?,'TASK_CREATED',?,current_timestamp)",
        UUID.randomUUID(),
        org,
        mission,
        id,
        human.id(),
        "{\"status\":\"DRAFT\"}");
    return new Created(response, false);
  }

  private String pattern(int page, String query) {
    if (page < 0 || page > 100000 || query == null || query.length() > 100)
      throw new IllegalArgumentException("Invalid page or query");
    return "%"
        + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
        + "%";
  }

  private void candidatesAllowed(UUID org, UUID team, UUID mission, HumanIdentity human) {
    authorization.authorize(org, human, true, false);
    if (!parent(org, team, mission, false).canCreate()) throw new Conflict("TASK_SETUP_CONFLICT");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates<Owner> owners(
      UUID org, UUID team, UUID mission, HumanIdentity human, int page, String query) {
    candidatesAllowed(org, team, mission, human);
    String search = pattern(page, query);
    String where =
        " where organization_id=? and team_id=? and status in ('DRAFT','ACTIVE') and lower(name)"
            + " like ? escape '!'";
    long total =
        jdbc.queryForObject("select count(*) from agents" + where, Long.class, org, team, search);
    return new Candidates<>(
        jdbc.query(
            "select id,name,level,status from agents"
                + where
                + " order by name,id limit 20 offset ?",
            (rs, n) ->
                new Owner(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            org,
            team,
            search,
            page * 20),
        page,
        20,
        total);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates<Dependency> dependencies(
      UUID org, UUID team, UUID mission, HumanIdentity human, int page, String query) {
    candidatesAllowed(org, team, mission, human);
    String search = pattern(page, query);
    String where =
        " where organization_id=? and team_id=? and mission_id=? and lower(title) like ? escape"
            + " '!'";
    long total =
        jdbc.queryForObject(
            "select count(*) from tasks" + where, Long.class, org, team, mission, search);
    return new Candidates<>(
        jdbc.query(
            "select id,title,status from tasks" + where + " order by title,id limit 20 offset ?",
            (rs, n) ->
                new Dependency(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            org,
            team,
            mission,
            search,
            page * 20),
        page,
        20,
        total);
  }
}

package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.application.MissionPlanReviewService.Review;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit configuration acceptance only; does not create Runs or authorize dispatch. */
@Service
@ConditionalOnProperty(name = "agentos.mission-plan-acceptance.enabled", havingValue = "true")
public class MissionPlanAcceptanceService {
  public static final class Conflict extends RuntimeException {}

  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String planRevision,
      UUID idempotencyKey,
      String sourceFingerprint,
      boolean executionAuthorized) {}

  public record Current(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String planRevision,
      boolean matchesCurrent,
      Review snapshot,
      boolean executionAuthorized) {}

  private record Version(
      UUID team,
      UUID mission,
      long revision,
      UUID key,
      String source,
      String ready,
      String content,
      String sha,
      int schema) {}

  private final JdbcTemplate jdbc;
  private final MissionCreateService authorization;
  private final MissionPlanReviewService reviews;
  private final ObjectMapper json;

  public MissionPlanAcceptanceService(
      JdbcTemplate jdbc,
      MissionCreateService authorization,
      MissionPlanReviewService reviews,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.reviews = reviews;
    this.json = json;
  }

  private List<Version> versions(String where, Object... args) {
    return jdbc.query(
        "select"
            + " team_id,mission_id,plan_revision,idempotency_key,source_fingerprint,ready_fingerprint,snapshot_json,snapshot_sha256,schema_version"
            + " from mission_plan_acceptance_versions "
            + where,
        (rs, n) ->
            new Version(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getInt(9)),
        args);
  }

  private Review snapshot(UUID org, Version v) {
    try {
      if (v.schema() != 1
          || v.content().getBytes(StandardCharsets.UTF_8).length > 8388608
          || !sha(v.content()).equals(v.sha())) throw new Conflict();
      var r = json.readValue(v.content(), Review.class);
      if (!org.equals(r.organizationId())
          || !v.team().equals(r.teamId())
          || !v.mission().equals(r.missionId())
          || !v.source().equals(r.fingerprint())
          || !r.canReviewConfiguration()
          || r.executionAuthorized()) throw new Conflict();
      var tasks =
          jdbc.query(
              "select"
                  + " task_id,owner_agent_id,task_edit_revision,task_owner_revision,dependency_revision"
                  + " from accepted_mission_plan_tasks where organization_id=? and team_id=? and"
                  + " mission_id=? and plan_revision=? order by task_id",
              (rs, n) ->
                  List.of(
                      rs.getObject(1, UUID.class).toString(),
                      rs.getObject(2, UUID.class).toString(),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getString(5)),
              org,
              v.team(),
              v.mission(),
              v.revision());
      var expected =
          r.tasks().stream()
              .map(
                  t ->
                      List.of(
                          t.id().toString(),
                          t.ownerId().toString(),
                          t.editRevision(),
                          t.ownerRevision(),
                          t.dependencyRevision()))
              .toList();
      var edges =
          jdbc.query(
              "select task_id,dependency_task_id from accepted_mission_plan_dependencies where"
                  + " organization_id=? and team_id=? and mission_id=? and plan_revision=? order by"
                  + " task_id,dependency_task_id",
              (rs, n) ->
                  new MissionPlanReviewService.Edge(
                      rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
              org,
              v.team(),
              v.mission(),
              v.revision());
      if (!tasks.equals(expected) || !edges.equals(r.edges())) throw new Conflict();
      return r;
    } catch (Conflict e) {
      throw e;
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  private Version selected(UUID org, UUID team, UUID mission) {
    long max =
        jdbc.queryForObject(
            "select coalesce(max(plan_revision),0) from accepted_mission_plans where"
                + " organization_id=? and team_id=? and mission_id=?",
            Long.class,
            org,
            team,
            mission);
    var selected =
        jdbc.queryForList(
            "select plan_revision from mission_plan_selections where organization_id=? and"
                + " team_id=? and mission_id=?",
            Long.class,
            org,
            team,
            mission);
    if (max == 0 && selected.isEmpty()) return null;
    if (selected.size() != 1 || selected.getFirst() != max) throw new Conflict();
    var rows =
        versions(
            "where organization_id=? and team_id=? and mission_id=? and plan_revision=?",
            org,
            team,
            mission,
            max);
    if (rows.size() != 1) throw new Conflict();
    long plans =
        jdbc.queryForObject(
            "select count(*) from accepted_mission_plans where organization_id=? and team_id=? and"
                + " mission_id=?",
            Long.class,
            org,
            team,
            mission);
    long receipts =
        jdbc.queryForObject(
            "select count(*) from mission_plan_acceptance_versions where organization_id=? and"
                + " team_id=? and mission_id=?",
            Long.class,
            org,
            team,
            mission);
    if (plans != max || receipts != max) throw new Conflict();
    snapshot(org, rows.getFirst());
    return rows.getFirst();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Current current(UUID org, UUID team, UUID mission, HumanIdentity human) {
    var now = reviews.current(org, team, mission, human);
    var v = selected(org, team, mission);
    return new Current(
        org,
        team,
        mission,
        v == null ? null : Long.toString(v.revision()),
        v != null && now.canReviewConfiguration() && now.fingerprint().equals(v.ready()),
        v == null ? null : snapshot(org, v),
        false);
  }

  @Transactional
  public Receipt accept(
      UUID org, UUID team, UUID mission, HumanIdentity human, UUID key, String fingerprint) {
    authorization.authorize(org, human, true, true);
    if (key == null || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid acceptance command");
    var receipts =
        versions(
            "where organization_id=? and actor_id=? and idempotency_key=?", org, human.id(), key);
    if (!receipts.isEmpty()) {
      var old = receipts.getFirst();
      if (!team.equals(old.team())
          || !mission.equals(old.mission())
          || !fingerprint.equals(old.source())) throw new Conflict();
      snapshot(org, old);
      return receipt(org, old);
    }
    var now = reviews.current(org, team, mission, human);
    var previous = selected(org, team, mission);
    if (!now.canReviewConfiguration()
        || !fingerprint.equals(now.fingerprint())
        || previous != null && now.fingerprint().equals(previous.ready())) throw new Conflict();
    for (String table : List.of("execution_runs", "mission_execution_controls")) {
      if (jdbc.queryForObject(
              "select count(*) from "
                  + table
                  + " where organization_id=? and team_id=? and mission_id=?",
              Long.class,
              org,
              team,
              mission)
          != 0) throw new Conflict();
    }
    if (previous != null && previous.revision() == Long.MAX_VALUE) throw new Conflict();
    long revision = previous == null ? 1 : previous.revision() + 1;
    String content;
    try {
      content = json.writeValueAsString(now);
    } catch (Exception e) {
      throw new Conflict();
    }
    if (content.getBytes(StandardCharsets.UTF_8).length > 8388608) throw new Conflict();
    jdbc.update(
        "insert into"
            + " accepted_mission_plans(organization_id,team_id,mission_id,plan_revision,accepted_by)"
            + " values(?,?,?,?,?)",
        org,
        team,
        mission,
        revision,
        human.id());
    for (var t : now.tasks())
      jdbc.update(
          "insert into"
              + " accepted_mission_plan_tasks(organization_id,team_id,mission_id,plan_revision,task_id,owner_agent_id,task_edit_revision,task_owner_revision,dependency_revision)"
              + " values(?,?,?,?,?,?,?,?,?)",
          org,
          team,
          mission,
          revision,
          t.id(),
          t.ownerId(),
          Integer.parseInt(t.editRevision()),
          Integer.parseInt(t.ownerRevision()),
          Integer.parseInt(t.dependencyRevision()));
    for (var e : now.edges())
      jdbc.update(
          "insert into"
              + " accepted_mission_plan_dependencies(organization_id,team_id,mission_id,plan_revision,task_id,dependency_task_id)"
              + " values(?,?,?,?,?,?)",
          org,
          team,
          mission,
          revision,
          e.taskId(),
          e.dependencyId());
    jdbc.update(
        "update missions set status='READY',updated_at=current_timestamp where organization_id=?"
            + " and team_id=? and id=?",
        org,
        team,
        mission);
    String ready = reviews.current(org, team, mission, human).fingerprint();
    var v = new Version(team, mission, revision, key, fingerprint, ready, content, sha(content), 1);
    jdbc.update(
        "insert into"
            + " mission_plan_acceptance_versions(organization_id,team_id,mission_id,plan_revision,actor_id,idempotency_key,schema_version,source_fingerprint,ready_fingerprint,snapshot_json,snapshot_sha256)"
            + " values(?,?,?,?,?,?,1,?,?,?,?)",
        org,
        team,
        mission,
        revision,
        human.id(),
        key,
        fingerprint,
        ready,
        content,
        v.sha());
    if (previous == null)
      jdbc.update(
          "insert into mission_plan_selections(organization_id,team_id,mission_id,plan_revision)"
              + " values(?,?,?,?)",
          org,
          team,
          mission,
          revision);
    else
      jdbc.update(
          "update mission_plan_selections set plan_revision=? where organization_id=? and team_id=?"
              + " and mission_id=?",
          revision,
          org,
          team,
          mission);
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values(?,?,?,'HUMAN',?,'MISSION_PLAN_ACCEPTED',?,current_timestamp)",
        UUID.randomUUID(),
        org,
        mission,
        human.id(),
        "{\"planRevision\":\"" + revision + "\"}");
    return receipt(org, v);
  }

  private Receipt receipt(UUID org, Version v) {
    return new Receipt(
        org, v.team(), v.mission(), Long.toString(v.revision()), v.key(), v.source(), false);
  }

  private static String sha(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Snapshot digest unavailable");
    }
  }
}

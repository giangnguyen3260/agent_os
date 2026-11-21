package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.application.MissionPlanAcceptanceService;
import com.agentos.mission.application.MissionPlanReviewService;
import com.agentos.mission.application.MissionPlanSnapshotValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "agentos.mission-graph-admission.enabled", havingValue = "true")
public class MissionGraphAdmissionService {
  public static class Conflict extends RuntimeException {}

  public record Run(UUID taskId, UUID runId, boolean root, String taskStatus, String runStatus) {}

  public record Assessment(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String status,
      String planRevision,
      MissionPlanReviewService.Review snapshot,
      List<Run> runs,
      boolean admitted,
      boolean canAdmit,
      List<String> blockers,
      String fingerprint,
      boolean executionAuthorized) {}

  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String planRevision,
      UUID idempotencyKey,
      String sourceFingerprint,
      List<Run> runs,
      boolean executionAuthorized) {}

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;
  private final MissionPlanAcceptanceService accepted;

  public MissionGraphAdmissionService(
      JdbcTemplate jdbc,
      ExecutionReviewDecisionStore authority,
      MissionPlanAcceptanceService accepted) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.accepted = accepted;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Assessment current(UUID org, UUID team, UUID mission, HumanIdentity actor) {
    authority.requireAdmin(org, actor, false);
    var a = accepted.current(org, team, mission, actor);
    String status =
        jdbc.queryForObject(
            "select status from missions where organization_id=? and team_id=? and id=?",
            String.class,
            org,
            team,
            mission);
    var revisions =
        jdbc.queryForList(
            "select plan_revision from mission_graph_admissions where organization_id=? and"
                + " team_id=? and mission_id=?",
            Long.class,
            org,
            team,
            mission);
    boolean admitted = !revisions.isEmpty();
    var blockers = new ArrayList<String>();
    if (!"READY".equals(status)) blockers.add("MISSION_NOT_READY");
    if (a.snapshot() == null || !a.matchesCurrent()) blockers.add("ACCEPTED_PLAN_NOT_CURRENT");
    if (admitted) blockers.add("ALREADY_ADMITTED");
    if (count("execution_runs", org, team, mission) > 0
        || count("mission_execution_controls", org, team, mission) > 0)
      blockers.add("RUNTIME_EXISTS");
    var runs = admitted ? mapping(org, team, mission, false) : List.<Run>of();
    if (admitted
        && (!Long.toString(revisions.getFirst()).equals(a.planRevision())
            || a.snapshot() == null
            || runs.size() != a.snapshot().taskCount())) throw new Conflict();
    String fingerprint =
        sha(
            org
                + "|"
                + team
                + "|"
                + mission
                + "|"
                + status
                + "|"
                + a.planRevision()
                + "|"
                + (a.snapshot() == null ? "" : a.snapshot().fingerprint())
                + "|"
                + a.matchesCurrent()
                + "|"
                + blockers);
    return new Assessment(
        org,
        team,
        mission,
        status,
        a.planRevision(),
        a.snapshot(),
        runs,
        admitted,
        blockers.isEmpty(),
        List.copyOf(blockers),
        fingerprint,
        false);
  }

  @Transactional
  public Receipt admit(
      UUID org,
      UUID team,
      UUID mission,
      HumanIdentity actor,
      UUID key,
      String expectedRevision,
      String expectedFingerprint) {
    authority.requireAdmin(org, actor, true);
    if (key == null
        || expectedRevision == null
        || !expectedRevision.matches("[1-9][0-9]{0,18}")
        || expectedFingerprint == null
        || !expectedFingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid admission command");
    long revision;
    try {
      revision = Long.parseLong(expectedRevision);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid plan revision");
    }
    var old =
        jdbc.query(
            "select team_id,mission_id,plan_revision,source_fingerprint from"
                + " mission_graph_admissions where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            (rs, n) ->
                new Object[] {
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getLong(3),
                  rs.getString(4)
                },
            org,
            actor.id(),
            key);
    if (!old.isEmpty()) {
      var o = old.getFirst();
      if (!team.equals(o[0])
          || !mission.equals(o[1])
          || revision != (Long) o[2]
          || !expectedFingerprint.equals(o[3])) throw new Conflict();
      return new Receipt(
          org,
          team,
          mission,
          expectedRevision,
          key,
          expectedFingerprint,
          mapping(org, team, mission, true),
          false);
    }
    var s = current(org, team, mission, actor);
    if (!s.canAdmit()
        || !expectedRevision.equals(s.planRevision())
        || !expectedFingerprint.equals(s.fingerprint())) throw new Conflict();
    MissionPlanSnapshotValidator.requireValid(s.snapshot());
    if (jdbc.update(
            "update missions set status='RUNNING',updated_at=current_timestamp where"
                + " organization_id=? and team_id=? and id=? and status='READY'",
            org,
            team,
            mission)
        != 1) throw new Conflict();
    new MissionExecutionControlStore(jdbc)
        .initialize(new MissionExecutionControlStore.Scope(org, team, mission));
    jdbc.update(
        "insert into"
            + " mission_graph_admissions(organization_id,team_id,mission_id,plan_revision,actor_id,idempotency_key,source_fingerprint)"
            + " values(?,?,?,?,?,?,?)",
        org,
        team,
        mission,
        revision,
        actor.id(),
        key,
        expectedFingerprint);
    for (var task : s.snapshot().tasks()) {
      boolean root = s.snapshot().edges().stream().noneMatch(e -> e.taskId().equals(task.id()));
      UUID run = UUID.randomUUID();
      if (jdbc.update(
              "update tasks set status=?,pending_reason=?,waiting_since="
                  + (root ? "null" : "current_timestamp")
                  + ",resolver=?,updated_at=current_timestamp where organization_id=? and team_id=?"
                  + " and mission_id=? and id=? and status='DRAFT'",
              root ? "READY" : "BLOCKED",
              root ? null : "AWAITING_ACCEPTED_DEPENDENCIES",
              root ? null : "MISSION_GRAPH_DEPENDENCIES",
              org,
              team,
              mission,
              task.id())
          != 1) throw new Conflict();
      jdbc.update(
          "insert into"
              + " execution_runs(id,organization_id,team_id,mission_id,task_id,plan_revision,status,revision,max_reworks)"
              + " values(?,?,?,?,?,?,?,0,1)",
          run,
          org,
          team,
          mission,
          task.id(),
          revision,
          root ? "CREATED" : "BLOCKED");
      jdbc.update(
          "insert into"
              + " mission_graph_admission_runs(organization_id,team_id,mission_id,plan_revision,task_id,run_id,is_root)"
              + " values(?,?,?,?,?,?,?)",
          org,
          team,
          mission,
          revision,
          task.id(),
          run,
          root);
    }
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values(?,?,?,'HUMAN',?,'MISSION_GRAPH_ADMITTED',?,current_timestamp)",
        UUID.randomUUID(),
        org,
        mission,
        actor.id(),
        "{\"planRevision\":\"" + revision + "\"}");
    return new Receipt(
        org,
        team,
        mission,
        expectedRevision,
        key,
        expectedFingerprint,
        mapping(org, team, mission, true),
        false);
  }

  private List<Run> mapping(UUID org, UUID team, UUID mission, boolean original) {
    var runs =
        jdbc.query(
            "select g.task_id,g.run_id,g.is_root,t.status,r.status from"
                + " mission_graph_admission_runs g join execution_runs r on r.id=g.run_id join"
                + " tasks t on t.id=g.task_id where g.organization_id=? and g.team_id=? and"
                + " g.mission_id=? order by g.task_id",
            (rs, n) ->
                new Run(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getBoolean(3),
                    original ? (rs.getBoolean(3) ? "READY" : "BLOCKED") : rs.getString(4),
                    original ? (rs.getBoolean(3) ? "CREATED" : "BLOCKED") : rs.getString(5)),
            org,
            team,
            mission);
    long expected =
        jdbc.queryForObject(
            "select count(*) from accepted_mission_plan_tasks p join mission_graph_admissions g on"
                + " g.organization_id=p.organization_id and g.team_id=p.team_id and"
                + " g.mission_id=p.mission_id and g.plan_revision=p.plan_revision where"
                + " g.organization_id=? and g.team_id=? and g.mission_id=?",
            Long.class,
            org,
            team,
            mission);
    if (runs.size() != expected || runs.size() > 200) throw new Conflict();
    return runs;
  }

  private long count(String table, UUID org, UUID team, UUID mission) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=? and team_id=? and mission_id=?",
        Long.class,
        org,
        team,
        mission);
  }

  static String sha(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Digest unavailable");
    }
  }
}

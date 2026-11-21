package com.agentos.orchestration.application;

import com.agentos.mission.application.MissionPlanReviewService;
import com.agentos.mission.application.MissionPlanSnapshotValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called only inside org-serialized lifecycle transactions. Never enqueues provider work. */
final class MissionGraphDependencyStore {
  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final ObjectMapper json = new ObjectMapper();

  MissionGraphDependencyStore(JdbcTemplate jdbc, boolean enabled) {
    this.jdbc = jdbc;
    this.enabled = enabled;
  }

  private record Scope(UUID team, UUID mission, long plan, UUID task) {}

  private Scope scope(UUID org, UUID run) {
    var rows =
        jdbc.query(
            "select r.team_id,r.mission_id,r.plan_revision,r.task_id from execution_runs r where"
                + " r.organization_id=? and r.id=? and (exists(select 1 from"
                + " mission_plan_selections s where s.organization_id=r.organization_id and"
                + " s.team_id=r.team_id and s.mission_id=r.mission_id) or exists(select 1 from"
                + " mission_graph_admissions a where a.organization_id=r.organization_id and"
                + " a.team_id=r.team_id and a.mission_id=r.mission_id))",
            (rs, n) ->
                new Scope(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getLong(3),
                    rs.getObject(4, UUID.class)),
            org,
            run);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  void requireAcceptedOwnOutput(UUID org, UUID run) {
    var s = scope(org, run);
    if (s == null) return;
    if (!enabled || new AcceptedRunOutputEvidence(jdbc).current(org, run, s.plan()) == null)
      throw denied();
  }

  void requireReview(UUID org, UUID run) {
    if (scope(org, run) != null && !enabled) throw denied();
  }

  void requireStart(UUID org, UUID run) {
    var s = scope(org, run);
    if (s == null) return;
    if (!enabled) throw denied();
    validate(org, s);
    var mapping =
        jdbc.queryForList(
            "select run_id from mission_graph_admission_runs where organization_id=? and team_id=?"
                + " and mission_id=? and plan_revision=? and task_id=? and run_id=?",
            UUID.class,
            org,
            s.team(),
            s.mission(),
            s.plan(),
            s.task(),
            run);
    if (mapping.size() != 1 || dependencies(org, s, s.task()) == null) throw denied();
  }

  private void validate(UUID org, Scope s) {
    var rows =
        jdbc.query(
            "select v.snapshot_json,v.snapshot_sha256,v.schema_version from"
                + " mission_graph_admissions a join mission_plan_selections c on"
                + " c.organization_id=a.organization_id and c.team_id=a.team_id and"
                + " c.mission_id=a.mission_id and c.plan_revision=a.plan_revision join"
                + " mission_plan_acceptance_versions v on v.organization_id=a.organization_id and"
                + " v.team_id=a.team_id and v.mission_id=a.mission_id and"
                + " v.plan_revision=a.plan_revision where a.organization_id=? and a.team_id=? and"
                + " a.mission_id=? and a.plan_revision=?",
            (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3)},
            org,
            s.team(),
            s.mission(),
            s.plan());
    if (rows.size() != 1) throw denied();
    try {
      var row = rows.getFirst();
      String raw = (String) row[0];
      if ((Integer) row[2] != 1
          || raw.getBytes(StandardCharsets.UTF_8).length > 8388608
          || !AcceptedRunOutputEvidence.sha(raw.getBytes(StandardCharsets.UTF_8)).equals(row[1]))
        throw denied();
      var snapshot = json.readValue(raw, MissionPlanReviewService.Review.class);
      MissionPlanSnapshotValidator.requireValid(snapshot);
      if (!org.equals(snapshot.organizationId())
          || !s.team().equals(snapshot.teamId())
          || !s.mission().equals(snapshot.missionId())) throw denied();
      var tasks =
          jdbc.query(
              "select"
                  + " t.id,t.title,t.owner_agent_id,t.description,t.acceptance_criteria,t.priority,t.task_edit_revision,t.task_owner_revision,t.dependency_revision,p.owner_agent_id,p.task_edit_revision,p.task_owner_revision,p.dependency_revision"
                  + " from accepted_mission_plan_tasks p join tasks t on"
                  + " t.organization_id=p.organization_id and t.team_id=p.team_id and"
                  + " t.mission_id=p.mission_id and t.id=p.task_id where p.organization_id=? and"
                  + " p.team_id=? and p.mission_id=? and p.plan_revision=? order by p.task_id",
              (rs, n) ->
                  List.of(
                      rs.getObject(1, UUID.class).toString(),
                      rs.getString(2),
                      rs.getObject(3, UUID.class).toString(),
                      rs.getString(4),
                      rs.getString(5),
                      rs.getString(6),
                      rs.getString(7),
                      rs.getString(8),
                      rs.getString(9),
                      rs.getObject(10, UUID.class).toString(),
                      rs.getString(11),
                      rs.getString(12),
                      rs.getString(13)),
              org,
              s.team(),
              s.mission(),
              s.plan());
      var expected =
          snapshot.tasks().stream()
              .map(
                  t ->
                      List.of(
                          t.id().toString(),
                          t.title(),
                          t.ownerId().toString(),
                          t.description(),
                          t.acceptanceCriteria(),
                          t.priority(),
                          t.editRevision(),
                          t.ownerRevision(),
                          t.dependencyRevision(),
                          t.ownerId().toString(),
                          t.editRevision(),
                          t.ownerRevision(),
                          t.dependencyRevision()))
              .toList();
      if (!tasks.equals(expected)) throw denied();
      var acceptedEdges =
          jdbc.query(
              "select task_id,dependency_task_id from accepted_mission_plan_dependencies where"
                  + " organization_id=? and team_id=? and mission_id=? and plan_revision=? order by"
                  + " task_id,dependency_task_id",
              (rs, n) ->
                  new MissionPlanReviewService.Edge(
                      rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
              org,
              s.team(),
              s.mission(),
              s.plan());
      var currentEdges =
          jdbc.query(
              "select d.task_id,d.dependency_task_id from task_dependencies d join"
                  + " accepted_mission_plan_tasks p on p.organization_id=d.organization_id and"
                  + " p.team_id=d.team_id and p.mission_id=d.mission_id and p.task_id=d.task_id and"
                  + " p.plan_revision=? where d.organization_id=? and d.team_id=? and"
                  + " d.mission_id=? order by d.task_id,d.dependency_task_id",
              (rs, n) ->
                  new MissionPlanReviewService.Edge(
                      rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
              s.plan(),
              org,
              s.team(),
              s.mission());
      if (!snapshot.edges().equals(acceptedEdges) || !acceptedEdges.equals(currentEdges))
        throw denied();
    } catch (Exception e) {
      throw denied();
    }
  }

  private List<AcceptedRunOutputEvidence.Evidence> dependencies(UUID org, Scope s, UUID task) {
    var prerequisites =
        jdbc.queryForList(
            "select dependency_task_id from accepted_mission_plan_dependencies where"
                + " organization_id=? and team_id=? and mission_id=? and plan_revision=? and"
                + " task_id=? order by dependency_task_id",
            UUID.class,
            org,
            s.team(),
            s.mission(),
            s.plan(),
            task);
    var result = new ArrayList<AcceptedRunOutputEvidence.Evidence>();
    for (var id : prerequisites) {
      var runs =
          jdbc.queryForList(
              "select run_id from mission_graph_admission_runs where organization_id=? and"
                  + " team_id=? and mission_id=? and plan_revision=? and task_id=?",
              UUID.class,
              org,
              s.team(),
              s.mission(),
              s.plan(),
              id);
      if (runs.size() != 1) return null;
      var evidence = new AcceptedRunOutputEvidence(jdbc).current(org, runs.getFirst(), s.plan());
      if (evidence == null) return null;
      result.add(evidence);
    }
    return List.copyOf(result);
  }

  void release(UUID org, UUID completedRun) {
    var s = scope(org, completedRun);
    if (s == null || !enabled) return;
    // Pause permits dependency-only readiness; per-Run Start/handoff still require OPEN.
    if (jdbc.queryForObject(
            "select count(*) from mission_execution_controls c join missions m on"
                + " m.organization_id=c.organization_id and m.team_id=c.team_id and"
                + " m.id=c.mission_id join teams t on t.organization_id=m.organization_id and"
                + " t.id=m.team_id join organizations o on o.id=t.organization_id where"
                + " c.organization_id=? and c.team_id=? and c.mission_id=? and c.state in"
                + " ('OPEN','PAUSE_REQUESTED') and m.status in ('RUNNING','WAITING_APPROVAL') and"
                + " t.status='ACTIVE' and o.status='ACTIVE'",
            Long.class,
            org,
            s.team(),
            s.mission())
        != 1) return;
    validate(org, s);
    var candidates =
        jdbc.query(
            "select g.task_id,g.run_id from mission_graph_admission_runs g join execution_runs r on"
                + " r.id=g.run_id join tasks t on t.id=g.task_id where g.organization_id=? and"
                + " g.team_id=? and g.mission_id=? and g.plan_revision=? and g.is_root=false and"
                + " r.status='BLOCKED' and r.revision=0 and t.status='BLOCKED' and"
                + " t.pending_reason='AWAITING_ACCEPTED_DEPENDENCIES' and"
                + " t.resolver='MISSION_GRAPH_DEPENDENCIES' and not exists(select 1 from"
                + " mission_graph_dependency_releases x where x.run_id=g.run_id) and not"
                + " exists(select 1 from execution_attempts x where x.run_id=g.run_id) and not"
                + " exists(select 1 from execution_start_dispatch x where x.run_id=g.run_id) and"
                + " not exists(select 1 from execution_claims x where x.run_id=g.run_id) and not"
                + " exists(select 1 from execution_budget_reservations x where x.run_id=g.run_id)"
                + " order by g.task_id",
            (rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
            org,
            s.team(),
            s.mission(),
            s.plan());
    for (var candidate : candidates) {
      var proof = dependencies(org, s, candidate[0]);
      if (proof == null || proof.isEmpty()) continue;
      String content;
      try {
        content = json.writeValueAsString(proof);
      } catch (Exception e) {
        throw denied();
      }
      if (jdbc.update(
                  "update tasks set"
                      + " status='READY',pending_reason=null,waiting_since=null,resolver=null,updated_at=current_timestamp"
                      + " where organization_id=? and id=? and status='BLOCKED' and"
                      + " pending_reason='AWAITING_ACCEPTED_DEPENDENCIES' and"
                      + " resolver='MISSION_GRAPH_DEPENDENCIES'",
                  org,
                  candidate[0])
              != 1
          || jdbc.update(
                  "update execution_runs set status='CREATED',revision=revision+1 where"
                      + " organization_id=? and id=? and status='BLOCKED' and revision=0",
                  org,
                  candidate[1])
              != 1) throw denied();
      jdbc.update(
          "insert into mission_graph_dependency_releases(run_id,evidence_json,evidence_sha256)"
              + " values(?,?,?)",
          candidate[1],
          content,
          AcceptedRunOutputEvidence.sha(content.getBytes(StandardCharsets.UTF_8)));
      jdbc.update(
          "insert into"
              + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
              + " values(?,?,?,?,'SYSTEM',null,'MISSION_GRAPH_DEPENDENCIES_RELEASED',?,current_timestamp)",
          UUID.randomUUID(),
          org,
          s.mission(),
          candidate[0],
          "{\"runId\":\"" + candidate[1] + "\"}");
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException(
        "Admitted graph configuration or dependency evidence unavailable");
  }
}

package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "agentos.mission-graph-completion.enabled", havingValue = "true")
public class MissionGraphCompletionService {
  public enum Action {
    SUBMIT_REVIEW,
    COMPLETE
  }

  public static class Conflict extends RuntimeException {}

  public record Output(
      UUID taskId,
      String title,
      UUID runId,
      UUID attemptId,
      String runRevision,
      String outputSha256,
      String requestSha256,
      String provenanceSha256) {}

  public record Snapshot(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String status,
      String planRevision,
      String controlRevision,
      int taskCount,
      List<Output> outputs,
      String manifestSha256,
      List<String> blockers,
      Action action,
      String fingerprint,
      ExecutionReviewService.Accounting accounting) {}

  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String planRevision,
      UUID idempotencyKey,
      Action action,
      String status,
      String controlRevision,
      String manifestSha256,
      List<Output> outputs) {}

  private record Manifest(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String planRevision,
      String sourceSnapshotSha256,
      List<Output> outputs) {}

  public record Content(UUID runId, UUID attemptId, String outputSha256, String content) {}

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;
  private final MissionGraphAdmissionService graphs;
  private final ObjectMapper json;

  public MissionGraphCompletionService(
      JdbcTemplate jdbc,
      ExecutionReviewDecisionStore authority,
      MissionGraphAdmissionService graphs,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.graphs = graphs;
    this.json = json;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot current(UUID org, UUID team, UUID mission, HumanIdentity actor) {
    authority.requireAdmin(org, actor, false);
    return snapshot(org, team, mission, actor);
  }

  private Snapshot snapshot(UUID org, UUID team, UUID mission, HumanIdentity actor) {
    var graph = graphs.current(org, team, mission, actor);
    var blockers = new ArrayList<String>();
    var parents =
        jdbc.queryForMap(
            "select o.status as org_status,t.status as team_status,m.status,c.state,c.revision from"
                + " missions m join teams t on t.organization_id=m.organization_id and"
                + " t.id=m.team_id join organizations o on o.id=m.organization_id left join"
                + " mission_execution_controls c on c.organization_id=m.organization_id and"
                + " c.team_id=m.team_id and c.mission_id=m.id where m.organization_id=? and"
                + " m.team_id=? and m.id=?",
            org,
            team,
            mission);
    String status = (String) parents.get("status"), control = (String) parents.get("state");
    Long revision =
        parents.get("revision") == null ? null : ((Number) parents.get("revision")).longValue();
    if (!"ACTIVE".equals(parents.get("org_status")) || !"ACTIVE".equals(parents.get("team_status")))
      blockers.add("PARENT_UNAVAILABLE");
    if (!List.of("RUNNING", "WAITING_APPROVAL", "REVIEW", "COMPLETED").contains(status))
      blockers.add("MISSION_STATE_UNAVAILABLE");
    if (revision == null
        || !"COMPLETED".equals(status) && revision == Long.MAX_VALUE
        || !List.of("OPEN", "PAUSE_REQUESTED").contains(control == null ? "" : control)
        || !"REVIEW".equals(status) && !"COMPLETED".equals(status) && revision > Long.MAX_VALUE - 2)
      blockers.add("EXECUTION_CONTROL_UNAVAILABLE");
    int count = graph.snapshot() == null ? 0 : graph.snapshot().tasks().size();
    if (!graph.admitted()
        || count < 1
        || count > 200
        || graph.runs().size() != count
        || count("tasks", org, team, mission) != count
        || count("execution_runs", org, team, mission) != count) blockers.add("UNSUPPORTED_SCOPE");
    var outputs = new ArrayList<Output>();
    if (!blockers.contains("UNSUPPORTED_SCOPE")) {
      try {
        new MissionGraphDependencyStore(jdbc, true)
            .requireConfiguration(org, graph.runs().getFirst().runId());
      } catch (IllegalStateException e) {
        blockers.add("PLAN_CONFIGURATION_UNAVAILABLE");
      }
      for (UUID task : graph.snapshot().order()) {
        var mapping =
            graph.runs().stream()
                .filter(r -> r.taskId().equals(task))
                .findFirst()
                .orElseThrow(Conflict::new);
        var proof =
            new AcceptedRunOutputEvidence(jdbc)
                .current(org, mapping.runId(), Long.parseLong(graph.planRevision()));
        if (proof == null) {
          if (!blockers.contains("ACCEPTED_OUTPUT_UNAVAILABLE"))
            blockers.add("ACCEPTED_OUTPUT_UNAVAILABLE");
          continue;
        }
        String title =
            graph.snapshot().tasks().stream()
                .filter(t -> t.id().equals(task))
                .findFirst()
                .orElseThrow(Conflict::new)
                .title();
        outputs.add(
            new Output(
                task,
                title,
                proof.runId(),
                proof.attemptId(),
                Long.toString(proof.runRevision()),
                proof.outputSha256(),
                proof.requestSha256(),
                proof.provenanceSha256()));
      }
    }
    String manifest =
        outputs.size() == count && count > 0
            ? sha(manifest(org, team, mission, graph.planRevision(), outputs))
            : null;
    if ("REVIEW".equals(status) || "COMPLETED".equals(status)) {
      String action = "REVIEW".equals(status) ? "SUBMIT_REVIEW" : "COMPLETE";
      var reviewed =
          jdbc.query(
              "select manifest_json,manifest_sha256 from mission_graph_completion_receipts where"
                  + " organization_id=? and team_id=? and mission_id=? and plan_revision=? and"
                  + " action=? and result_control_revision=? and manifest_sha256=?",
              (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
              org,
              team,
              mission,
              graph.planRevision() == null ? null : Long.parseLong(graph.planRevision()),
              action,
              revision,
              manifest);
      if (reviewed.size() != 1
          || !sha(reviewed.getFirst()[0]).equals(reviewed.getFirst()[1])
          || !reviewed.getFirst()[0].equals(
              manifest(org, team, mission, graph.planRevision(), outputs)))
        blockers.add("COMPLETION_REVIEW_UNAVAILABLE");
    }
    String fingerprint =
        sha(
            org
                + "|"
                + team
                + "|"
                + mission
                + "|"
                + graph.planRevision()
                + "|"
                + (graph.snapshot() == null ? "" : graph.snapshot().fingerprint())
                + "|"
                + status
                + "|"
                + revision
                + "|"
                + control
                + "|"
                + parents.get("org_status")
                + "|"
                + parents.get("team_status")
                + "|"
                + manifest
                + "|"
                + blockers);
    Action action =
        blockers.isEmpty() && !"COMPLETED".equals(status)
            ? ("REVIEW".equals(status) ? Action.COMPLETE : Action.SUBMIT_REVIEW)
            : null;
    return new Snapshot(
        org,
        team,
        mission,
        status,
        graph.planRevision(),
        revision == null ? null : revision.toString(),
        count,
        List.copyOf(outputs),
        manifest,
        List.copyOf(blockers),
        action,
        fingerprint,
        new RecordedAccountingStore(jdbc).mission(org, team, mission));
  }

  @Transactional
  public Receipt apply(
      UUID org,
      UUID team,
      UUID mission,
      HumanIdentity actor,
      UUID key,
      Action action,
      String expectedFingerprint) {
    authority.requireAdmin(org, actor, true);
    if (key == null
        || action == null
        || expectedFingerprint == null
        || !expectedFingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid graph completion command");
    var old =
        jdbc.query(
            "select"
                + " team_id,mission_id,plan_revision,action,source_fingerprint,result_status,result_control_revision,manifest_json,manifest_sha256"
                + " from mission_graph_completion_receipts where organization_id=? and actor_id=?"
                + " and idempotency_key=?",
            (rs, n) ->
                new Object[] {
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getLong(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getLong(7),
                  rs.getString(8),
                  rs.getString(9)
                },
            org,
            actor.id(),
            key);
    if (!old.isEmpty()) {
      var r = old.getFirst();
      if (!team.equals(r[0])
          || !mission.equals(r[1])
          || !action.name().equals(r[3])
          || !expectedFingerprint.equals(r[4])
          || !sha((String) r[7]).equals(r[8])) throw new Conflict();
      try {
        var stored = json.readValue((String) r[7], Manifest.class);
        if (!org.equals(stored.organizationId())
            || !team.equals(stored.teamId())
            || !mission.equals(stored.missionId())
            || !Long.toString((Long) r[2]).equals(stored.planRevision())) throw new Conflict();
        List<Output> outputs = stored.outputs();
        return new Receipt(
            org,
            team,
            mission,
            Long.toString((Long) r[2]),
            key,
            action,
            (String) r[5],
            Long.toString((Long) r[6]),
            (String) r[8],
            outputs);
      } catch (Exception e) {
        throw new Conflict();
      }
    }
    var s = snapshot(org, team, mission, actor);
    if (s.action() != action || !expectedFingerprint.equals(s.fingerprint())) throw new Conflict();
    long revision = Long.parseLong(s.controlRevision());
    String status = action == Action.SUBMIT_REVIEW ? "REVIEW" : "COMPLETED";
    if (jdbc.update(
                "update mission_execution_controls set revision=revision+1 where organization_id=?"
                    + " and team_id=? and mission_id=? and revision=? and state in"
                    + " ('OPEN','PAUSE_REQUESTED')",
                org,
                team,
                mission,
                revision)
            != 1
        || jdbc.update(
                "update missions set status=?,updated_at=current_timestamp where organization_id=?"
                    + " and team_id=? and id=? and status=?",
                status,
                org,
                team,
                mission,
                s.status())
            != 1) throw new Conflict();
    String manifest = manifest(org, team, mission, s.planRevision(), s.outputs());
    if (!sha(manifest).equals(s.manifestSha256())) throw new Conflict();
    jdbc.update(
        "insert into"
            + " mission_graph_completion_receipts(organization_id,team_id,mission_id,plan_revision,actor_id,idempotency_key,action,source_fingerprint,result_status,source_control_revision,result_control_revision,manifest_json,manifest_sha256)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        org,
        team,
        mission,
        Long.parseLong(s.planRevision()),
        actor.id(),
        key,
        action.name(),
        expectedFingerprint,
        status,
        revision,
        revision + 1,
        manifest,
        s.manifestSha256());
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values(?,?,?,'HUMAN',?,?,?,current_timestamp)",
        UUID.randomUUID(),
        org,
        mission,
        actor.id(),
        action == Action.SUBMIT_REVIEW
            ? "MISSION_GRAPH_REVIEW_SUBMITTED"
            : "MISSION_GRAPH_COMPLETED",
        "{\"manifestSha256\":\"" + s.manifestSha256() + "\"}");
    return new Receipt(
        org,
        team,
        mission,
        s.planRevision(),
        key,
        action,
        status,
        Long.toString(revision + 1),
        s.manifestSha256(),
        s.outputs());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Content output(
      UUID org,
      UUID team,
      UUID mission,
      HumanIdentity actor,
      UUID run,
      UUID attempt,
      String expectedSha) {
    var s = current(org, team, mission, actor);
    var item =
        s.outputs().stream()
            .filter(
                o ->
                    o.runId().equals(run)
                        && o.attemptId().equals(attempt)
                        && o.outputSha256().equals(expectedSha))
            .findFirst()
            .orElseThrow(Conflict::new);
    if (s.blockers().contains("PLAN_CONFIGURATION_UNAVAILABLE")
        || s.blockers().contains("UNSUPPORTED_SCOPE")
        || s.blockers().contains("PARENT_UNAVAILABLE")) throw new Conflict();
    String content =
        jdbc.queryForObject(
            "select content from execution_verified_output_content where run_id=? and attempt_id=?"
                + " and output_sha256=?",
            String.class,
            run,
            item.attemptId(),
            item.outputSha256());
    if (content == null
        || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
        || content.getBytes(StandardCharsets.UTF_8).length > 262144
        || !sha(content).equals(expectedSha)) throw new Conflict();
    return new Content(run, attempt, expectedSha, content);
  }

  public record Bundle(byte[] bytes, String sha256) {
    @Override
    public String toString() {
      return "MissionResultBundle[redacted]";
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Bundle bundle(
      UUID org, UUID team, UUID mission, HumanIdentity actor, String expectedManifest) {
    var snapshot = current(org, team, mission, actor);
    if (!"COMPLETED".equals(snapshot.status())
        || !snapshot.blockers().isEmpty()
        || expectedManifest == null
        || !expectedManifest.equals(snapshot.manifestSha256())) throw new Conflict();
    String manifest = manifest(org, team, mission, snapshot.planRevision(), snapshot.outputs());
    if (!sha(manifest).equals(expectedManifest)) throw new Conflict();
    var contents = new java.util.LinkedHashMap<String, byte[]>();
    byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
    long size = manifestBytes.length;
    if (size > 1048576) throw new Conflict();
    contents.put("manifest.json", manifestBytes);
    for (var item : snapshot.outputs()) {
      String text =
          jdbc.queryForObject(
              "select content from execution_verified_output_content where run_id=? and"
                  + " attempt_id=? and output_sha256=?",
              String.class,
              item.runId(),
              item.attemptId(),
              item.outputSha256());
      if (text == null || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
        throw new Conflict();
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 262144 || !sha(text).equals(item.outputSha256())) throw new Conflict();
      size += bytes.length;
      if (size > 8388608) throw new Conflict();
      contents.put("outputs/" + item.runId() + "-" + item.attemptId() + ".txt", bytes);
    }
    try (var buffer = new java.io.ByteArrayOutputStream();
        var zip = new java.util.zip.ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
      for (var entry : contents.entrySet()) {
        var path = new java.util.zip.ZipEntry(entry.getKey());
        path.setTime(0);
        zip.putNextEntry(path);
        zip.write(entry.getValue());
        zip.closeEntry();
      }
      zip.finish();
      byte[] bytes = buffer.toByteArray();
      return new Bundle(bytes, AcceptedRunOutputEvidence.sha(bytes));
    } catch (java.io.IOException e) {
      throw new Conflict();
    }
  }

  private long count(String table, UUID org, UUID team, UUID mission) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=? and team_id=? and mission_id=?",
        Long.class,
        org,
        team,
        mission);
  }

  private String manifest(UUID org, UUID team, UUID mission, String plan, List<Output> outputs) {
    String source =
        jdbc.queryForObject(
            "select snapshot_sha256 from mission_plan_acceptance_versions where organization_id=?"
                + " and team_id=? and mission_id=? and plan_revision=?",
            String.class,
            org,
            team,
            mission,
            Long.parseLong(plan));
    return encode(new Manifest(org, team, mission, plan, source, List.copyOf(outputs)));
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  private static String sha(String text) {
    return AcceptedRunOutputEvidence.sha(text.getBytes(StandardCharsets.UTF_8));
  }
}

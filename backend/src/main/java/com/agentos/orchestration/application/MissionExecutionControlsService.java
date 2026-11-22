package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
@ConditionalOnProperty(name = "agentos.mission-execution-controls.enabled", havingValue = "true")
public class MissionExecutionControlsService {
  public static class Conflict extends RuntimeException {}

  public enum Action {
    PAUSE,
    RESUME,
    REQUEST_CANCEL,
    ACK_CANCEL
  }

  public record Command(UUID idempotencyKey, String expectedFingerprint, Action action) {}

  public record Work(
      int queued,
      int claimed,
      int unknownDispatches,
      int activeAttempts,
      int unknownAttempts,
      int liveClaims,
      int unsettledReservations,
      int handedOffRequests) {}

  public record Run(
      UUID taskId, UUID runId, String title, String taskStatus, String status, String revision) {}

  public record Snapshot(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String title,
      String missionStatus,
      String controlState,
      String controlRevision,
      String planRevision,
      List<Run> runs,
      Work work,
      boolean canPause,
      boolean canResume,
      boolean canRequestCancel,
      boolean canAcknowledgeCancel,
      boolean safelyPaused,
      List<String> blockers,
      String fingerprint,
      String evidenceSha256) {}

  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID idempotencyKey,
      Action action,
      String sourceFingerprint,
      String controlRevisionBefore,
      String controlRevisionAfter,
      String controlState,
      String missionStatus,
      List<UUID> retiredQueuedDispatchIds,
      String snapshotSha256) {}

  public record Result(Receipt receipt, boolean replay) {}

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;
  private final MissionExecutionControlStore controls;
  private final ObjectMapper json;

  public MissionExecutionControlsService(
      JdbcTemplate jdbc,
      ExecutionReviewDecisionStore authority,
      MissionExecutionControlStore controls,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.controls = controls;
    this.json = json;
  }

  private String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  private String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  private int count(UUID org, UUID team, UUID mission, String query) {
    return jdbc.queryForObject(query, Integer.class, org, team, mission);
  }

  private List<UUID> queued(UUID org, UUID team, UUID mission) {
    return jdbc.queryForList(
        "select d.id from execution_start_dispatch d join execution_runs r on r.id=d.run_id where"
            + " r.organization_id=? and r.team_id=? and r.mission_id=? and d.state='QUEUED' order"
            + " by d.id limit 201",
        UUID.class,
        org,
        team,
        mission);
  }

  private Work work(UUID org, UUID team, UUID mission) {
    String scope = "r.organization_id=? and r.team_id=? and r.mission_id=?";
    String dispatch =
        "select count(*) from execution_start_dispatch d join execution_runs r on r.id=d.run_id"
            + " where "
            + scope;
    String attempts =
        "select count(*) from execution_attempts a join execution_runs r on r.id=a.run_id where "
            + scope;
    return new Work(
        count(org, team, mission, dispatch + " and d.state='QUEUED'"),
        count(org, team, mission, dispatch + " and d.state='CLAIMED'"),
        count(org, team, mission, dispatch + " and d.state='UNKNOWN'"),
        count(
            org,
            team,
            mission,
            attempts
                + " and a.status in"
                + " ('CREATED','RUNNING','WAITING_APPROVAL','WAITING_INPUT','CANCELLING')"),
        count(org, team, mission, attempts + " and a.status='UNKNOWN'"),
        count(
            org,
            team,
            mission,
            "select count(*) from execution_claims c join execution_runs r on r.id=c.run_id where "
                + scope
                + " and c.invalidated_at is null and c.expires_at>current_timestamp"),
        count(
            org,
            team,
            mission,
            "select count(*) from execution_budget_reservations b join execution_runs r on"
                + " r.id=b.run_id where "
                + scope
                + " and b.status<>'SETTLED'"),
        count(
            org,
            team,
            mission,
            "select count(*) from execution_model_authorizations a join execution_runs r on"
                + " r.id=a.run_id join execution_start_dispatch d on d.run_id=a.run_id and"
                + " d.id=a.action_id where "
                + scope
                + " and d.state='CLAIMED' and a.handed_off_at is not null"));
  }

  private record Reviewed(Snapshot snapshot, String sourceJson, String metadataJson) {}

  private String metadata(UUID org, UUID team, UUID mission) {
    var result = new java.util.TreeMap<String, Object>();
    String scope = "r.organization_id=? and r.team_id=? and r.mission_id=?";
    String[] names = {
      "dispatches",
      "attempts",
      "claims",
      "reservations",
      "authorizations",
      "usage",
      "reconciliations"
    };
    String[] queries = {
      "select"
          + " d.id,d.run_id,d.source_run_revision,d.actor_id,d.actor_credential_version,d.state,d.created_at"
          + " from execution_start_dispatch d join execution_runs r on r.id=d.run_id where "
          + scope
          + " order by d.id limit 2001",
      "select a.id,a.run_id,a.attempt_number,a.status,a.outcome_slot from execution_attempts a join"
          + " execution_runs r on r.id=a.run_id where "
          + scope
          + " order by a.run_id,a.attempt_number limit 2001",
      "select"
          + " c.run_id,c.attempt_id,c.fence,c.worker_id,c.issued_at,c.expires_at,c.deadline_at,c.invalidated_at"
          + " from execution_claims c join execution_runs r on r.id=c.run_id where "
          + scope
          + " order by c.run_id,c.fence limit 2001",
      "select"
          + " b.run_id,b.action_id,b.attempt_id,b.fence,b.reserved_microusd,b.status,b.actual_microusd"
          + " from execution_budget_reservations b join execution_runs r on r.id=b.run_id where "
          + scope
          + " order by b.run_id,b.action_id limit 2001",
      "select"
          + " a.run_id,a.action_id,a.attempt_id,a.fence,a.agent_id,a.connection_id,a.credential_revision,a.model_id,a.request_sha256,a.authorized_at,a.control_revision,a.handed_off_at,a.delivery_resolution,a.reconciled_at"
          + " from execution_model_authorizations a join execution_runs r on r.id=a.run_id where "
          + scope
          + " order by a.run_id,a.action_id limit 2001",
      "select"
          + " u.run_id,u.action_id,u.connection_id,u.provider_request_sha256,u.input_tokens,u.output_tokens,u.recorded_at"
          + " from execution_model_usage u join execution_runs r on r.id=u.run_id where "
          + scope
          + " order by u.run_id,u.action_id limit 2001",
      "select e.run_id,e.attempt_id,e.outcome,e.evidence_sha256,e.output_sha256,e.reconciled_at"
          + " from execution_attempt_reconciliations e join execution_runs r on r.id=e.run_id where"
          + " "
          + scope
          + " order by e.run_id,e.attempt_id limit 2001"
    };
    for (int i = 0; i < names.length; i++) {
      var rows = jdbc.queryForList(queries[i], org, team, mission);
      if (rows.size() > 2000) throw new Conflict();
      result.put(
          names[i],
          rows.stream()
              .map(
                  row -> {
                    var normalized = new java.util.TreeMap<String, Object>();
                    row.forEach(
                        (key, value) ->
                            normalized.put(
                                key,
                                value instanceof java.sql.Timestamp timestamp
                                    ? timestamp.toInstant().toString()
                                    : value instanceof java.time.OffsetDateTime time
                                        ? time.toInstant().toString()
                                        : value));
                    return normalized;
                  })
              .toList());
    }
    var requests =
        jdbc.query(
            "select"
                + " q.dispatch_id,q.connection_id,q.credential_revision,q.model_id,q.request_sha256,q.preflight_sha256,q.request_body,q.max_output_tokens,q.maximum_microusd,q.input_rate,q.output_rate"
                + " from execution_openai_requests q join execution_start_dispatch d on"
                + " d.id=q.dispatch_id join execution_runs r on r.id=d.run_id where "
                + scope
                + " order by q.dispatch_id limit 2001",
            (rs, n) -> {
              byte[] bytes = rs.getBytes(7);
              if (bytes == null || bytes.length > 32768) throw new Conflict();
              return java.util.Arrays.asList(
                  rs.getObject(1),
                  rs.getObject(2),
                  rs.getLong(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  AcceptedRunOutputEvidence.sha(bytes),
                  rs.getInt(8),
                  rs.getLong(9),
                  rs.getLong(10),
                  rs.getLong(11));
            },
            org,
            team,
            mission);
    if (requests.size() > 2000) throw new Conflict();
    result.put("requests", requests);
    String encoded = encode(result);
    if (encoded.getBytes(StandardCharsets.UTF_8).length > 524288) throw new Conflict();
    return encoded;
  }

  private Reviewed inspect(UUID org, UUID team, UUID mission) {
    var parents =
        jdbc.query(
            "select m.title,m.status,t.status,o.status,c.state,c.revision,a.plan_revision from"
                + " missions m join teams t on t.organization_id=m.organization_id and"
                + " t.id=m.team_id join organizations o on o.id=m.organization_id left join"
                + " mission_execution_controls c on c.organization_id=m.organization_id and"
                + " c.team_id=m.team_id and c.mission_id=m.id left join mission_graph_admissions a"
                + " on a.organization_id=m.organization_id and a.team_id=m.team_id and"
                + " a.mission_id=m.id where m.organization_id=? and m.team_id=? and m.id=?",
            (rs, n) ->
                new Object[] {
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getObject(6, Long.class),
                  rs.getObject(7, Long.class)
                },
            org,
            team,
            mission);
    if (parents.size() != 1) throw new Conflict();
    var p = parents.getFirst();
    String status = (String) p[1], state = (String) p[4];
    Long revision = (Long) p[5], plan = (Long) p[6];
    var blockers = new ArrayList<String>();
    if (!"ACTIVE".equals(p[2]) || !"ACTIVE".equals(p[3])) blockers.add("PARENT_UNAVAILABLE");
    if (!List.of("RUNNING", "WAITING_APPROVAL", "CANCELLED", "COMPLETED", "REVIEW", "FAILED")
        .contains(status)) blockers.add("MISSION_STATE_UNAVAILABLE");
    if (revision == null
        || revision <= 0
        || state == null
        || !List.of("OPEN", "PAUSE_REQUESTED", "CANCEL_REQUESTED").contains(state))
      blockers.add("CONTROL_UNAVAILABLE");
    int total =
        count(
            org,
            team,
            mission,
            "select count(*) from execution_runs r where r.organization_id=? and r.team_id=? and"
                + " r.mission_id=?");
    var runs =
        total > 0 && total <= 200
            ? jdbc.query(
                "select r.task_id,r.id,t.title,t.status,r.status,r.revision from execution_runs r"
                    + " join tasks t on t.organization_id=r.organization_id and t.team_id=r.team_id"
                    + " and t.mission_id=r.mission_id and t.id=r.task_id where r.organization_id=?"
                    + " and r.team_id=? and r.mission_id=? order by r.id",
                (rs, n) ->
                    new Run(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6)),
                org,
                team,
                mission)
            : List.<Run>of();
    if (plan == null
        || runs.isEmpty()
        || runs.size() != total
        || count(
                org,
                team,
                mission,
                "select count(*) from tasks r where r.organization_id=? and r.team_id=? and"
                    + " r.mission_id=?")
            != total
        || count(
                org,
                team,
                mission,
                "select count(*) from mission_graph_admission_runs r where r.organization_id=? and"
                    + " r.team_id=? and r.mission_id=?")
            != total) blockers.add("UNSUPPORTED_SCOPE");
    if (!blockers.contains("UNSUPPORTED_SCOPE")) {
      try {
        new MissionGraphDependencyStore(jdbc, true)
            .requireConfiguration(org, runs.getFirst().runId());
      } catch (RuntimeException e) {
        blockers.add("PLAN_CONFIGURATION_UNAVAILABLE");
      }
      if (jdbc.queryForObject(
              "select count(*) from execution_runs r join mission_graph_admission_runs a on"
                  + " a.run_id=r.id and a.organization_id=r.organization_id and a.team_id=r.team_id"
                  + " and a.mission_id=r.mission_id and a.task_id=r.task_id and"
                  + " a.plan_revision=r.plan_revision where r.organization_id=? and r.team_id=? and"
                  + " r.mission_id=? and r.plan_revision=?",
              Integer.class,
              org,
              team,
              mission,
              plan)
          != total) blockers.add("UNSUPPORTED_SCOPE");
    }
    if (runs.stream()
        .anyMatch(
            r ->
                !switch (r.status()) {
                  case "CREATED" -> "READY".equals(r.taskStatus());
                  case "BLOCKED" -> "BLOCKED".equals(r.taskStatus());
                  case "RUNNING" -> "RUNNING".equals(r.taskStatus());
                  case "WAITING" -> "WAITING".equals(r.taskStatus());
                  case "IN_REVIEW" -> "IN_REVIEW".equals(r.taskStatus());
                  case "COMPLETED" -> "COMPLETED".equals(r.taskStatus());
                  case "CANCELLED" -> "CANCELLED".equals(r.taskStatus());
                  case "FAILED" -> "FAILED".equals(r.taskStatus());
                  case "CANCELLING" ->
                      List.of("READY", "BLOCKED", "RUNNING", "WAITING", "IN_REVIEW", "FAILED")
                          .contains(r.taskStatus());
                  default -> false;
                })) blockers.add("INCONSISTENT_RUNTIME_SCOPE");
    var w = work(org, team, mission);
    if (w.queued() > 200) blockers.add("SCOPE_LIMIT_EXCEEDED");
    boolean drained = w.claimed() == 0 && w.activeAttempts() == 0 && w.liveClaims() == 0;
    boolean reconciled =
        drained
            && w.unknownDispatches() == 0
            && w.unknownAttempts() == 0
            && w.unsettledReservations() == 0;
    boolean unusedQueue =
        count(
                org,
                team,
                mission,
                "select count(*) from execution_start_dispatch d join execution_runs r on"
                    + " r.id=d.run_id where r.organization_id=? and r.team_id=? and r.mission_id=?"
                    + " and d.state='QUEUED' and (exists(select 1 from"
                    + " execution_model_authorizations a where a.run_id=r.id and a.action_id=d.id)"
                    + " or exists(select 1 from execution_budget_reservations b where b.run_id=r.id"
                    + " and b.action_id=d.id) or exists(select 1 from execution_model_usage u where"
                    + " u.run_id=r.id and u.action_id=d.id))")
            == 0;
    boolean mutable = blockers.isEmpty() && List.of("RUNNING", "WAITING_APPROVAL").contains(status);
    boolean requestHeadroom = revision != null && revision <= Long.MAX_VALUE - 3;
    boolean runHeadroom =
        runs.stream()
            .allMatch(
                r ->
                    List.of("COMPLETED", "CANCELLED").contains(r.status())
                        || Long.parseLong(r.revision()) <= Long.MAX_VALUE - 2);
    boolean pause =
        mutable
            && "OPEN".equals(state)
            && revision <= Long.MAX_VALUE - 4
            && runs.stream()
                .allMatch(
                    r ->
                        List.of("COMPLETED", "CANCELLED").contains(r.status())
                            || Long.parseLong(r.revision()) <= Long.MAX_VALUE - 3);
    boolean resume = mutable && "PAUSE_REQUESTED".equals(state) && requestHeadroom && drained;
    boolean cancel =
        mutable
            && !"CANCEL_REQUESTED".equals(state)
            && revision <= Long.MAX_VALUE - 2
            && reconciled
            && unusedQueue
            && runHeadroom;
    boolean ack =
        mutable
            && "CANCEL_REQUESTED".equals(state)
            && revision < Long.MAX_VALUE
            && reconciled
            && w.queued() == 0
            && runs.stream()
                .allMatch(
                    r ->
                        List.of("CANCELLING", "COMPLETED", "CANCELLED").contains(r.status())
                            && (!"CANCELLING".equals(r.status())
                                || Long.parseLong(r.revision()) < Long.MAX_VALUE));
    if (!drained) blockers.add("WORKER_DRAIN_PENDING");
    if (!reconciled) blockers.add("RECONCILIATION_PENDING");
    if (!unusedQueue) blockers.add("QUEUED_EVIDENCE_UNAVAILABLE");
    if (mutable && !pause && !resume && !cancel && !ack && (!requestHeadroom || !runHeadroom))
      blockers.add("REVISION_HEADROOM_UNAVAILABLE");
    String planSource =
        plan == null
            ? null
            : jdbc.queryForObject(
                "select snapshot_sha256 from mission_plan_acceptance_versions where"
                    + " organization_id=? and team_id=? and mission_id=? and plan_revision=?",
                String.class,
                org,
                team,
                mission,
                plan);
    String evidence = metadata(org, team, mission), evidenceSha = sha(evidence);
    String source =
        encode(
            java.util.Arrays.asList(
                "MISSION_EXECUTION_CONTROLS_V1",
                org,
                team,
                mission,
                p[0],
                status,
                p[2],
                p[3],
                state,
                revision,
                plan,
                planSource,
                runs,
                w,
                queued(org, team, mission),
                blockers,
                evidenceSha));
    String fp = sha(source);
    var snapshot =
        new Snapshot(
            org,
            team,
            mission,
            (String) p[0],
            status,
            state,
            revision == null ? null : revision.toString(),
            plan == null ? null : plan.toString(),
            List.copyOf(runs),
            w,
            pause,
            resume,
            cancel,
            ack,
            "PAUSE_REQUESTED".equals(state) && reconciled,
            List.copyOf(blockers),
            fp,
            evidenceSha);
    var reviewed = new Reviewed(snapshot, source, evidence);
    if (encode(reviewed).getBytes(StandardCharsets.UTF_8).length > 2097152) throw new Conflict();
    return reviewed;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot current(UUID org, UUID team, UUID mission, HumanIdentity actor) {
    authority.requireAdmin(org, actor, false);
    return inspect(org, team, mission).snapshot();
  }

  private Receipt saved(
      UUID org, UUID team, UUID mission, HumanIdentity actor, UUID key, String digest) {
    var rows =
        jdbc.query(
            "select"
                + " team_id,mission_id,action,command_sha256,source_fingerprint,source_control_revision,result_control_revision,response_json,response_sha256,snapshot_json,snapshot_sha256"
                + " from mission_execution_control_receipts where organization_id=? and actor_id=?"
                + " and idempotency_key=?",
            (rs, n) ->
                new String[] {
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
                  rs.getString(9),
                  rs.getString(10),
                  rs.getString(11)
                },
            org,
            actor.id(),
            key);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (!team.toString().equals(row[0])
        || !mission.toString().equals(row[1])
        || digest != null && !digest.equals(row[3])
        || !sha(row[7]).equals(row[8])
        || !sha(row[9]).equals(row[10])) throw new Conflict();
    try {
      var r = json.readValue(row[7], Receipt.class);
      var image = json.readValue(row[9], Reviewed.class);
      var source = json.readTree(image.sourceJson());
      var snapshot = image.snapshot();
      if (!sha(image.sourceJson()).equals(r.sourceFingerprint())
          || !sha(image.metadataJson()).equals(snapshot.evidenceSha256())
          || !r.snapshotSha256().equals(row[10])
          || !snapshot.fingerprint().equals(r.sourceFingerprint())
          || !org.equals(snapshot.organizationId())
          || !team.equals(snapshot.teamId())
          || !mission.equals(snapshot.missionId())
          || !snapshot.controlRevision().equals(r.controlRevisionBefore())
          || !source.isArray()
          || source.size() != 17
          || !source.get(1).asText().equals(org.toString())
          || !source.get(2).asText().equals(team.toString())
          || !source.get(3).asText().equals(mission.toString())
          || !source.get(16).asText().equals(snapshot.evidenceSha256())) throw new Conflict();
      var expectedRetired =
          r.action() == Action.REQUEST_CANCEL
              ? json.convertValue(
                  source.get(14),
                  new com.fasterxml.jackson.core.type.TypeReference<List<UUID>>() {})
              : List.<UUID>of();
      if (!expectedRetired.equals(r.retiredQueuedDispatchIds())) throw new Conflict();
      if (!org.equals(r.organizationId())
          || !team.equals(r.teamId())
          || !mission.equals(r.missionId())
          || !key.equals(r.idempotencyKey())
          || !r.action().name().equals(row[2])
          || !r.sourceFingerprint().equals(row[4])
          || !r.controlRevisionBefore().equals(row[5])
          || !r.controlRevisionAfter().equals(row[6])
          || Long.parseLong(r.controlRevisionAfter())
              != Math.addExact(Long.parseLong(r.controlRevisionBefore()), 1)
          || r.retiredQueuedDispatchIds().size() > 200
          || new java.util.HashSet<>(r.retiredQueuedDispatchIds()).size()
              != r.retiredQueuedDispatchIds().size()) throw new Conflict();
      return r;
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Receipt receipt(UUID org, UUID team, UUID mission, HumanIdentity actor, UUID key) {
    authority.requireAdmin(org, actor, false);
    var r = saved(org, team, mission, actor, key, null);
    if (r == null) throw new Conflict();
    return r;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot reviewedSnapshot(
      UUID org, UUID team, UUID mission, HumanIdentity actor, UUID key) {
    receipt(org, team, mission, actor, key);
    String raw =
        jdbc.queryForObject(
            "select snapshot_json from mission_execution_control_receipts where organization_id=?"
                + " and actor_id=? and idempotency_key=?",
            String.class,
            org,
            actor.id(),
            key);
    try {
      return json.readValue(raw, Reviewed.class).snapshot();
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result apply(UUID org, UUID team, UUID mission, HumanIdentity actor, Command command) {
    authority.requireAdmin(org, actor, true);
    if (command == null
        || command.idempotencyKey() == null
        || command.action() == null
        || command.expectedFingerprint() == null
        || !command.expectedFingerprint().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid execution control command");
    String digest =
        sha(
            encode(
                List.of("EXECUTION_CONTROL_COMMAND_V1", org, team, mission, actor.id(), command)));
    var replay = saved(org, team, mission, actor, command.idempotencyKey(), digest);
    if (replay != null) return new Result(replay, true);
    var reviewed = inspect(org, team, mission);
    var s = reviewed.snapshot();
    if (!s.fingerprint().equals(command.expectedFingerprint())
        || !switch (command.action()) {
          case PAUSE -> s.canPause();
          case RESUME -> s.canResume();
          case REQUEST_CANCEL -> s.canRequestCancel();
          case ACK_CANCEL -> s.canAcknowledgeCancel();
        }) throw new Conflict();
    var retired =
        command.action() == Action.REQUEST_CANCEL ? queued(org, team, mission) : List.<UUID>of();
    for (var id : retired)
      if (jdbc.update(
              "update execution_start_dispatch set state='FINISHED' where organization_id=? and"
                  + " id=? and state='QUEUED'",
              org,
              id)
          != 1) throw new Conflict();
    var scope = new MissionExecutionControlStore.Scope(org, team, mission);
    long before = Long.parseLong(s.controlRevision()), after;
    try {
      after =
          command.action() == Action.ACK_CANCEL
              ? controls.acknowledgeCancellation(scope, before)
              : controls.change(
                  scope,
                  before,
                  switch (command.action()) {
                    case PAUSE -> MissionExecutionControlStore.State.PAUSE_REQUESTED;
                    case RESUME -> MissionExecutionControlStore.State.OPEN;
                    case REQUEST_CANCEL -> MissionExecutionControlStore.State.CANCEL_REQUESTED;
                    default -> throw new Conflict();
                  });
    } catch (IllegalStateException e) {
      throw new Conflict();
    }
    var r =
        new Receipt(
            org,
            team,
            mission,
            command.idempotencyKey(),
            command.action(),
            s.fingerprint(),
            s.controlRevision(),
            Long.toString(after),
            switch (command.action()) {
              case PAUSE -> "PAUSE_REQUESTED";
              case RESUME -> "OPEN";
              default -> "CANCEL_REQUESTED";
            },
            command.action() == Action.ACK_CANCEL ? "CANCELLED" : s.missionStatus(),
            List.copyOf(retired),
            sha(encode(reviewed)));
    String response = encode(r);
    jdbc.update(
        "insert into"
            + " mission_execution_control_receipts(organization_id,team_id,mission_id,actor_id,idempotency_key,action,command_sha256,source_fingerprint,source_control_revision,result_control_revision,response_json,response_sha256,snapshot_json,snapshot_sha256)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        org,
        team,
        mission,
        actor.id(),
        command.idempotencyKey(),
        command.action().name(),
        digest,
        s.fingerprint(),
        before,
        after,
        response,
        sha(response),
        encode(reviewed),
        r.snapshotSha256());
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values(?,?,?,'HUMAN',?,'MISSION_EXECUTION_CONTROL_CHANGED',?,current_timestamp)",
        UUID.randomUUID(),
        org,
        mission,
        actor.id(),
        encode(
            java.util.Map.of(
                "action",
                command.action().name(),
                "controlRevision",
                Long.toString(after),
                "retiredQueuedCount",
                retired.size())));
    return new Result(r, false);
  }
}

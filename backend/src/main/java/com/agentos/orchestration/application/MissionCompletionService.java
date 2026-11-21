package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
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

/** Explicit review then closure of a fully accounted, accepted single-Task Mission. */
@Service
@ConditionalOnProperty(name = "agentos.mission-completion.enabled", havingValue = "true")
public class MissionCompletionService {
  public enum Action {
    SUBMIT_REVIEW,
    COMPLETE
  }

  public record Snapshot(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String status,
      String controlRevision,
      UUID taskId,
      UUID runId,
      UUID attemptId,
      String outputSha256,
      String outputText,
      Action action,
      List<String> blockers,
      String fingerprint) {}

  public record Command(UUID idempotencyKey, Action action, String expectedFingerprint) {}

  public record Receipt(
      UUID missionId,
      Action action,
      String status,
      String controlRevision,
      UUID taskId,
      UUID runId,
      UUID attemptId,
      String outputSha256) {}

  public static class Conflict extends RuntimeException {
    public Conflict() {
      super("Mission completion conditions changed or are unavailable.");
    }
  }

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;

  MissionCompletionService(JdbcTemplate jdbc, ExecutionReviewDecisionStore authority) {
    this.jdbc = jdbc;
    this.authority = authority;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot current(UUID org, UUID team, UUID mission, HumanIdentity human) {
    authority.requireAdmin(org, human, false);
    return snapshot(org, team, mission);
  }

  private Snapshot snapshot(UUID org, UUID team, UUID mission) {
    var parents =
        jdbc.query(
            """
            select m.status,t.status,o.status,c.revision,c.state
            from missions m join teams t on t.organization_id=m.organization_id and t.id=m.team_id
            join organizations o on o.id=m.organization_id
            left join mission_execution_controls c on c.organization_id=m.organization_id
              and c.team_id=m.team_id and c.mission_id=m.id
            where m.organization_id=? and m.team_id=? and m.id=?
            """,
            (rs, n) ->
                new Object[] {
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getObject(4, Long.class),
                  rs.getString(5)
                },
            org,
            team,
            mission);
    if (parents.isEmpty()) throw new EntityNotFoundException("Mission not found");
    var parent = parents.getFirst();
    String status = (String) parent[0];
    Long revision = (Long) parent[3];
    var blockers = new ArrayList<String>();
    if (!"ACTIVE".equals(parent[1]) || !"ACTIVE".equals(parent[2]))
      blockers.add("PARENT_UNAVAILABLE");
    if (!List.of("RUNNING", "WAITING_APPROVAL", "REVIEW", "COMPLETED").contains(status))
      blockers.add("MISSION_STATE_UNAVAILABLE");
    if (revision == null
        || revision == Long.MAX_VALUE
        || !List.of("OPEN", "PAUSE_REQUESTED").contains(parent[4] == null ? "" : parent[4]))
      blockers.add("EXECUTION_CONTROL_UNAVAILABLE");
    if (count(
            "select count(*) from mission_graph_admissions where organization_id=? and team_id=?"
                + " and mission_id=?",
            org,
            team,
            mission)
        > 0) blockers.add("UNSUPPORTED_SCOPE");
    if (count(
                "select count(*) from tasks where organization_id=? and team_id=? and mission_id=?",
                org,
                team,
                mission)
            != 1
        || count(
                "select count(*) from execution_runs where organization_id=? and team_id=? and"
                    + " mission_id=?",
                org,
                team,
                mission)
            != 1) blockers.add("UNSUPPORTED_SCOPE");
    UUID task = null, run = null, attempt = null;
    String output = null;
    String outputText = null;
    String evidence = "";
    if (!blockers.contains("UNSUPPORTED_SCOPE")) {
      var rows =
          jdbc.query(
              """
select t.id,r.id,a.id,e.output_sha256,x.content,r.revision,r.plan_revision,
  a.attempt_number,p.owner_agent_id,p.task_edit_revision,p.task_owner_revision,p.dependency_revision,
  z.action_id,z.connection_id,q.request_sha256,u.provider_request_sha256,u.input_tokens,u.output_tokens,
  b.actual_microusd,q.input_rate,q.output_rate,q.maximum_microusd,q.request_body
from tasks t
join execution_runs r on r.organization_id=t.organization_id and r.team_id=t.team_id
  and r.mission_id=t.mission_id and r.task_id=t.id
join accepted_mission_plan_tasks p on p.organization_id=r.organization_id and p.team_id=r.team_id
  and p.mission_id=r.mission_id and p.plan_revision=r.plan_revision and p.task_id=t.id
join execution_attempts a on a.run_id=r.id and a.status='SUCCEEDED'
  and a.attempt_number=(select max(a2.attempt_number) from execution_attempts a2 where a2.run_id=r.id)
join execution_review_decisions d on d.run_id=r.id and d.attempt_id=a.id
  and d.decision='ACCEPT' and d.source_run_revision=r.revision-1
join execution_reconciled_review_submissions s on s.run_id=r.id and s.attempt_id=a.id
  and s.source_run_revision=d.source_run_revision-1
join execution_attempt_reconciliations e on e.run_id=r.id and e.attempt_id=a.id and e.outcome='SUCCEEDED'
join execution_verified_output_content x on x.run_id=r.id and x.attempt_id=a.id and x.output_sha256=e.output_sha256
join execution_model_authorizations z on z.run_id=r.id and z.attempt_id=a.id
  and z.organization_id=r.organization_id and z.agent_id=p.owner_agent_id and z.handed_off_at is not null
join execution_budget_reservations b on b.run_id=z.run_id and b.action_id=z.action_id
  and b.attempt_id=a.id and b.fence=z.fence and b.status='SETTLED'
join execution_model_usage u on u.organization_id=r.organization_id and u.run_id=z.run_id
  and u.action_id=z.action_id and u.connection_id=z.connection_id
join execution_start_dispatch sd on sd.organization_id=r.organization_id and sd.run_id=r.id
  and sd.id=z.action_id and sd.state='FINISHED'
join execution_openai_requests q on q.dispatch_id=sd.id and q.connection_id=z.connection_id
  and q.credential_revision=z.credential_revision and q.model_id=z.model_id and q.request_sha256=z.request_sha256
where t.organization_id=? and t.team_id=? and t.mission_id=?
  and t.status='COMPLETED' and r.status='COMPLETED'
  and t.owner_agent_id=p.owner_agent_id and t.task_edit_revision=p.task_edit_revision
  and t.task_owner_revision=p.task_owner_revision and t.dependency_revision=p.dependency_revision
  and (select count(*) from accepted_mission_plan_tasks p2 where p2.organization_id=r.organization_id
    and p2.team_id=r.team_id and p2.mission_id=r.mission_id and p2.plan_revision=r.plan_revision)=1
  and not exists(select 1 from accepted_mission_plan_dependencies pd where pd.organization_id=r.organization_id
    and pd.team_id=r.team_id and pd.mission_id=r.mission_id and pd.plan_revision=r.plan_revision)
  and not exists(select 1 from task_dependencies td where td.organization_id=t.organization_id
    and td.team_id=t.team_id and td.mission_id=t.mission_id)
""",
              (rs, n) ->
                  new Object[] {
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getLong(6),
                    rs.getLong(7),
                    rs.getInt(8),
                    rs.getObject(9, UUID.class),
                    rs.getInt(10),
                    rs.getInt(11),
                    rs.getInt(12),
                    rs.getObject(13, UUID.class),
                    rs.getObject(14, UUID.class),
                    rs.getString(15),
                    rs.getString(16),
                    rs.getLong(17),
                    rs.getLong(18),
                    rs.getLong(19),
                    rs.getLong(20),
                    rs.getLong(21),
                    rs.getLong(22),
                    rs.getBytes(23)
                  },
              org,
              team,
              mission);
      if (rows.size() != 1) blockers.add("ACCEPTED_OUTPUT_UNAVAILABLE");
      else {
        var row = rows.getFirst();
        task = (UUID) row[0];
        run = (UUID) row[1];
        attempt = (UUID) row[2];
        output = (String) row[3];
        if (!StandardCharsets.UTF_8.newEncoder().canEncode((String) row[4])
            || ((String) row[4]).getBytes(StandardCharsets.UTF_8).length > 262144
            || !sha((String) row[4]).equals(output)) blockers.add("OUTPUT_INTEGRITY_UNAVAILABLE");
        if (!shaBytes((byte[]) row[22]).equals(row[14]))
          blockers.add("REQUEST_INTEGRITY_UNAVAILABLE");
        var actual =
            java.math.BigInteger.valueOf((Long) row[16])
                .multiply(java.math.BigInteger.valueOf((Long) row[19]))
                .add(
                    java.math.BigInteger.valueOf((Long) row[17])
                        .multiply(java.math.BigInteger.valueOf((Long) row[20])))
                .add(java.math.BigInteger.valueOf(999999))
                .divide(java.math.BigInteger.valueOf(1000000));
        if (!actual.equals(java.math.BigInteger.valueOf((Long) row[18]))
            || (Long) row[18] > (Long) row[21]) blockers.add("USAGE_EVIDENCE_UNAVAILABLE");
        if (!blockers.contains("OUTPUT_INTEGRITY_UNAVAILABLE")) outputText = (String) row[4];
        evidence = java.util.Arrays.asList(row).subList(5, 22).toString();
      }
      var runs =
          jdbc.queryForList(
              "select id from execution_runs where organization_id=? and team_id=? and"
                  + " mission_id=?",
              UUID.class,
              org,
              team,
              mission);
      if (!runs.isEmpty()) {
        UUID id = runs.getFirst();
        if (count(
                "select count(*) from execution_attempts where run_id=? and status not in"
                    + " ('SUCCEEDED','FAILED','CANCELLED')",
                id)
            > 0) blockers.add("ATTEMPT_UNRESOLVED");
        if (count(
                "select count(*) from execution_claims where run_id=? and invalidated_at is null"
                    + " and expires_at>current_timestamp",
                id)
            > 0) blockers.add("LIVE_CLAIM");
        if (count(
                "select count(*) from execution_budget_reservations where run_id=? and"
                    + " status<>'SETTLED'",
                id)
            > 0) blockers.add("ACCOUNTING_UNRESOLVED");
        if (count(
                "select count(*) from execution_start_dispatch where run_id=? and"
                    + " state<>'FINISHED'",
                id)
            > 0) blockers.add("DISPATCH_UNRESOLVED");
      }
    }
    // REVIEW must have been entered by this exact completion flow and pinned evidence.
    if ("REVIEW".equals(status) || "COMPLETED".equals(status)) {
      if (count(
              """
select count(*) from mission_completion_receipts where organization_id=? and team_id=? and mission_id=?
  and action=? and result_control_revision=? and task_id=? and run_id=? and attempt_id=? and output_sha256=?
""",
              org,
              team,
              mission,
              "REVIEW".equals(status) ? "SUBMIT_REVIEW" : "COMPLETE",
              revision,
              task,
              run,
              attempt,
              output)
          != 1) blockers.add("COMPLETION_REVIEW_UNAVAILABLE");
    }
    Action action =
        blockers.isEmpty() && !"COMPLETED".equals(status)
            ? ("REVIEW".equals(status) ? Action.COMPLETE : Action.SUBMIT_REVIEW)
            : null;
    var copy = List.copyOf(blockers);
    String fingerprint =
        sha(
            org + "|" + team + "|" + mission + "|" + status + "|" + parent[1] + "|" + parent[2]
                + "|" + revision + "|" + parent[4] + "|" + task + "|" + run + "|" + attempt + "|"
                + output + "|" + evidence + "|" + copy);
    return new Snapshot(
        org,
        team,
        mission,
        status,
        revision == null ? null : revision.toString(),
        task,
        run,
        attempt,
        output,
        outputText,
        action,
        copy,
        fingerprint);
  }

  @Transactional
  public Receipt apply(UUID org, UUID team, UUID mission, HumanIdentity human, Command command) {
    authority.requireAdmin(org, human, true);
    if (command == null
        || command.idempotencyKey() == null
        || command.action() == null
        || command.expectedFingerprint() == null
        || !command.expectedFingerprint().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid Mission completion command");
    var receipts =
        jdbc.query(
            """
select team_id,mission_id,action,source_fingerprint,result_status,result_control_revision,task_id,run_id,attempt_id,output_sha256
from mission_completion_receipts where organization_id=? and actor_id=? and idempotency_key=?
""",
            (rs, n) ->
                new Object[] {
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getObject(7, UUID.class),
                  rs.getObject(8, UUID.class),
                  rs.getObject(9, UUID.class),
                  rs.getString(10)
                },
            org,
            human.id(),
            command.idempotencyKey());
    if (!receipts.isEmpty()) {
      var r = receipts.getFirst();
      if (!team.equals(r[0])
          || !mission.equals(r[1])
          || !command.action().name().equals(r[2])
          || !command.expectedFingerprint().equals(r[3])) throw new Conflict();
      return new Receipt(
          mission,
          command.action(),
          (String) r[4],
          (String) r[5],
          (UUID) r[6],
          (UUID) r[7],
          (UUID) r[8],
          (String) r[9]);
    }
    // All lifecycle writers serialize under the organization lock held above.
    var s = snapshot(org, team, mission);
    if (s.action() != command.action()
        || !s.blockers().isEmpty()
        || !s.fingerprint().equals(command.expectedFingerprint())) throw new Conflict();
    long revision = Long.parseLong(s.controlRevision());
    String next = command.action() == Action.SUBMIT_REVIEW ? "REVIEW" : "COMPLETED";
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
                next,
                org,
                team,
                mission,
                s.status())
            != 1) throw new Conflict();
    jdbc.update(
        """
insert into mission_completion_receipts(organization_id,team_id,mission_id,actor_id,idempotency_key,action,source_fingerprint,source_control_revision,result_control_revision,result_status,task_id,run_id,attempt_id,output_sha256)
values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
        org,
        team,
        mission,
        human.id(),
        command.idempotencyKey(),
        command.action().name(),
        command.expectedFingerprint(),
        revision,
        revision + 1,
        next,
        s.taskId(),
        s.runId(),
        s.attemptId(),
        s.outputSha256());
    jdbc.update(
        """
insert into audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at)
values(?,?,?,'HUMAN',?,?,?,current_timestamp)
""",
        UUID.randomUUID(),
        org,
        mission,
        human.id(),
        command.action() == Action.SUBMIT_REVIEW
            ? "MISSION_COMPLETION_REVIEW_SUBMITTED"
            : "MISSION_COMPLETED",
        "{\"runId\":\""
            + s.runId()
            + "\",\"attemptId\":\""
            + s.attemptId()
            + "\",\"outputSha256\":\""
            + s.outputSha256()
            + "\"}");
    return new Receipt(
        mission,
        command.action(),
        next,
        Long.toString(revision + 1),
        s.taskId(),
        s.runId(),
        s.attemptId(),
        s.outputSha256());
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private static String sha(String text) {
    return shaBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String shaBytes(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Digest unavailable");
    }
  }
}

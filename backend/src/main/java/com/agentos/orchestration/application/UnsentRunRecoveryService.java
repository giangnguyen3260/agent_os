package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepares a definite pre-provider failure only; never sends, claims or resolves UNKNOWN. */
@Service
@ConditionalOnProperty(name = "agentos.execution.unsent-recovery.enabled", havingValue = "true")
public class UnsentRunRecoveryService {
  public static class Conflict extends RuntimeException {}

  public record Context(UUID runId, String revision, String fingerprint) {}

  public record Receipt(
      UUID runId, UUID idempotencyKey, String sourceRevision, String resultingRevision) {}

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;
  private final ExecutionStartService starts;

  public UnsentRunRecoveryService(
      JdbcTemplate jdbc, ExecutionReviewDecisionStore authority, ExecutionStartService starts) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.starts = starts;
  }

  private Context target(UUID org, UUID run) {
    var rows =
        jdbc.queryForList(
            """
select r.revision from execution_runs r
join tasks t on t.organization_id=r.organization_id and t.id=r.task_id
join execution_unsent_failure_evidence e on e.organization_id=r.organization_id and e.run_id=r.id
join execution_start_dispatch d on d.organization_id=r.organization_id and d.run_id=r.id and d.id=e.dispatch_id and d.source_run_revision=r.revision-2 and d.state='FINISHED'
join execution_attempts a on a.run_id=r.id and a.id=e.attempt_id and a.status='FAILED' and a.outcome_slot=a.attempt_number and a.attempt_number=(select max(x.attempt_number) from execution_attempts x where x.run_id=r.id)
join execution_claims c on c.run_id=r.id and c.attempt_id=a.id and c.fence=e.fence and c.invalidated_at is not null and c.fence=(select max(z.fence) from execution_claims z where z.run_id=r.id)
join execution_openai_requests q on q.dispatch_id=d.id and q.request_sha256=e.request_sha256
where r.organization_id=? and r.id=? and r.status='BLOCKED' and r.revision between 2 and 9223372036854775802
and t.status='BLOCKED' and t.pending_reason='EXECUTION_UNAVAILABLE'
and not exists(select 1 from execution_start_dispatch x where x.run_id=r.id and x.state<>'FINISHED')
and not exists(select 1 from execution_attempts x where x.run_id=r.id and x.status not in ('SUCCEEDED','FAILED','CANCELLED'))
and not exists(select 1 from execution_claims x where x.run_id=r.id and x.invalidated_at is null)
and not exists(select 1 from execution_budget_reservations b where b.run_id=r.id and b.status<>'SETTLED')
and not exists(select 1 from execution_budget_reservations b where b.run_id=r.id and b.attempt_id=a.id and b.actual_microusd<>0)
and not exists(select 1 from execution_model_usage u join execution_budget_reservations b on b.run_id=u.run_id and b.action_id=u.action_id where b.run_id=r.id and b.attempt_id=a.id)
and not exists(select 1 from execution_model_authorizations m where m.run_id=r.id and m.attempt_id=a.id and m.handed_off_at is not null)
""",
            Long.class,
            org,
            run);
    if (rows.size() != 1) throw new Conflict();
    long revision = rows.getFirst();
    return new Context(
        run,
        Long.toString(revision),
        TextToolGrantService.hash("unsent.recovery@1|" + org + "|" + run + "|" + revision));
  }

  @Transactional
  public Context preview(UUID org, UUID run, HumanIdentity actor) {
    authority.requireAdmin(org, actor, true);
    return target(org, run);
  }

  @Transactional
  public Receipt prepare(UUID org, UUID run, HumanIdentity actor, UUID key, String fingerprint) {
    authority.requireAdmin(org, actor, true);
    if (key == null || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid recovery command");
    var old =
        jdbc.query(
            "select run_id,source_revision,resulting_revision,fingerprint from"
                + " unsent_run_recovery_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            (rs, n) ->
                new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
            org,
            actor.id(),
            key);
    if (!old.isEmpty()) {
      var r = old.getFirst();
      if (!run.toString().equals(r[0]) || !fingerprint.equals(r[3])) throw new Conflict();
      long source = Long.parseLong(r[1]);
      if (!Long.toString(source + 1).equals(r[2])
          || !TextToolGrantService.hash("unsent.recovery@1|" + org + "|" + run + "|" + source)
              .equals(fingerprint)) throw new Conflict();
      return new Receipt(run, key, r[1], r[2]);
    }
    Context c = target(org, run);
    if (!c.fingerprint().equals(fingerprint)) throw new Conflict();
    long revision = Long.parseLong(c.revision());
    if (jdbc.update(
            "update execution_runs set status='CREATED',wait_reason=null,revision=revision+1 where"
                + " organization_id=? and id=? and revision=? and status='BLOCKED'",
            org,
            run,
            revision)
        != 1) throw new Conflict();
    if (jdbc.update(
            "update tasks set"
                + " status='READY',pending_reason=null,waiting_since=null,resolver=null,updated_at=current_timestamp"
                + " where organization_id=? and id=(select task_id from execution_runs where id=?)"
                + " and status='BLOCKED'",
            org,
            run)
        != 1) throw new Conflict();
    // Revalidate parent lifecycle, OPEN control, current graph/dependencies and configured owner.
    starts.requireEligible(org, run, revision + 1);
    jdbc.update(
        "insert into"
            + " unsent_run_recovery_receipts(organization_id,actor_id,idempotency_key,run_id,source_revision,resulting_revision,fingerprint)"
            + " values(?,?,?,?,?,?,?)",
        org,
        actor.id(),
        key,
        run,
        revision,
        revision + 1,
        fingerprint);
    return new Receipt(run, key, c.revision(), Long.toString(revision + 1));
  }
}

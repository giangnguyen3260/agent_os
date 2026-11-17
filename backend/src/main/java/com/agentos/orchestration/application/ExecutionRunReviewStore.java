package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Submits an already-verified output within an authorized review transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionRunReviewStore {
  private record Submission(long sourceRevision, String outputSha256) {}

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;

  ExecutionRunReviewStore(JdbcTemplate jdbc, ExecutionClaimStore claims) {
    this.jdbc = jdbc;
    this.claims = claims;
  }

  void submitReconciled(
      UUID organization, UUID run, UUID attempt, long expectedRevision, String outputSha256) {
    if (attempt == null
        || expectedRevision < 0
        || expectedRevision == Long.MAX_VALUE
        || outputSha256 == null
        || !outputSha256.matches("[0-9a-f]{64}")) throw unavailable();
    claims.lockRun(organization, run, false);
    var existing =
        jdbc.query(
            """
select s.source_run_revision,e.output_sha256
from execution_reconciled_review_submissions s
join execution_attempt_reconciliations e on e.run_id=s.run_id and e.attempt_id=s.attempt_id
where s.run_id=? and s.attempt_id=?
""",
            (rs, row) -> new Submission(rs.getLong(1), rs.getString(2)),
            run,
            attempt);
    if (!existing.isEmpty()) {
      if (existing.getFirst().equals(new Submission(expectedRevision, outputSha256))) return;
      throw unavailable();
    }

    var at = claims.now();
    if (jdbc.update(
            """
insert into execution_reconciled_review_submissions
  (run_id,attempt_id,source_run_revision,submitted_at)
select r.id,a.id,r.revision,?
from execution_runs r
join execution_attempts a on a.run_id=r.id
join execution_attempt_reconciliations e on e.run_id=r.id and e.attempt_id=a.id
join mission_execution_controls c on c.organization_id=r.organization_id
  and c.team_id=r.team_id and c.mission_id=r.mission_id
join missions m on m.organization_id=r.organization_id
  and m.team_id=r.team_id and m.id=r.mission_id
where r.organization_id=? and r.id=? and r.revision=? and r.status='BLOCKED'
  and a.id=? and a.status='SUCCEEDED' and e.outcome='SUCCEEDED' and e.output_sha256=?
  and c.state in ('OPEN','PAUSE_REQUESTED') and m.status in ('RUNNING','WAITING_APPROVAL')
  and not exists (select 1 from execution_attempts other where other.run_id=r.id
    and (other.attempt_number>a.attempt_number or other.outcome_slot=0))
  and not exists (select 1 from execution_budget_reservations b
    where b.run_id=r.id and b.status<>'SETTLED')
  and not exists (select 1 from execution_claims x where x.run_id=r.id
    and x.invalidated_at is null and x.expires_at>?)
""",
            at,
            organization,
            run,
            expectedRevision,
            attempt,
            outputSha256,
            at)
        != 1) {
      throw unavailable();
    }
    if (jdbc.update(
            """
update tasks set status='IN_REVIEW',pending_reason=null,waiting_since=null,resolver=null,updated_at=?
where organization_id=? and status='BLOCKED' and pending_reason='OUTCOME_UNKNOWN'
  and id=(select task_id from execution_runs where organization_id=? and id=?)
""",
            at,
            organization,
            organization,
            run)
        != 1) throw unavailable();
    jdbc.update("update execution_runs set status='IN_REVIEW',revision=revision+1 where id=?", run);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Reconciled output review is unavailable.");
  }
}

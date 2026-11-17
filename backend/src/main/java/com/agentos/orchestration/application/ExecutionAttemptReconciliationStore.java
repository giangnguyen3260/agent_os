package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records a verified Attempt outcome from a trusted reconciliation transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionAttemptReconciliationStore {
  enum Outcome {
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  private record Receipt(String outcome, String evidenceSha256, String outputSha256) {}

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;

  ExecutionAttemptReconciliationStore(JdbcTemplate jdbc, ExecutionClaimStore claims) {
    this.jdbc = jdbc;
    this.claims = claims;
  }

  // The caller must verify the outcome and evidence before recording a disposition.
  void recordVerified(
      UUID organization, UUID run, UUID attempt, Outcome outcome, String evidenceSha256) {
    recordVerified(organization, run, attempt, outcome, evidenceSha256, null);
  }

  void recordVerified(
      UUID organization,
      UUID run,
      UUID attempt,
      Outcome outcome,
      String evidenceSha256,
      String outputSha256) {
    if (attempt == null
        || outcome == null
        || evidenceSha256 == null
        || !evidenceSha256.matches("[0-9a-f]{64}")
        || (outputSha256 != null
            && (outcome != Outcome.SUCCEEDED || !outputSha256.matches("[0-9a-f]{64}"))))
      throw unavailable();
    claims.lockRun(organization, run, false);
    var receipt = new Receipt(outcome.name(), evidenceSha256, outputSha256);
    var existing =
        jdbc.query(
            """
            select outcome,evidence_sha256,output_sha256 from execution_attempt_reconciliations
            where run_id=? and attempt_id=?
            """,
            (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
            run,
            attempt);
    if (!existing.isEmpty()) {
      if (existing.getFirst().equals(receipt)) return;
      throw unavailable();
    }
    var at = claims.now();
    if (jdbc.update(
            """
            update execution_attempts a set status=?,outcome_slot=attempt_number
            where a.run_id=? and a.id=?
              and exists (select 1 from execution_runs r where r.id=a.run_id and (
                (r.status='BLOCKED' and a.status='UNKNOWN')
                or (r.status='CANCELLING' and a.status in ('UNKNOWN','CANCELLING'))
              ))
              and not exists (select 1 from execution_claims c
                where c.run_id=a.run_id and c.attempt_id=a.id and c.invalidated_at is null
                  and c.expires_at>?)
              and not exists (select 1 from execution_budget_reservations b
                where b.run_id=a.run_id and b.attempt_id=a.id and b.status<>'SETTLED')
            """,
            receipt.outcome(),
            run,
            attempt,
            at)
        != 1) throw unavailable();

    jdbc.update(
        """
        insert into execution_attempt_reconciliations
          (run_id,attempt_id,outcome,evidence_sha256,output_sha256,reconciled_at)
        values (?,?,?,?,?,?)
        """,
        run,
        attempt,
        receipt.outcome(),
        receipt.evidenceSha256(),
        receipt.outputSha256(),
        at);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Execution Attempt reconciliation is unavailable.");
  }
}

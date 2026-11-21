package com.agentos.orchestration.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal worker admission; callers must supply an authenticated worker identity. */
@Service
class ExecutionDispatchClaimService {
  @org.springframework.beans.factory.annotation.Value(
      "${agentos.execution.dependency-context.enabled:false}")
  private boolean dependencyContextEnabled;

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;
  private final ExecutionStartService starts;

  ExecutionDispatchClaimService(
      JdbcTemplate jdbc, ExecutionClaimStore claims, ExecutionStartService starts) {
    this.jdbc = jdbc;
    this.claims = claims;
    this.starts = starts;
  }

  @Transactional
  public ExecutionClaimStore.Token claim(
      UUID organization,
      UUID dispatch,
      UUID worker,
      OffsetDateTime expiresAt,
      OffsetDateTime deadline) {
    if (worker == null || dispatch == null) throw unavailable();
    // Match the existing organization-first lifecycle lock order before touching the queue.
    if (jdbc.queryForList(
            "select id from organizations where id=? for update", UUID.class, organization)
        .isEmpty()) throw unavailable();
    var runs =
        jdbc.queryForList(
            """
select d.run_id from execution_start_dispatch d
join human_accounts h on h.id=d.actor_id and h.enabled=true and h.credential_version=d.actor_credential_version
join organization_memberships m on m.organization_id=d.organization_id and m.account_id=d.actor_id and m.role='ADMIN'
where d.organization_id=? and d.id=? and d.state='QUEUED' for update
""",
            UUID.class,
            organization,
            dispatch);
    if (runs.size() != 1) throw unavailable();
    UUID run = runs.getFirst();
    claims.lockRun(organization, run, false);
    long revision =
        jdbc.queryForObject(
            "select source_run_revision from execution_start_dispatch where id=?",
            Long.class,
            dispatch);
    starts.requireEligible(organization, run, revision);
    new ExecutionDependencyContextStore(jdbc, dependencyContextEnabled)
        .requireFresh(organization, run, dispatch);
    if (jdbc.update(
            """
            update execution_runs set status='RUNNING',revision=revision+1
            where organization_id=? and id=? and status='CREATED'
              and revision=(select source_run_revision from execution_start_dispatch where id=?)
              and revision<9223372036854775807
            """,
            organization,
            run,
            dispatch)
        != 1) throw unavailable();
    if (jdbc.update(
            """
update tasks set status='RUNNING',updated_at=current_timestamp
where organization_id=? and id=(select task_id from execution_runs where id=?) and status='READY'
""",
            organization,
            run)
        != 1) throw unavailable();
    UUID attempt = UUID.randomUUID();
    Integer next =
        jdbc.queryForObject(
            "select coalesce(max(attempt_number),0)+1 from execution_attempts where run_id=?",
            Integer.class,
            run);
    jdbc.update(
        "insert into execution_attempts(id,run_id,attempt_number,status,outcome_slot)"
            + " values(?,?,?,'RUNNING',0)",
        attempt,
        run,
        next);
    var token = claims.issueFresh(organization, run, attempt, worker, expiresAt, deadline);
    if (jdbc.update(
            "update execution_start_dispatch set state='CLAIMED' where id=? and state='QUEUED'",
            dispatch)
        != 1) throw unavailable();
    return token;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Execution dispatch claim is unavailable.");
  }
}

package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fences an expired claim and records its runtime consequences in one transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionLeaseExpiryStore {
  private record RuntimeState(UUID task, String runStatus, String attemptStatus) {}

  private record Reservation(UUID action, String status, boolean mayHaveBeenSent) {}

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;
  private final ExecutionBudgetStore budgets;

  ExecutionLeaseExpiryStore(
      JdbcTemplate jdbc, ExecutionClaimStore claims, ExecutionBudgetStore budgets) {
    this.jdbc = jdbc;
    this.claims = claims;
    this.budgets = budgets;
  }

  boolean expire(UUID organization, UUID run, long fence) {
    var token = claims.invalidateExpired(organization, run, fence);
    if (token == null) return false;
    var runtime =
        jdbc.queryForObject(
            """
            select r.task_id,r.status,a.status from execution_runs r
            join execution_attempts a on a.run_id=r.id
            where r.id=? and a.id=?
            """,
            (rs, row) ->
                new RuntimeState(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            run,
            token.attempt());
    var reservations =
        jdbc.query(
            """
select b.action_id,b.status,m.action_id,m.handed_off_at
from execution_budget_reservations b
left join execution_model_authorizations m on m.run_id=b.run_id and m.action_id=b.action_id
where b.run_id=? and b.attempt_id=? order by b.action_id
""",
            (rs, row) ->
                new Reservation(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3) == null || rs.getObject(4) != null),
            run,
            token.attempt());
    boolean outstanding = false;
    boolean possiblySent = false;
    for (var reservation : reservations) {
      possiblySent |= reservation.mayHaveBeenSent();
      if ("SETTLED".equals(reservation.status())) continue;
      if (reservation.mayHaveBeenSent()) {
        budgets.markUnknown(organization, run, reservation.action());
        outstanding = true;
      } else {
        budgets.settleVerified(organization, run, reservation.action(), 0);
      }
    }

    boolean cancelling = "CANCELLING".equals(runtime.runStatus());
    boolean waiting =
        "WAITING".equals(runtime.runStatus())
            && ("WAITING_INPUT".equals(runtime.attemptStatus())
                || "WAITING_APPROVAL".equals(runtime.attemptStatus()));
    if (waiting && !outstanding) return true;
    if (!cancelling && !waiting && !"RUNNING".equals(runtime.runStatus())) throw inconsistent();
    boolean unresolved =
        outstanding || (possiblySent && !waiting) || "UNKNOWN".equals(runtime.attemptStatus());
    String outcome = unresolved ? "UNKNOWN" : cancelling ? "CANCELLED" : "FAILED";
    if (jdbc.update(
            """
            update execution_attempts
            set status=?,outcome_slot=case when ?='UNKNOWN' then 0 else attempt_number end
            where id=? and run_id=?
              and status in ('RUNNING','WAITING_INPUT','WAITING_APPROVAL','CANCELLING','UNKNOWN')
            """,
            outcome,
            outcome,
            token.attempt(),
            run)
        != 1) throw inconsistent();

    if (!cancelling) {
      String reason = unresolved ? "OUTCOME_UNKNOWN" : "LEASE_EXPIRED";
      if (jdbc.update(
              """
update tasks set status='BLOCKED',pending_reason=?,waiting_since=?,resolver='Runtime',
    updated_at=current_timestamp
where organization_id=? and id=? and status in ('RUNNING','WAITING')
""",
              reason,
              claims.now(),
              organization,
              runtime.task())
          != 1) throw inconsistent();
      jdbc.update(
          "update execution_runs set status='BLOCKED',wait_reason=null,revision=revision+1 where"
              + " id=?",
          run);
    }
    return true;
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException("Execution lease expiry requires a current runtime state.");
  }
}

package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inside the worker's current-token transaction, proven absence of handoff is recoverable data. */
final class ExecutionPausedDispatchStore {
  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;
  private final ExecutionBudgetStore budgets;

  ExecutionPausedDispatchStore(
      JdbcTemplate jdbc, ExecutionClaimStore claims, ExecutionBudgetStore budgets) {
    this.jdbc = jdbc;
    this.claims = claims;
    this.budgets = budgets;
  }

  boolean releaseUnsent(ExecutionClaimStore.Token token, UUID dispatch) {
    claims.requireCurrent(token);
    if (jdbc.queryForObject(
            "select count(*) from mission_execution_controls c join execution_runs r on"
                + " r.organization_id=c.organization_id and r.team_id=c.team_id and"
                + " r.mission_id=c.mission_id where r.organization_id=? and r.id=? and"
                + " c.state='PAUSE_REQUESTED'",
            Long.class,
            token.organization(),
            token.run())
        != 1) return false;
    long revision =
        jdbc.queryForObject(
            "select revision from execution_runs where id=?", Long.class, token.run());
    if (revision == Long.MAX_VALUE
        || jdbc.queryForObject(
                "select count(*) from execution_start_dispatch where id=? and organization_id=? and"
                    + " run_id=? and state='CLAIMED'",
                Long.class,
                dispatch,
                token.organization(),
                token.run())
            != 1
        || jdbc.queryForObject(
                "select count(*) from execution_model_authorizations where run_id=? and action_id=?"
                    + " and (attempt_id<>? or fence<>? or handed_off_at is not null)",
                Long.class,
                token.run(),
                dispatch,
                token.attempt(),
                token.fence())
            != 0
        || jdbc.queryForObject(
                "select count(*) from execution_model_usage where run_id=? and action_id=?",
                Long.class,
                token.run(),
                dispatch)
            != 0
        || jdbc.queryForObject(
                "select count(*) from execution_budget_reservations where run_id=? and action_id=?"
                    + " and (attempt_id<>? or fence<>? or status='UNKNOWN' or (status='SETTLED' and"
                    + " actual_microusd<>0))",
                Long.class,
                token.run(),
                dispatch,
                token.attempt(),
                token.fence())
            != 0) throw new IllegalStateException("Paused execution release unavailable");
    if (jdbc.queryForObject(
            "select count(*) from execution_budget_reservations where run_id=? and action_id=?",
            Long.class,
            token.run(),
            dispatch)
        > 0) budgets.settleVerified(token.organization(), token.run(), dispatch, 0);
    claims.invalidate(token);
    if (jdbc.update(
                "update execution_attempts set status='FAILED',outcome_slot=attempt_number where"
                    + " run_id=? and id=? and status='RUNNING'",
                token.run(),
                token.attempt())
            != 1
        || jdbc.update(
                "update execution_runs set status='CREATED',wait_reason=null,revision=revision+1"
                    + " where id=? and revision=? and status='RUNNING'",
                token.run(),
                revision)
            != 1
        || jdbc.update(
                "update tasks set"
                    + " status='READY',pending_reason=null,waiting_since=null,resolver=null,updated_at=current_timestamp"
                    + " where id=(select task_id from execution_runs where id=?) and"
                    + " status='RUNNING'",
                token.run())
            != 1
        || jdbc.update(
                "update execution_start_dispatch set state='FINISHED' where id=? and"
                    + " state='CLAIMED'",
                dispatch)
            != 1) throw new IllegalStateException("Paused execution release unavailable");
    jdbc.update(
        "insert into"
            + " execution_pause_unsent_releases(dispatch_id,run_id,attempt_id,fence,source_run_revision,result_run_revision)"
            + " values(?,?,?,?,?,?)",
        dispatch,
        token.run(),
        token.attempt(),
        token.fence(),
        revision,
        revision + 1);
    return true;
  }
}

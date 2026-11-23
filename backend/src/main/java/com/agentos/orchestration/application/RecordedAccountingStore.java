package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Internal read-only projection; callers authorize before reading in their snapshot transaction.
 */
final class RecordedAccountingStore {
  private final JdbcTemplate jdbc;

  RecordedAccountingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  ExecutionReviewService.Accounting run(UUID run) {
    return aggregate("r.id=?", run);
  }

  ExecutionReviewService.Accounting mission(UUID org, UUID team, UUID mission) {
    return aggregate("r.organization_id=? and r.team_id=? and r.mission_id=?", org, team, mission);
  }

  private ExecutionReviewService.Accounting aggregate(String scope, Object... args) {
    var costs =
        jdbc.queryForMap(
            "select coalesce(sum(case when b.status='SETTLED' then cast(actual_microusd as numeric)"
                + " else 0 end),0) as settled,coalesce(sum(case when b.status='HELD' then"
                + " cast(reserved_microusd as numeric) else 0 end),0) as held,coalesce(sum(case"
                + " when b.status='UNKNOWN' then cast(reserved_microusd as numeric) else 0 end),0)"
                + " as unknown_amount,count(case when b.status<>'SETTLED' then 1 end) as unresolved"
                + " from execution_budget_reservations b join execution_runs r on r.id=b.run_id"
                + " where "
                + scope
                + "",
            args);
    var usage =
        jdbc.queryForMap(
            "select coalesce(sum(cast(input_tokens as numeric)),0) as"
                + " input,coalesce(sum(cast(output_tokens as numeric)),0) as output from"
                + " execution_model_usage u join execution_runs r on r.id=u.run_id where "
                + scope
                + "",
            args);
    return new ExecutionReviewService.Accounting(
        integer(costs.get("settled")),
        integer(costs.get("held")),
        integer(costs.get("unknown_amount")),
        integer(costs.get("unresolved")),
        integer(usage.get("input")),
        integer(usage.get("output")));
  }

  private String integer(Object n) {
    var value = new java.math.BigDecimal(n.toString()).toBigIntegerExact();
    if (value.signum() < 0) throw new IllegalStateException("Accounting unavailable");
    return value.toString();
  }
}

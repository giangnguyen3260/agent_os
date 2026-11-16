package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Budget accounting inside an orchestration or trusted reconciliation transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionBudgetStore {
  private record Bucket(UUID id, String scope) {}

  private record Reservation(UUID attempt, long fence, long reserved, String status, Long actual) {}

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;

  ExecutionBudgetStore(JdbcTemplate jdbc, ExecutionClaimStore claims) {
    this.jdbc = jdbc;
    this.claims = claims;
  }

  // Returns false for a matching replay so the caller can distinguish it from a new hold.
  boolean reserve(ExecutionClaimStore.Token token, UUID action, long amount) {
    if (action == null || amount <= 0) throw unavailable();
    claims.requireCurrent(token);
    var previous = find(token.run(), action);
    if (previous != null) {
      if ("HELD".equals(previous.status())
          && previous.attempt().equals(token.attempt())
          && previous.fence() == token.fence()
          && previous.reserved() == amount) return false;
      throw unavailable();
    }
    var at = claims.now();
    var buckets =
        jdbc.query(
            """
            select b.id, b.scope
            from execution_budget_buckets b
            join execution_runs r on b.organization_id=r.organization_id
            join accepted_mission_plan_tasks p on p.organization_id=r.organization_id
              and p.team_id=r.team_id and p.mission_id=r.mission_id
              and p.plan_revision=r.plan_revision and p.task_id=r.task_id
            where r.id=? and b.opens_at<=? and b.closes_at>?
              and b.subject_id=case b.scope
                when 'ORGANIZATION' then r.organization_id
                when 'TEAM' then r.team_id
                when 'AGENT' then p.owner_agent_id
                when 'MISSION' then r.mission_id end
            order by b.id
            """,
            (rs, i) -> new Bucket(rs.getObject(1, UUID.class), rs.getString(2)),
            token.run(),
            at,
            at);
    if (buckets.size() != 4 || buckets.stream().map(Bucket::scope).distinct().count() != 4)
      throw unavailable();

    jdbc.update(
        """
        insert into execution_budget_reservations
          (run_id,action_id,attempt_id,fence,reserved_microusd,status)
        values (?,?,?,?,?,'HELD')
        """,
        token.run(),
        action,
        token.attempt(),
        token.fence(),
        amount);
    for (var bucket : buckets) {
      if (jdbc.update(
              """
              update execution_budget_buckets set held_microusd=held_microusd+?
              where id=? and ?<=cast(limit_microusd as numeric)-spent_microusd-held_microusd
              """,
              amount,
              bucket.id(),
              amount)
          != 1) throw unavailable();
      jdbc.update(
          "insert into execution_budget_reservation_buckets (run_id,action_id,bucket_id) values"
              + " (?,?,?)",
          token.run(),
          action,
          bucket.id());
    }
    return true;
  }

  void markUnknown(UUID organization, UUID run, UUID action) {
    claims.lockRun(organization, run, false);
    var reservation = requireReservation(run, action);
    if ("SETTLED".equals(reservation.status())) throw unavailable();
    jdbc.update(
        "update execution_budget_reservations set status='UNKNOWN' where run_id=? and action_id=?",
        run,
        action);
  }

  // Only a trusted reconciliation command can attest the final actual amount.
  void settleVerified(UUID organization, UUID run, UUID action, long actual) {
    if (actual < 0) throw unavailable();
    claims.lockRun(organization, run, false);
    var reservation = requireReservation(run, action);
    if ("SETTLED".equals(reservation.status())) {
      if (reservation.actual() == actual) return;
      throw unavailable();
    }
    var buckets =
        jdbc.queryForList(
            """
            select bucket_id from execution_budget_reservation_buckets
            where run_id=? and action_id=? order by bucket_id
            """,
            UUID.class,
            run,
            action);
    if (buckets.size() != 4) throw unavailable();
    for (var bucket : buckets) {
      if (jdbc.update(
              """
              update execution_budget_buckets
              set held_microusd=held_microusd-?, spent_microusd=spent_microusd+?
              where id=? and organization_id=? and held_microusd>=?
              """,
              reservation.reserved(),
              actual,
              bucket,
              organization,
              reservation.reserved())
          != 1) {
        throw unavailable();
      }
    }
    jdbc.update(
        """
        update execution_budget_reservations set status='SETTLED',actual_microusd=?
        where run_id=? and action_id=?
        """,
        actual,
        run,
        action);
  }

  private Reservation requireReservation(UUID run, UUID action) {
    var reservation = find(run, action);
    if (reservation == null) throw unavailable();
    return reservation;
  }

  private Reservation find(UUID run, UUID action) {
    var rows =
        jdbc.query(
            """
            select attempt_id,fence,reserved_microusd,status,actual_microusd
            from execution_budget_reservations where run_id=? and action_id=? for update
            """,
            (rs, i) ->
                new Reservation(
                    rs.getObject(1, UUID.class),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getObject(5, Long.class)),
            run,
            action);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Execution budget is unavailable.");
  }
}

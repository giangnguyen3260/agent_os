package com.agentos.orchestration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Budget accounting inside an orchestration or trusted reconciliation transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionBudgetStore {
  private record Bucket(
      UUID id, String scope, int maxConcurrent, int maxAdmissions, int windowSeconds) {}

  private record Usage(long concurrent, long recent) {}

  private record ModelUsage(
      UUID connection, String requestSha256, long inputTokens, long outputTokens) {}

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
            select b.id, b.scope, l.max_concurrent, l.max_admissions, l.window_seconds
            from execution_budget_buckets b
            join execution_runs r on b.organization_id=r.organization_id
            join accepted_mission_plan_tasks p on p.organization_id=r.organization_id
              and p.team_id=r.team_id and p.mission_id=r.mission_id
              and p.plan_revision=r.plan_revision and p.task_id=r.task_id
            left join execution_capacity_limits l on l.organization_id=b.organization_id
              and l.scope=b.scope and l.subject_id=b.subject_id
            where r.id=? and b.opens_at<=? and b.closes_at>?
              and b.subject_id=case b.scope
                when 'ORGANIZATION' then r.organization_id
                when 'TEAM' then r.team_id
                when 'AGENT' then p.owner_agent_id
                when 'MISSION' then r.mission_id end
            order by b.id
            """,
            (rs, i) ->
                new Bucket(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getInt(4),
                    rs.getInt(5)),
            token.run(),
            at,
            at);
    if (buckets.size() != 4 || buckets.stream().map(Bucket::scope).distinct().count() != 4)
      throw unavailable();

    for (var bucket : buckets) requireCapacity(bucket, at);

    jdbc.update(
        """
        insert into execution_budget_reservations
          (run_id,action_id,attempt_id,fence,reserved_microusd,status,admitted_at)
        values (?,?,?,?,?,'HELD',?)
        """,
        token.run(),
        action,
        token.attempt(),
        token.fence(),
        amount,
        at);
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

  private void requireCapacity(Bucket bucket, OffsetDateTime at) {
    if (bucket.windowSeconds() == 0) throw capacityUnavailable();
    var cutoff = at.minusSeconds(bucket.windowSeconds());
    // Count reservations across budget windows for this scope while holding the organization lock.
    var usage =
        jdbc.queryForObject(
            """
select count(case when r.status in ('HELD','UNKNOWN') then 1 end),
       count(case when r.admitted_at>? then 1 end)
from execution_budget_buckets current_bucket
join execution_budget_buckets history on history.organization_id=current_bucket.organization_id
  and history.scope=current_bucket.scope and history.subject_id=current_bucket.subject_id
join execution_budget_reservation_buckets link on link.bucket_id=history.id
join execution_budget_reservations r on r.run_id=link.run_id and r.action_id=link.action_id
where current_bucket.id=? and (r.admitted_at>? or r.status in ('HELD','UNKNOWN'))
""",
            (rs, i) -> new Usage(rs.getLong(1), rs.getLong(2)),
            cutoff,
            bucket.id(),
            cutoff);
    if (usage.concurrent() >= bucket.maxConcurrent() || usage.recent() >= bucket.maxAdmissions()) {
      throw capacityUnavailable();
    }
  }

  private static IllegalStateException capacityUnavailable() {
    return new IllegalStateException("Execution capacity is unavailable.");
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

  // Called after trusted reconciliation verifies the provider response against the action.
  void settleModelUsage(
      UUID organization,
      UUID run,
      UUID action,
      UUID connection,
      String providerRequestId,
      long inputTokens,
      long outputTokens,
      long actual) {
    if (connection == null
        || providerRequestId == null
        || providerRequestId.isBlank()
        || providerRequestId.length() > 512
        || inputTokens < 0
        || outputTokens < 0
        || actual < 0) {
      throw unavailable();
    }
    claims.lockRun(organization, run, false);
    var reservation = requireReservation(run, action);
    var authorizedConnections =
        jdbc.queryForList(
            "select connection_id from execution_model_authorizations where run_id=? and"
                + " action_id=?",
            UUID.class,
            run,
            action);
    if (!authorizedConnections.isEmpty() && !connection.equals(authorizedConnections.getFirst())) {
      throw unavailable();
    }
    var usage =
        new ModelUsage(connection, hashRequestId(providerRequestId), inputTokens, outputTokens);
    if ("SETTLED".equals(reservation.status())) {
      var existing =
          jdbc.query(
              """
              select connection_id,provider_request_sha256,input_tokens,output_tokens
              from execution_model_usage where run_id=? and action_id=?
              """,
              (rs, row) ->
                  new ModelUsage(
                      rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4)),
              run,
              action);
      if (reservation.actual() == actual && existing.equals(List.of(usage))) return;
      throw unavailable();
    }

    // The database rejects a foreign connection or reuse of the same provider receipt.
    jdbc.update(
        """
        insert into execution_model_usage
          (organization_id,run_id,action_id,connection_id,provider_request_sha256,
           input_tokens,output_tokens,recorded_at)
        values (?,?,?,?,?,?,?,?)
        """,
        organization,
        run,
        action,
        usage.connection(),
        usage.requestSha256(),
        usage.inputTokens(),
        usage.outputTokens(),
        claims.now());
    settle(organization, run, action, reservation, actual);
  }

  private static String hashRequestId(String requestId) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(requestId.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
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
    settle(organization, run, action, reservation, actual);
  }

  private void settle(
      UUID organization, UUID run, UUID action, Reservation reservation, long actual) {
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

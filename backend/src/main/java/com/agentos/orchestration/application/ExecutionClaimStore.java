package com.agentos.orchestration.application;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claim persistence used inside an authorized orchestration transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionClaimStore {
  record Token(UUID organization, UUID run, UUID attempt, UUID worker, long fence) {}

  private record Claim(
      Token token, OffsetDateTime expiresAt, OffsetDateTime deadline, boolean invalidated) {}

  private final JdbcTemplate jdbc;
  private final MissionExecutionControlStore controls;
  private final String wallClockQuery;

  ExecutionClaimStore(JdbcTemplate jdbc, MissionExecutionControlStore controls) {
    this.jdbc = jdbc;
    this.controls = controls;
    this.wallClockQuery =
        jdbc.execute(
            (ConnectionCallback<String>)
                connection ->
                    "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                        ? "select clock_timestamp()"
                        : "select current_timestamp");
  }

  Token issueFresh(
      UUID organization,
      UUID run,
      UUID attempt,
      UUID worker,
      OffsetDateTime expiresAt,
      OffsetDateTime deadline) {
    lockRun(organization, run, true);
    controls.requireOpen(organization, run);
    requireRunningAttempt(run, attempt);
    var previous = latest(organization, run);
    if (previous != null
        && (!previous.invalidated()
            || previous.token().fence() == Long.MAX_VALUE
            || !jdbc.queryForList(
                    "select fence from execution_claims where run_id=? and attempt_id=?",
                    Long.class,
                    run,
                    attempt)
                .isEmpty())) {
      throw denied();
    }
    if (worker == null || expiresAt == null || deadline == null) throw denied();
    var at = now();
    expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    deadline = deadline.truncatedTo(ChronoUnit.MICROS);
    if (!expiresAt.isAfter(at) || expiresAt.isAfter(deadline)) {
      throw denied();
    }
    long fence = previous == null ? 1 : previous.token().fence() + 1;
    jdbc.update(
        """
        insert into execution_claims
          (run_id,attempt_id,fence,worker_id,issued_at,expires_at,deadline_at)
        values (?,?,?,?,?,?,?)
        """,
        run,
        attempt,
        fence,
        worker,
        at,
        expiresAt,
        deadline);
    return new Token(organization, run, attempt, worker, fence);
  }

  void requireCurrent(Token token) {
    requireCurrentLease(token);
  }

  void renew(Token token, OffsetDateTime expiresAt) {
    if (expiresAt == null) throw denied();
    var claim = requireCurrentLease(token);
    expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    if (!expiresAt.isAfter(claim.expiresAt()) || expiresAt.isAfter(claim.deadline())) {
      throw denied();
    }
    jdbc.update(
        "update execution_claims set expires_at=? where run_id=? and fence=?",
        expiresAt,
        token.run(),
        token.fence());
  }

  // The lifecycle command owns cancellation, expiry and outcome reconciliation.
  void invalidate(Token token) {
    lockRun(token.organization(), token.run(), false);
    var claim = latest(token.organization(), token.run());
    if (claim == null || !claim.token().equals(token)) throw denied();
    if (!claim.invalidated()) {
      jdbc.update(
          "update execution_claims set invalidated_at=? where run_id=? and fence=?",
          now(),
          token.run(),
          token.fence());
    }
  }

  private Claim requireCurrentLease(Token token) {
    lockRun(token.organization(), token.run(), true);
    requireRunningAttempt(token.run(), token.attempt());
    var claim = latest(token.organization(), token.run());
    if (claim == null
        || !claim.token().equals(token)
        || claim.invalidated()
        || !claim.expiresAt().isAfter(now())) {
      throw denied();
    }
    return claim;
  }

  void lockRun(UUID organization, UUID run, boolean requireRunning) {
    var organizations =
        jdbc.queryForList(
            "select id from organizations where id=? for update", UUID.class, organization);
    if (organizations.isEmpty()) throw denied();
    var statuses =
        jdbc.queryForList(
            "select status from execution_runs where organization_id=? and id=? for update",
            String.class,
            organization,
            run);
    if (statuses.isEmpty() || (requireRunning && !"RUNNING".equals(statuses.getFirst()))) {
      throw denied();
    }
  }

  private void requireRunningAttempt(UUID run, UUID attempt) {
    var statuses =
        jdbc.queryForList(
            "select status from execution_attempts where run_id=? and id=? for update",
            String.class,
            run,
            attempt);
    if (statuses.isEmpty() || !"RUNNING".equals(statuses.getFirst())) throw denied();
  }

  private Claim latest(UUID organization, UUID run) {
    var claims =
        jdbc.query(
            """
            select attempt_id,worker_id,fence,expires_at,deadline_at,invalidated_at
            from execution_claims where run_id=? order by fence desc limit 1 for update
            """,
            (rs, row) ->
                new Claim(
                    new Token(
                        organization,
                        run,
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getLong(3)),
                    rs.getObject(4, OffsetDateTime.class),
                    rs.getObject(5, OffsetDateTime.class),
                    rs.getObject(6, OffsetDateTime.class) != null),
            run);
    return claims.isEmpty() ? null : claims.getFirst();
  }

  OffsetDateTime now() {
    return jdbc.queryForObject(wallClockQuery, OffsetDateTime.class);
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Execution claim is unavailable.");
  }
}

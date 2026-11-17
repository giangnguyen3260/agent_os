package com.agentos.orchestration.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    name = "agentos.execution.lease-expiry.enabled",
    havingValue = "true",
    matchIfMissing = true)
class ExecutionLeaseExpiryScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutionLeaseExpiryScheduler.class);
  private static final int BATCH_SIZE = 16;
  private static final String CANDIDATES =
      """
      select r.organization_id,c.run_id,c.fence,c.expires_at
      from execution_claims c join execution_runs r on r.id=c.run_id
      where c.invalidated_at is null and c.expires_at<=current_timestamp
        and not exists (select 1 from execution_claims newer where newer.run_id=c.run_id
          and newer.fence>c.fence)
      """;

  private record Candidate(UUID organization, UUID run, long fence, OffsetDateTime expiresAt) {}

  private final JdbcTemplate jdbc;
  private final ExecutionLeaseExpiryStore expiry;
  private final TransactionTemplate transaction;
  private Candidate cursor;

  ExecutionLeaseExpiryScheduler(
      JdbcTemplate jdbc, ExecutionLeaseExpiryStore expiry, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.expiry = expiry;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(2);
  }

  @Scheduled(fixedDelayString = "${agentos.execution.lease-expiry.delay-ms:5000}")
  synchronized void sweep() {
    var batch = candidatesAfter(cursor);
    if (batch.isEmpty()) {
      cursor = null;
      return;
    }
    for (var candidate : batch) {
      try {
        transaction.executeWithoutResult(
            status -> expiry.expire(candidate.organization(), candidate.run(), candidate.fence()));
      } catch (RuntimeException failure) {
        LOG.warn(
            "Execution lease expiry failed for run {} at fence {} ({}); will retry on a later scan",
            candidate.run(),
            candidate.fence(),
            failure.getClass().getSimpleName());
      }
    }
    cursor = batch.size() == BATCH_SIZE ? batch.getLast() : null;
  }

  private List<Candidate> candidatesAfter(Candidate after) {
    String tail = " order by c.expires_at,c.run_id,c.fence limit ?";
    String query =
        after == null
            ? CANDIDATES + tail
            : CANDIDATES + " and (c.expires_at,c.run_id,c.fence)>(?,?,?)" + tail;
    Object[] parameters =
        after == null
            ? new Object[] {BATCH_SIZE}
            : new Object[] {after.expiresAt(), after.run(), after.fence(), BATCH_SIZE};
    return jdbc.query(
        query,
        (rs, row) ->
            new Candidate(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                rs.getObject(4, OffsetDateTime.class)),
        parameters);
  }
}

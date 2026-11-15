package com.agentos.providerchecks;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs one bounded polling cycle on a dedicated worker thread. */
public final class ProviderAccessCheckPoller {
  private static final int EXPIRY_BATCH = 32;
  private final JdbcTemplate jdbc;
  private final ProviderAccessCheckDispatch dispatch;
  private final ProviderAccessCheckRunner runner;
  private final UUID worker = UUID.randomUUID();

  public ProviderAccessCheckPoller(
      DataSource source, ProviderAccessCheckDispatch dispatch, ProviderAccessCheckRunner runner) {
    this.jdbc = new JdbcTemplate(source);
    this.dispatch = Objects.requireNonNull(dispatch);
    this.runner = Objects.requireNonNull(runner);
  }

  public void poll() {
    for (var candidate :
        candidates(
            """
            where (c.worker_owner_id is null and c.queue_expires_at <= current_timestamp)
               or c.dispatch_deadline <= current_timestamp
            order by coalesce(c.dispatch_deadline, c.queue_expires_at), c.id limit ?
            """,
            EXPIRY_BATCH)) {
      dispatch.expire(candidate.organization(), candidate.check());
    }

    var ready =
        candidates(
            """
            where c.worker_owner_id is null and c.queue_expires_at > current_timestamp
            order by c.accepted_at, c.id limit ?
            """,
            1);
    if (!ready.isEmpty()) {
      var candidate = ready.getFirst();
      runner.run(candidate.organization(), candidate.check(), worker);
    }
  }

  private List<Candidate> candidates(String filter, int limit) {
    return jdbc.query(
        """
        select o.organization_id, o.check_id
        from provider_access_check_outbox o join provider_access_checks c on c.id=o.check_id
        """
            + filter,
        (rs, row) ->
            new Candidate(
                rs.getObject("organization_id", UUID.class), rs.getObject("check_id", UUID.class)),
        limit);
  }

  private record Candidate(UUID organization, UUID check) {}
}

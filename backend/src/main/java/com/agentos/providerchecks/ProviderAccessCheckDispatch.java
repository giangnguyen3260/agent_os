package com.agentos.providerchecks;

import com.agentos.providerchecks.ProviderModelsTransport.Model;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal check dispatch transitions. Each call commits before returning. */
public final class ProviderAccessCheckDispatch {
  public record Claim(UUID organization, UUID check, UUID owner, UUID token) {}

  /** Only the winning authorization receives a deadline for the request. */
  public record Permission(OffsetDateTime requestDeadline) {}

  record Catalogue(List<Model> models, boolean truncated) {
    Catalogue {
      models = List.copyOf(models);
      if (models.size() > 1000) throw new IllegalArgumentException("Invalid catalogue size");
    }
  }

  public enum Result {
    ACCESSIBLE("ACCESSIBLE", null),
    UNAUTHENTICATED("DENIED", "PROVIDER_UNAUTHENTICATED"),
    FORBIDDEN("DENIED", "PROVIDER_FORBIDDEN"),
    RATE_LIMITED("FAILED", "PROVIDER_RATE_LIMITED"),
    PROVIDER_UNAVAILABLE("FAILED", "PROVIDER_UNAVAILABLE"),
    NETWORK_UNAVAILABLE("FAILED", "NETWORK_UNAVAILABLE"),
    PROTOCOL_ERROR("FAILED", "PROVIDER_PROTOCOL_ERROR"),
    RESPONSE_LIMIT("FAILED", "RESPONSE_LIMIT"),
    CREDENTIAL_UNAVAILABLE("FAILED", "CREDENTIAL_UNAVAILABLE"),
    UNKNOWN("UNKNOWN", "TRANSPORT_UNKNOWN"),
    CHECK_EXPIRED("FAILED", "CHECK_EXPIRED"),
    AUTHORITY_LOST("FAILED", "AUTHORITY_LOST"),
    SUPERSEDED_AUTHORITY("SUPERSEDED", "AUTHORITY_LOST"),
    REVISION_CHANGED("SUPERSEDED", "REVISION_CHANGED");

    private final String outcome;
    private final String code;

    Result(String outcome, String code) {
      this.outcome = outcome;
      this.code = code;
    }
  }

  private record Check(
      UUID connection,
      UUID actor,
      long actorVersion,
      long revision,
      long credentialRevision,
      long policyRevision,
      OffsetDateTime expires,
      UUID owner,
      UUID token,
      OffsetDateTime started,
      OffsetDateTime deadline,
      OffsetDateTime authorized) {
    boolean ownedBy(Claim claim) {
      return claim.owner() != null
          && claim.token() != null
          && claim.owner().equals(owner)
          && claim.token().equals(token);
    }
  }

  record Target(
      UUID connection, UUID actor, long actorVersion, long revision, long credentialRevision) {}

  private record Locked(Check check, OffsetDateTime now, Result invalidation) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String clock;
  private final boolean discovery;

  public ProviderAccessCheckDispatch(DataSource source) {
    this(source, false);
  }

  public static ProviderAccessCheckDispatch forDiscovery(DataSource source) {
    return new ProviderAccessCheckDispatch(source, true);
  }

  private ProviderAccessCheckDispatch(DataSource source, boolean discovery) {
    this.discovery = discovery;
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    String product =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
    clock =
        switch (product) {
          case "PostgreSQL" -> "clock_timestamp()";
          case "H2" -> "current_timestamp";
          default -> throw new IllegalStateException("CHECK_DISPATCH_UNAVAILABLE");
        };
  }

  String checkKind() {
    return discovery ? "MODEL_DISCOVERY" : "ACCESS_CHECK";
  }

  public Claim claim(UUID organization, UUID check, UUID worker) {
    if (worker == null) throw new IllegalArgumentException("Worker required");
    return transact(
        () -> {
          var locked = lock(organization, check);
          if (locked == null || locked.check().owner() != null) return null;
          var rejection = rejection(locked);
          if (rejection != null) {
            complete(organization, check, locked.now(), rejection);
            return null;
          }
          var token = UUID.randomUUID();
          requireOne(
              jdbc.update(
                  """
update provider_access_checks
set worker_owner_id=?, dispatch_token=?, started_at=?, dispatch_deadline=?
where organization_id=? and id=? and worker_owner_id is null and completed_at is null
""",
                  worker,
                  token,
                  locked.now(),
                  locked.now().plusSeconds(30),
                  organization,
                  check));
          return new Claim(organization, check, worker, token);
        });
  }

  Target target(Claim claim) {
    return transact(
        () -> {
          var locked = lock(claim.organization(), claim.check());
          if (locked == null
              || !locked.check().ownedBy(claim)
              || locked.check().authorized() != null) return null;
          var rejection = rejection(locked);
          if (rejection != null) {
            complete(claim.organization(), claim.check(), locked.now(), rejection);
            return null;
          }
          var check = locked.check();
          return new Target(
              check.connection(),
              check.actor(),
              check.actorVersion(),
              check.revision(),
              check.credentialRevision());
        });
  }

  public Permission authorize(Claim claim) {
    if (discovery) throw new IllegalStateException("Page ordinal required");
    return authorize(claim, 0);
  }

  Permission authorizePage(Claim claim, int ordinal) {
    if (!discovery || ordinal < 1 || ordinal > 10)
      throw new IllegalArgumentException("Invalid page authorization");
    return authorize(claim, ordinal);
  }

  private Permission authorize(Claim claim, int ordinal) {
    return transact(
        () -> {
          var locked = lock(claim.organization(), claim.check());
          if (locked == null
              || !locked.check().ownedBy(claim)
              || (!discovery && locked.check().authorized() != null)) return null;
          var rejection = rejection(locked);
          if (rejection != null) {
            complete(claim.organization(), claim.check(), locked.now(), rejection);
            return null;
          }
          var authorizedAt = locked.now();
          if (discovery) {
            if (ordinal != authorizedPages(claim) + 1) return null;
            authorizedAt = now();
            if (!authorizedAt.isBefore(locked.check().deadline())) {
              complete(claim.organization(), claim.check(), authorizedAt, Result.UNKNOWN);
              return null;
            }
          }
          var deadline = authorizedAt.plusSeconds(10);
          if (locked.check().deadline().isBefore(deadline)) deadline = locked.check().deadline();
          if (discovery) {
            requireOne(
                jdbc.update(
                    """
                    insert into provider_model_discovery_pages
                      (organization_id,connection_id,check_id,page_ordinal,worker_owner_id,
                       dispatch_token,started_at,dispatch_deadline,authorized_at,request_deadline)
                    values (?,?,?,?,?,?,?,?,?,?)
                    """,
                    claim.organization(),
                    locked.check().connection(),
                    claim.check(),
                    ordinal,
                    claim.owner(),
                    claim.token(),
                    locked.check().started(),
                    locked.check().deadline(),
                    authorizedAt,
                    deadline));
          }
          if (!discovery || ordinal == 1) {
            requireOne(
                jdbc.update(
                    """
                    update provider_access_checks set authorized_at=?
                    where organization_id=? and id=? and dispatch_token=? and authorized_at is null
                      and completed_at is null
                    """,
                    authorizedAt,
                    claim.organization(),
                    claim.check(),
                    claim.token()));
          }
          return new Permission(deadline);
        });
  }

  public boolean finish(Claim claim, Result result) {
    return finish(claim, result, null);
  }

  boolean finish(Claim claim, Result result, Catalogue catalogue) {
    if (result == null
        || (catalogue != null) != (discovery && result == Result.ACCESSIBLE)
        || switch (result) {
          case CHECK_EXPIRED, AUTHORITY_LOST, SUPERSEDED_AUTHORITY, REVISION_CHANGED -> true;
          default -> false;
        }) throw new IllegalArgumentException("Invalid worker result");
    return transact(
        () -> {
          var locked = lock(claim.organization(), claim.check());
          if (locked == null || !locked.check().ownedBy(claim)) return false;
          var rejection = rejection(locked);
          if (rejection == null
              && locked.check().authorized() == null
              && result != Result.CREDENTIAL_UNAVAILABLE) return false;
          if (rejection == null && catalogue != null && authorizedPages(claim) == 0) return false;
          var disposition = rejection == null ? result : rejection;
          if (discovery && disposition != Result.UNKNOWN) {
            if (!complete(claim.organization(), claim.check(), now(), disposition, true)) {
              complete(claim.organization(), claim.check(), now(), Result.UNKNOWN);
              return true;
            }
          } else {
            complete(claim.organization(), claim.check(), locked.now(), disposition);
          }
          if (rejection == null && catalogue != null)
            publish(claim, locked.check().connection(), catalogue);
          return true;
        });
  }

  private int authorizedPages(Claim claim) {
    return jdbc.queryForObject(
        """
        select count(*) from provider_model_discovery_pages
        where organization_id=? and check_id=? and worker_owner_id=? and dispatch_token=?
        """,
        Integer.class,
        claim.organization(),
        claim.check(),
        claim.owner(),
        claim.token());
  }

  private void publish(Claim claim, UUID connection, Catalogue catalogue) {
    requireOne(
        jdbc.update(
            """
            insert into provider_model_discovery_snapshots
              (organization_id,connection_id,check_id,catalogue_state) values (?,?,?,?)
            """,
            claim.organization(),
            connection,
            claim.check(),
            catalogue.truncated() ? "TRUNCATED" : "COMPLETE"));
    if (catalogue.models().isEmpty()) return;
    jdbc.batchUpdate(
        """
        insert into provider_model_discovery_entries
          (organization_id,connection_id,check_id,model_ordinal,model_id,display_name)
        values (?,?,?,?,?,?)
        """,
        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
          @Override
          public void setValues(java.sql.PreparedStatement statement, int index)
              throws java.sql.SQLException {
            var model = catalogue.models().get(index);
            statement.setObject(1, claim.organization());
            statement.setObject(2, connection);
            statement.setObject(3, claim.check());
            statement.setInt(4, index + 1);
            statement.setString(5, model.id());
            statement.setString(6, model.displayName());
          }

          @Override
          public int getBatchSize() {
            return catalogue.models().size();
          }
        });
  }

  public boolean expire(UUID organization, UUID check) {
    return transact(
        () -> {
          var locked = lock(organization, check);
          if (locked == null) return false;
          var result = deadlineRejection(locked);
          if (result == null) return false;
          complete(organization, check, locked.now(), result);
          return true;
        });
  }

  private Locked lock(UUID org, UUID id) {
    if (jdbc.queryForList("select id from organizations where id=? for update", org).isEmpty())
      return null;
    var policy =
        jdbc.queryForList("select * from provider_deployment_policy where id=1 for update");
    var checks =
        jdbc.query(
            """
select c.* from provider_access_checks c
join provider_access_check_outbox o on o.check_id=c.id and o.organization_id=c.organization_id
where c.organization_id=? and c.id=? and c.check_kind=? and c.completed_at is null
""",
            (rs, n) ->
                new Check(
                    rs.getObject("connection_id", UUID.class),
                    rs.getObject("actor_id", UUID.class),
                    rs.getLong("actor_credential_version"),
                    rs.getLong("connection_revision"),
                    rs.getLong("credential_revision"),
                    rs.getLong("policy_revision"),
                    rs.getObject("queue_expires_at", OffsetDateTime.class),
                    rs.getObject("worker_owner_id", UUID.class),
                    rs.getObject("dispatch_token", UUID.class),
                    rs.getObject("started_at", OffsetDateTime.class),
                    rs.getObject("dispatch_deadline", OffsetDateTime.class),
                    rs.getObject("authorized_at", OffsetDateTime.class)),
            org,
            id,
            checkKind());
    if (checks.isEmpty()) return null;
    var check = checks.getFirst();
    var accounts =
        jdbc.queryForList(
            "select enabled,credential_version from human_accounts where id=? for update",
            check.actor());
    var membership =
        jdbc.queryForList(
            "select role from organization_memberships where organization_id=? and account_id=? for"
                + " update",
            org,
            check.actor());
    var connections =
        jdbc.queryForList(
            """
select provider,state,configuration_revision,credential_revision from provider_connections
where organization_id=? and id=? for update
""",
            org,
            check.connection());
    OffsetDateTime now = now();
    Result invalidation = null;
    if (accounts.size() != 1
        || membership.size() != 1
        || !Boolean.TRUE.equals(accounts.getFirst().get("enabled"))
        || ((Number) accounts.getFirst().get("credential_version")).longValue()
            != check.actorVersion()
        || !"ADMIN".equals(membership.getFirst().get("role"))) {
      invalidation =
          check.authorized() == null ? Result.AUTHORITY_LOST : Result.SUPERSEDED_AUTHORITY;
    } else if (policy.size() != 1
        || connections.size() != 1
        || !matches(check, policy.getFirst(), connections.getFirst())) {
      invalidation = Result.REVISION_CHANGED;
    }
    return new Locked(check, now, invalidation);
  }

  private static boolean matches(
      Check check, Map<String, Object> policy, Map<String, Object> connection) {
    String flag =
        switch ((String) connection.get("provider")) {
          case "OPENAI" -> "openai_enabled";
          case "ANTHROPIC" -> "anthropic_enabled";
          case "GOOGLE_GEMINI" -> "gemini_enabled";
          default -> throw new IllegalStateException("CHECK_DISPATCH_UNAVAILABLE");
        };
    return Boolean.TRUE.equals(policy.get(flag))
        && ((Number) policy.get("revision")).longValue() == check.policyRevision()
        && "ENABLED".equals(connection.get("state"))
        && ((Number) connection.get("configuration_revision")).longValue() == check.revision()
        && ((Number) connection.get("credential_revision")).longValue()
            == check.credentialRevision();
  }

  private static Result rejection(Locked locked) {
    var deadline = deadlineRejection(locked);
    return deadline == null ? locked.invalidation() : deadline;
  }

  private static Result deadlineRejection(Locked locked) {
    var check = locked.check();
    if (check.deadline() != null)
      return locked.now().isBefore(check.deadline()) ? null : Result.UNKNOWN;
    return locked.now().isBefore(check.expires()) ? null : Result.CHECK_EXPIRED;
  }

  private void complete(UUID org, UUID check, OffsetDateTime now, Result result) {
    complete(org, check, now, result, false);
  }

  private boolean complete(
      UUID org, UUID check, OffsetDateTime now, Result result, boolean beforeDeadline) {
    int updated =
        jdbc.update(
            """
            update provider_access_checks set completed_at=?,outcome=?,error_code=?
            where organization_id=? and id=? and completed_at is null
            """
                + (beforeDeadline ? " and dispatch_deadline > " + clock : ""),
            now,
            result.outcome,
            result.code,
            org,
            check);
    if (beforeDeadline && updated == 0) return false;
    requireOne(updated);
    requireOne(
        jdbc.update(
            "delete from provider_access_check_outbox where organization_id=? and check_id=?",
            org,
            check));
    return true;
  }

  private OffsetDateTime now() {
    return jdbc.queryForObject("select " + clock, OffsetDateTime.class);
  }

  private <T> T transact(Supplier<T> action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
      throw new IllegalStateException("CHECK_DISPATCH_UNAVAILABLE");
    return transaction.execute(status -> action.get());
  }

  private static void requireOne(int rows) {
    if (rows != 1) throw new IllegalStateException("CHECK_DISPATCH_UNAVAILABLE");
  }
}

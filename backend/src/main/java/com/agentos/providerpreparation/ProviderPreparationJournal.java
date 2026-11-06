package com.agentos.providerpreparation;

import com.agentos.providercredentials.CredentialBoundary.CreateAttempt;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal durable evidence; construct explicitly with the trusted writer datasource. No deletion
 * or automatic orphan classification is provided.
 */
public final class ProviderPreparationJournal {
  @JsonIgnoreType
  public record Preparation(UUID id, Scope scope, String locator) {
    public Preparation {
      if (id == null
          || scope == null
          || locator == null
          || !locator.matches(
              "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
        throw unavailable();
    }

    @Override
    public String toString() {
      return "Preparation[redacted]";
    }
  }

  public enum Observation {
    RETAINED_REFERENCE_FOUND,
    UNRESOLVED
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate snapshot;
  private final TransactionTemplate transaction;

  public ProviderPreparationJournal(DataSource datasource) {
    if (datasource == null) throw unavailable();
    this.jdbc = new JdbcTemplate(datasource);
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
  }

  /**
   * Must return successfully before any Vault write; a failed/unknown commit forbids that write.
   */
  public Preparation begin(Scope scope, String locator) {
    outside();
    var preparation = new Preparation(UUID.randomUUID(), scope, locator);
    try {
      transaction.executeWithoutResult(
          status ->
              jdbc.update(
                  "insert into provider_secret_preparations"
                      + "(id,organization_id,connection_id,provider,credential_revision,external_locator,state)"
                      + " values (?,?,?,?,?,?,'PENDING')",
                  preparation.id(),
                  scope.organization(),
                  scope.connection(),
                  scope.provider().name(),
                  scope.credentialRevision(),
                  locator));
      return preparation;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  /** Commits the Create receipt namespace along with the preparation before any Vault write. */
  public Preparation beginCreate(Scope scope, String locator, CreateAttempt attempt) {
    outside();
    if (attempt == null || scope == null || scope.credentialRevision() != 1) throw unavailable();
    var preparation = new Preparation(UUID.randomUUID(), scope, locator);
    try {
      transaction.executeWithoutResult(
          status ->
              jdbc.update(
                  """
                  insert into provider_secret_preparations
                    (id,organization_id,connection_id,provider,credential_revision,external_locator,
                     state,create_actor_id,create_idempotency_key)
                  values (?,?,?,?,?,?,'PENDING',?,?)
                  """,
                  preparation.id(),
                  scope.organization(),
                  scope.connection(),
                  scope.provider().name(),
                  scope.credentialRevision(),
                  locator,
                  attempt.actor(),
                  attempt.idempotencyKey()));
      return preparation;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  /** Acknowledgement is scoped to the exact preparation, never a locator supplied by a browser. */
  public void written(Preparation preparation) {
    transition(preparation, true);
  }

  /** Unknown does not erase an already durable write acknowledgement. */
  public void unknown(Preparation preparation) {
    transition(preparation, false);
  }

  /**
   * Snapshot evidence only: absence is not proof of orphanhood or permission to delete. Historical
   * credential versions remain retained references after rotation.
   */
  public Observation observe(Preparation preparation) {
    outside();
    if (preparation == null) throw unavailable();
    try {
      return snapshot.execute(
          status -> {
            Scope scope = preparation.scope();
            var results =
                jdbc.queryForList(
                    """
                    select exists (
                      select 1 from provider_credential_versions v
                      join provider_connections c on c.organization_id=v.organization_id
                        and c.id=v.connection_id and c.provider=v.provider
                        and c.credential_revision>=v.credential_revision
                      where v.organization_id=p.organization_id and v.connection_id=p.connection_id
                        and v.provider=p.provider and v.credential_revision=p.credential_revision
                        and v.external_locator=p.external_locator and v.external_key_version='1'
                    )
                    from provider_secret_preparations p
                    where p.id=? and p.organization_id=? and p.connection_id=? and p.provider=?
                      and p.credential_revision=? and p.external_locator=?
                    """,
                    Boolean.class,
                    preparation.id(),
                    scope.organization(),
                    scope.connection(),
                    scope.provider().name(),
                    scope.credentialRevision(),
                    preparation.locator());
            if (results.size() != 1 || results.get(0) == null) throw unavailable();
            return results.get(0) ? Observation.RETAINED_REFERENCE_FOUND : Observation.UNRESOLVED;
          });
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  /**
   * Positive evidence for this Create attempt only. A receipt for a competing preparation or an
   * absent receipt leaves this preparation unresolved and grants no cleanup authority.
   */
  public Observation observeCreate(Preparation preparation, CreateAttempt attempt) {
    outside();
    if (preparation == null || attempt == null) throw unavailable();
    try {
      return snapshot.execute(
          status -> {
            Scope scope = preparation.scope();
            var results =
                jdbc.queryForList(
                    """
                    select p.state='WRITTEN' and exists (
                      select 1 from provider_command_receipts r
                      join provider_credential_versions v on v.organization_id=r.organization_id
                        and v.connection_id=r.connection_id
                      join provider_connections c on c.organization_id=v.organization_id
                        and c.id=v.connection_id and c.provider=v.provider
                        and c.credential_revision>=v.credential_revision
                      where r.organization_id=p.organization_id and r.actor_id=p.create_actor_id
                        and r.idempotency_key=p.create_idempotency_key and r.operation='CREATE'
                        and r.connection_id=p.connection_id and v.provider=p.provider
                        and v.credential_revision=p.credential_revision
                        and v.external_locator=p.external_locator
                        and v.external_key_version=p.external_version
                    )
                    from provider_secret_preparations p
                    where p.id=? and p.organization_id=? and p.connection_id=? and p.provider=?
                      and p.credential_revision=? and p.external_locator=?
                      and p.create_actor_id=? and p.create_idempotency_key=?
                    """,
                    Boolean.class,
                    preparation.id(),
                    scope.organization(),
                    scope.connection(),
                    scope.provider().name(),
                    scope.credentialRevision(),
                    preparation.locator(),
                    attempt.actor(),
                    attempt.idempotencyKey());
            if (results.size() != 1 || results.get(0) == null) throw unavailable();
            return results.get(0) ? Observation.RETAINED_REFERENCE_FOUND : Observation.UNRESOLVED;
          });
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private void transition(Preparation preparation, boolean written) {
    outside();
    if (preparation == null) throw unavailable();
    try {
      transaction.executeWithoutResult(
          status -> {
            Scope scope = preparation.scope();
            String where =
                " where id=? and organization_id=? and connection_id=? and provider=?"
                    + " and credential_revision=? and external_locator=?";
            var rows =
                jdbc.queryForList(
                    "select state from provider_secret_preparations" + where + " for update",
                    String.class,
                    preparation.id(),
                    scope.organization(),
                    scope.connection(),
                    scope.provider().name(),
                    scope.credentialRevision(),
                    preparation.locator());
            if (rows.size() != 1) throw unavailable();
            String state = rows.get(0);
            if ("WRITTEN".equals(state) || (!written && "UNKNOWN".equals(state))) return;
            if (!"PENDING".equals(state) && !"UNKNOWN".equals(state)) throw unavailable();
            if (jdbc.update(
                    "update provider_secret_preparations set state=?,external_version=?,"
                        + "updated_at=CURRENT_TIMESTAMP"
                        + where,
                    written ? "WRITTEN" : "UNKNOWN",
                    written ? "1" : null,
                    preparation.id(),
                    scope.organization(),
                    scope.connection(),
                    scope.provider().name(),
                    scope.credentialRevision(),
                    preparation.locator())
                != 1) throw unavailable();
          });
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static void outside() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("PREPARATION_JOURNAL_UNAVAILABLE", null);
  }
}

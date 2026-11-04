package com.agentos.providerpreparation;

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

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public ProviderPreparationJournal(DataSource datasource) {
    if (datasource == null) throw unavailable();
    this.jdbc = new JdbcTemplate(datasource);
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
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

  /** Acknowledgement is scoped to the exact preparation, never a locator supplied by a browser. */
  public void written(Preparation preparation) {
    transition(preparation, true);
  }

  /** Unknown does not erase an already durable write acknowledgement. */
  public void unknown(Preparation preparation) {
    transition(preparation, false);
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

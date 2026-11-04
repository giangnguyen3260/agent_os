package com.agentos.providerpreparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

public class ProviderPreparationJournalTest {
  @Test
  void actualMigrationAndDurableScopedTransitionsOnH2() throws Exception {
    var datasource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:preparation_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    var jdbc = new JdbcTemplate(datasource);
    jdbc.execute("create domain TIMESTAMPTZ as timestamp with time zone");
    jdbc.execute("create table organizations(id uuid primary key)");
    try (var connection = datasource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V026__provider_preparation_journal.sql"));
    }
    UUID org = UUID.randomUUID();
    jdbc.update("insert into organizations(id) values (?)", org);
    verify(datasource, org);
  }

  /** Shared assertions run against the migrated, disposable native PostgreSQL database too. */
  public static void verify(DataSource datasource, UUID organization) {
    var journal = new ProviderPreparationJournal(datasource);
    var jdbc = new JdbcTemplate(datasource);
    var scope = new Scope(organization, UUID.randomUUID(), Provider.OPENAI, 1);
    String locator = UUID.randomUUID().toString();
    var preparation = journal.begin(scope, locator);
    assertThat(
            jdbc.queryForObject(
                "select state from provider_secret_preparations where id=?",
                String.class,
                preparation.id()))
        .isEqualTo("PENDING");
    assertThatThrownBy(() -> journal.begin(scope, locator))
        .hasMessage("PREPARATION_JOURNAL_UNAVAILABLE")
        .hasNoCause();
    journal.unknown(preparation);
    journal.unknown(preparation);
    assertThat(
            jdbc.queryForObject(
                "select state from provider_secret_preparations where id=?",
                String.class,
                preparation.id()))
        .isEqualTo("UNKNOWN");
    var foreign =
        new ProviderPreparationJournal.Preparation(
            preparation.id(),
            new Scope(organization, UUID.randomUUID(), Provider.OPENAI, 1),
            locator);
    assertThatThrownBy(() -> journal.written(foreign))
        .hasMessage("PREPARATION_JOURNAL_UNAVAILABLE")
        .hasNoCause();
    journal.written(preparation);
    journal.written(preparation);
    journal.unknown(preparation);
    assertThat(
            jdbc.queryForObject(
                "select state from provider_secret_preparations where id=?",
                String.class,
                preparation.id()))
        .isEqualTo("WRITTEN");
    assertThat(
            jdbc.queryForObject(
                "select external_version from provider_secret_preparations where id=?",
                String.class,
                preparation.id()))
        .isEqualTo("1");
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    transaction.executeWithoutResult(
        status -> {
          assertThatThrownBy(() -> journal.begin(scope, UUID.randomUUID().toString()))
              .hasMessage("PREPARATION_JOURNAL_UNAVAILABLE");
          assertThatThrownBy(() -> journal.unknown(preparation))
              .hasMessage("PREPARATION_JOURNAL_UNAVAILABLE");
        });
    assertThat(jdbc.queryForObject("select count(*) from provider_secret_preparations", Long.class))
        .isEqualTo(1L);
  }
}

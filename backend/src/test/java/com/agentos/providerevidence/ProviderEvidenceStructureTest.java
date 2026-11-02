package com.agentos.providerevidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** H2 portability/structure only; not PostgreSQL privilege or publication evidence. */
class ProviderEvidenceStructureTest {
  private JdbcTemplate jdbc;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:evidence_"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF"
                + " NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
            "sa",
            "");
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    EvidenceAssertions.empty(jdbc);
    EvidenceAssertions.seed(jdbc);
  }

  @Test
  void portableMigrationIsEmptyAndExactBindingsRejectSubstitution() {
    EvidenceAssertions.bindings(jdbc);
  }

  @Test
  void timeRevisionNullEnumAndDuplicateConstraintsAreReal() {
    EvidenceAssertions.constraints(jdbc);
  }

  @Test
  void schemaContainsOnlyNonsecretAllowlistedColumnsWithNoDefaults() {
    for (String table :
        java.util.List.of(
            "provider_credential_integrity",
            "provider_credential_health",
            "provider_evidence_publishers")) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from information_schema.columns where table_name=?"
                      + " and (is_nullable<>'NO' or column_default is not null)",
                  Long.class,
                  table))
          .isZero();
    }
    assertThat(
            jdbc.queryForList(
                "select upper(column_name) from information_schema.columns"
                    + " where table_name='provider_credential_integrity' order by ordinal_position",
                String.class))
        .containsExactly(
            "ORGANIZATION_ID",
            "CONNECTION_ID",
            "PROVIDER",
            "CREDENTIAL_REVISION",
            "EXTERNAL_LOCATOR",
            "EXTERNAL_KEY_VERSION",
            "HMAC_KEY_VERSION",
            "HMAC_DOMAIN_VERSION",
            "STATE",
            "RECORDED_AT",
            "PUBLICATION_REVISION");
    assertThat(
            jdbc.queryForList(
                "select upper(column_name) from information_schema.columns"
                    + " where table_name='provider_credential_health' order by ordinal_position",
                String.class))
        .containsExactly(
            "ORGANIZATION_ID",
            "CONNECTION_ID",
            "PROVIDER",
            "CREDENTIAL_REVISION",
            "EXTERNAL_LOCATOR",
            "EXTERNAL_KEY_VERSION",
            "HMAC_KEY_VERSION",
            "HMAC_DOMAIN_VERSION",
            "STATE",
            "OBSERVED_AT",
            "PUBLISHED_AT",
            "VALID_UNTIL",
            "PUBLICATION_REVISION",
            "PUBLISHER_ID",
            "PUBLISHER_EPOCH",
            "PUBLISHER_FENCE");
  }
}

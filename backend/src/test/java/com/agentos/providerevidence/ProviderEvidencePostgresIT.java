package com.agentos.providerevidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Requires an explicitly disposable PostgreSQL 16 service. Never skips missing native inputs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderEvidencePostgresIT {
  private static final String READER = "agentos_provider_evidence_reader";
  private static final String PUBLISHER = "agentos_provider_evidence_publisher";
  private static final List<String> TABLES =
      List.of(
          "provider_credential_integrity",
          "provider_credential_health",
          "provider_evidence_publishers");
  private final String database = "evidence_" + UUID.randomUUID().toString().replace("-", "");
  private final String owner = database + "_owner";
  private String adminUrl;
  private String url;
  private String user;
  private String password;
  private String roleScript;
  private boolean ownerCreated;
  private boolean databaseCreated;
  private boolean capabilityRolesCreated;
  private JdbcTemplate jdbc;

  private static String required(String suffix) {
    String value = System.getenv("TASK_EDIT_TEST_" + suffix);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Explicit TASK_EDIT_TEST_" + suffix + " required; no skip");
    }
    return value;
  }

  private Connection connect(String target) throws SQLException {
    return DriverManager.getConnection(target, user, password);
  }

  @BeforeAll
  void provisionDisposableDatabaseAndExecuteActualOperatorScript() throws Exception {
    assertThat(System.getenv("PROVIDER_EVIDENCE_ALLOW_ROLE_TESTS"))
        .as("Explicit disposable cluster-role test opt-in required; no skip")
        .isEqualTo("true");
    adminUrl = required("JDBC_URL");
    assertThat(adminUrl).startsWith("jdbc:postgresql:");
    user = required("DB_USER");
    password = required("DB_PASSWORD");
    Path script = Path.of("operations/provider-evidence-roles.sql");
    if (!Files.isRegularFile(script))
      script = Path.of("backend/operations/provider-evidence-roles.sql");
    roleScript = Files.readString(script);
    try (var connection = connect(adminUrl);
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("select current_setting('server_version_num')::int")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isBetween(160000, 169999);
      }
      // Refuse existing shared role names before creating any disposable objects.
      try (var rows =
          statement.executeQuery(
              "select count(*) from pg_roles where rolname in ('"
                  + READER
                  + "','"
                  + PUBLISHER
                  + "')")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).as("role names must be unused on disposable service").isZero();
      }
      statement.execute("create role " + owner + " nologin nosuperuser nocreatedb nocreaterole");
      ownerCreated = true;
      statement.execute("create database " + database + " owner " + owner);
      databaseCreated = true;
    }
    // Keep connection properties but replace only the database path of an explicit host URL.
    int query = adminUrl.indexOf('?');
    String base = query < 0 ? adminUrl : adminUrl.substring(0, query);
    assertThat(base).contains("//");
    url =
        base.substring(0, base.lastIndexOf('/') + 1)
            + database
            + (query < 0 ? "" : adminUrl.substring(query));
    Flyway.configure()
        .dataSource(url, user, password)
        .initSql("set role " + owner)
        .target("24")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    EvidenceAssertions.seed(jdbc);
    String maximum = "a".repeat(256);
    jdbc.update(
        "update provider_credential_versions set external_locator=?,external_key_version=?,"
            + "hmac_key_version=?",
        maximum,
        maximum,
        maximum);
    var retained = jdbc.queryForList("select * from provider_credential_versions");
    Flyway.configure()
        .dataSource(url, user, password)
        .initSql("set role " + owner)
        .load()
        .migrate();
    assertThat(jdbc.queryForList("select * from provider_credential_versions")).isEqualTo(retained);
    EvidenceAssertions.empty(jdbc);
    try (var connection = connect(url);
        var statement = connection.createStatement()) {
      statement.execute(roleScript);
      capabilityRolesCreated = true;
    }
  }

  @AfterAll
  void removeOnlyObjectsCreatedByThisFixture() throws Exception {
    if (adminUrl == null || user == null || password == null) return;
    try (var connection = connect(adminUrl);
        var statement = connection.createStatement()) {
      if (databaseCreated) statement.execute("drop database " + database + " with (force)");
      if (capabilityRolesCreated) {
        statement.execute("drop role " + READER);
        statement.execute("drop role " + PUBLISHER);
      }
      if (ownerCreated) statement.execute("drop role " + owner);
    }
  }

  @BeforeEach
  void fixtureRows() {
    for (String table : TABLES) jdbc.update("delete from " + table);
    jdbc.update("delete from provider_credential_versions");
    jdbc.update("delete from organizations");
    EvidenceAssertions.seed(jdbc);
  }

  @Test
  void nativeExactForeignKeysAndTemporalConstraintsRejectInvalidRows() {
    EvidenceAssertions.bindings(jdbc);
    EvidenceAssertions.constraints(jdbc);
    for (String table : TABLES.subList(0, 2)) {
      assertThat(
              jdbc.queryForList(
                  "select pg_get_constraintdef(oid) from pg_constraint"
                      + " where conrelid=?::regclass and contype='f'",
                  String.class,
                  table))
          .singleElement()
          .asString()
          .isEqualTo(
              "FOREIGN KEY (organization_id, connection_id, provider, credential_revision,"
                  + " external_locator, external_key_version, hmac_key_version,"
                  + " hmac_domain_version) REFERENCES provider_credential_versions(organization_id,"
                  + " connection_id, provider, credential_revision, external_locator,"
                  + " external_key_version, hmac_key_version, hmac_domain_version)");
    }
  }

  @Test
  void maximumContractAsciiBindingsFitIndexAndRetainExactEvidence() {
    jdbc.update("delete from provider_credential_versions");
    String maximum = "a".repeat(256);
    String values =
        EvidenceAssertions.VALUES
            .replace("'ref'", "'" + maximum + "'")
            .replace("'external-v1'", "'" + maximum + "'")
            .replace("'hmac-v1'", "'" + maximum + "'");
    jdbc.update(
        "insert into provider_credential_versions("
            + EvidenceAssertions.BINDING
            + ",verifier,created_at) values ("
            + values
            + ",'"
            + "A".repeat(43)
            + "=',current_timestamp)");
    jdbc.update(
        EvidenceAssertions.integrity(
            values, "'VERIFIED_AT_WRITE'", EvidenceAssertions.OBSERVED, "1"));
    jdbc.update(
        EvidenceAssertions.health(
            values,
            "'USABLE'",
            EvidenceAssertions.OBSERVED,
            EvidenceAssertions.OBSERVED,
            EvidenceAssertions.OBSERVED + " + interval '60' second",
            "1"));
    assertThat(
            jdbc.queryForObject(
                "select external_locator from provider_credential_integrity", String.class))
        .isEqualTo(maximum);
  }

  @Test
  void nativeInfinityAndUnsupportedCivilDatesCannotBecomeEvidence() {
    for (String instant :
        List.of("infinity", "-infinity", "10000-01-01 00:00:00+00", "0001-01-01 00:00:00+00 BC")) {
      String time = "cast('" + instant + "' as timestamp with time zone)";
      EvidenceAssertions.rejected(
          jdbc, EvidenceAssertions.integrity(EvidenceAssertions.VALUES, "'FAILED'", time, "1"));
      for (int field = 0; field < 3; field++) {
        String[] times = {
          EvidenceAssertions.OBSERVED,
          EvidenceAssertions.OBSERVED,
          EvidenceAssertions.OBSERVED + " + interval '60' second"
        };
        times[field] = time;
        EvidenceAssertions.rejected(
            jdbc,
            EvidenceAssertions.health(
                EvidenceAssertions.VALUES, "'USABLE'", times[0], times[1], times[2], "1"));
      }
      EvidenceAssertions.empty(jdbc);
    }
  }

  @Test
  void actualRestrictedRolesHaveOnlyReviewedPrivilegesAndAreNotOwners() throws Exception {
    EvidenceAssertions.empty(jdbc);
    jdbc.update(EvidenceAssertions.integrity());
    jdbc.update(EvidenceAssertions.health());
    for (String role : List.of(READER, PUBLISHER)) {
      var attributes =
          jdbc.queryForMap(
              "select rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,"
                  + " rolinherit,rolreplication,rolbypassrls from pg_roles where rolname=?",
              role);
      assertThat(attributes.values()).containsOnly(false);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from pg_auth_members m join pg_roles r"
                      + " on r.oid=m.member where r.rolname=?",
                  Long.class,
                  role))
          .isZero();
      try (var connection = connect(url);
          var statement = connection.createStatement()) {
        statement.execute("set role " + role);
        try (var rows = statement.executeQuery("select current_user")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo(role);
        }
        for (String table : TABLES.subList(0, 2)) {
          if (role.equals(READER)) {
            try (var rows = statement.executeQuery("select * from " + table)) {
              assertThat(rows.next()).isTrue();
              assertThat(rows.getString("state")).isIn("VERIFIED_AT_WRITE", "USABLE");
              assertThat(rows.next()).isFalse();
            }
          } else denied(connection, "select * from " + table);
          denied(connection, "update " + table + " set publication_revision=2");
          denied(connection, "delete from " + table);
          denied(connection, "truncate " + table);
          denied(connection, "alter table " + table + " add column bypass integer");
        }
        for (String table :
            List.of("provider_credential_versions", "provider_evidence_publishers")) {
          if (role.equals(READER)) {
            try (var rows = statement.executeQuery("select count(*) from " + table)) {
              assertThat(rows.next()).isTrue();
              assertThat(rows.getLong(1)).isEqualTo(table.endsWith("versions") ? 1L : 0L);
            }
          } else denied(connection, "select * from " + table);
        }
        denied(connection, "update provider_evidence_publishers set publisher_epoch=2");
        denied(connection, "delete from provider_evidence_publishers");
        denied(connection, "truncate provider_evidence_publishers");
        denied(connection, "alter table provider_evidence_publishers add column bypass integer");
        // Valid statements: an unrelated constraint/syntax failure cannot count as denial.
        denied(connection, EvidenceAssertions.integrity());
        denied(connection, EvidenceAssertions.health());
        denied(connection, "update provider_credential_versions set external_locator='changed'");
        denied(connection, "update provider_credential_versions set verifier='changed'");
        denied(connection, "delete from provider_credential_versions");
        denied(connection, "truncate provider_credential_versions cascade");
        denied(connection, "alter table provider_credential_versions add column bypass integer");
        denied(connection, "create table public.evidence_bypass(id integer)");
        denied(
            connection,
            "insert into provider_evidence_publishers values ('"
                + EvidenceAssertions.PUBLISHER
                + "',1,'"
                + EvidenceAssertions.FENCE
                + "')");
      }
    }
    assertThat(
            jdbc.queryForList(
                "select distinct pg_get_userbyid(relowner) from pg_class where oid in"
                    + " ('provider_credential_versions'::regclass,"
                    + " 'provider_credential_integrity'::regclass,'provider_credential_health'::regclass,"
                    + " 'provider_evidence_publishers'::regclass)",
                String.class))
        .containsExactly(owner);
    // Owners/admins deliberately retain tampering access for retained corruption tests.
    jdbc.update("update provider_credential_versions set verifier='privileged-test-corruption'");
    assertThat(
            jdbc.queryForObject("select verifier from provider_credential_versions", String.class))
        .isEqualTo("privileged-test-corruption");
  }

  private static void denied(Connection connection, String sql) {
    assertThatThrownBy(
            () -> {
              try (var statement = connection.createStatement()) {
                statement.execute(sql);
              }
            })
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
  }

  @Test
  void operatorScriptRefusesExistingRolesBeforeChangingGrants() throws Exception {
    var before =
        jdbc.queryForList(
            "select * from information_schema.role_table_grants"
                + " where grantee in ('"
                + READER
                + "','"
                + PUBLISHER
                + "') order by grantee,table_name");
    try (var connection = connect(url);
        var statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute(roleScript))
          .isInstanceOfSatisfying(
              SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
      statement.execute("rollback");
    }
    assertThat(
            jdbc.queryForList(
                "select * from information_schema.role_table_grants"
                    + " where grantee in ('"
                    + READER
                    + "','"
                    + PUBLISHER
                    + "') order by grantee,table_name"))
        .isEqualTo(before);
  }

  @Test
  void catalogHasExactColumnsNoDefaultsSixDigitTimestampsAndPositiveFence() {
    for (String table : TABLES) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from information_schema.columns"
                      + " where table_schema='public' and table_name=?"
                      + " and (is_nullable<>'NO' or column_default is not null)",
                  Long.class,
                  table))
          .isZero();
    }
    assertThat(
            jdbc.queryForList(
                "select datetime_precision from information_schema.columns where"
                    + " table_schema='public' and table_name like 'provider_credential_%' and"
                    + " column_name in ('recorded_at','observed_at','published_at','valid_until')",
                Integer.class))
        .containsExactlyInAnyOrder(6, 6, 6, 6);
    for (String table : TABLES.subList(0, 2)) {
      String columns =
          EvidenceAssertions.BINDING
              + (table.endsWith("integrity")
                  ? ",state,recorded_at,publication_revision"
                  : ",state,observed_at,published_at,valid_until,publication_revision,"
                      + "publisher_id,publisher_epoch,publisher_fence");
      assertThat(
              jdbc.queryForList(
                  "select column_name from information_schema.columns"
                      + " where table_schema='public' and table_name=? order by ordinal_position",
                  String.class,
                  table))
          .containsExactly(columns.toLowerCase(Locale.ROOT).split(","));
    }
    for (String table : TABLES) {
      var columns =
          jdbc.queryForList(
              "select column_name,data_type,character_maximum_length"
                  + " from information_schema.columns where table_schema='public' and table_name=?",
              table);
      for (var column : columns) {
        String name = (String) column.get("column_name");
        String expected =
            switch (name) {
              case "organization_id", "connection_id", "publisher_id", "publisher_fence" -> "uuid";
              case "credential_revision", "publication_revision", "publisher_epoch" -> "bigint";
              case "observed_at", "published_at", "valid_until", "recorded_at" ->
                  "timestamp with time zone";
              default -> "character varying";
            };
        assertThat(column.get("data_type")).isEqualTo(expected);
        if (expected.equals("character varying")) {
          int length =
              switch (name) {
                case "provider" -> 20;
                case "hmac_domain_version" -> 2;
                case "state" -> table.endsWith("integrity") ? 17 : 11;
                default -> 256;
              };
          assertThat(((Number) column.get("character_maximum_length")).intValue())
              .isEqualTo(length);
        }
      }
    }
    for (String epoch : List.of("0", "-1", "null", "9223372036854775808")) {
      EvidenceAssertions.rejected(
          jdbc,
          "insert into provider_evidence_publishers values ('"
              + EvidenceAssertions.PUBLISHER
              + "',"
              + epoch
              + ",'"
              + EvidenceAssertions.FENCE
              + "')");
    }
    jdbc.update(
        "insert into provider_evidence_publishers values ('"
            + EvidenceAssertions.PUBLISHER
            + "',1,'"
            + EvidenceAssertions.FENCE
            + "')");
    jdbc.update(EvidenceAssertions.health());
    jdbc.update(
        "update provider_evidence_publishers set publisher_epoch=2,publisher_fence=?",
        UUID.randomUUID());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from provider_credential_health h join"
                    + " provider_evidence_publishers p on p.publisher_id=h.publisher_id and"
                    + " p.publisher_epoch=h.publisher_epoch and"
                    + " p.publisher_fence=h.publisher_fence",
                Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from provider_credential_health", Long.class))
        .isEqualTo(1);
  }
}

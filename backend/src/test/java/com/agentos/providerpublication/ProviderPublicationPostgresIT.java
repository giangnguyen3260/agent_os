package com.agentos.providerpublication;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Native guarded-protocol fixtures, not observer validity. Requires a fresh disposable PostgreSQL
 * 16 cluster and explicit opt-in. Passwords are supplied by CI, never generated or printed here.
 *
 * <p>Executes the actual installer file. All producer outcomes are controlled assertions, not
 * network checks. Administrator connections are used only for provisioning, catalog checks and race
 * fixtures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderPublicationPostgresIT {
  private static final String READER = "agentos_provider_evidence_reader";
  private static final String PUBLISHER = "agentos_provider_evidence_publisher";
  private static final String DEFINER = "agentos_provider_evidence_owner";
  private static final List<String> ACTORS =
      List.of("health", "prepare", "unregistered", "web", "reader", "recreated");
  private static final List<String> TABLES =
      List.of(
          "provider_credential_versions",
          "provider_evidence_publishers",
          "provider_credential_integrity",
          "provider_credential_health");

  private final String database = "publication_" + UUID.randomUUID().toString().replace("-", "");
  private final List<String> createdRoles = new ArrayList<>();
  private String adminUrl;
  private String databaseUrl;
  private boolean databaseCreated;
  private boolean unsafePublisherRefused;
  private boolean unsafeReaderRefused;

  private static String required(String name) {
    String value = System.getenv("PROVIDER_PUBLICATION_" + name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Explicit publication test input required: " + name);
    }
    return value;
  }

  private static String password(String actor) {
    String value = required(actor.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD");
    if (!actor.equals("admin") && !value.matches("[A-Za-z0-9_-]{40,128}")) {
      throw new IllegalStateException(
          "Publication test passwords must be CI-generated URL-safe data");
    }
    return value;
  }

  private String login(String actor) {
    return database + "_" + actor;
  }

  private static Connection connect(String url, String user, String password) {
    try {
      return DriverManager.getConnection(url, user, password);
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication fixture connection unavailable");
    }
  }

  private Connection admin(String url) {
    return connect(url, required("ADMIN_USER"), password("admin"));
  }

  private Connection actor(String actor) {
    return connect(databaseUrl, login(actor), password(actor));
  }

  private static Path operation(String file) {
    Path path = Path.of("operations", file);
    return Files.isRegularFile(path) ? path : Path.of("backend", "operations", file);
  }

  /** Executes the exact reviewed operations file rather than mirrored test functions. */
  static void installActualPublicationScript(Connection operator) throws Exception {
    String script = Files.readString(operation("provider-evidence-publication.sql"));
    try (var statement = operator.createStatement()) {
      statement.execute(script);
    } catch (SQLException failure) {
      String state = failure.getSQLState();
      if (state == null || !state.matches("[0-9A-Z]{5}")) state = "XXXXX";
      int position = 0;
      if (failure instanceof org.postgresql.util.PSQLException postgres
          && postgres.getServerErrorMessage() != null) {
        position = Math.max(0, postgres.getServerErrorMessage().getPosition());
      }
      // Static installer diagnostics only: never attach the driver exception or its message.
      throw new IllegalStateException(
          "Publication installer failed [SQLSTATE=" + state + ",position=" + position + "]");
    }
  }

  @BeforeAll
  void provisionDisposableClusterUsingUnchangedBaseRoleScript() throws Exception {
    assertThat(System.getenv("PROVIDER_PUBLICATION_ALLOW_ROLE_TESTS"))
        .as("Explicit fresh disposable cluster opt-in; never silently skip")
        .isEqualTo("true");
    adminUrl = required("ADMIN_JDBC_URL");
    assertThat(adminUrl)
        .matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+");
    for (String name : ACTORS) password(name);
    databaseUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;
    try (var connection = admin(adminUrl);
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("select current_setting('server_version_num')::int")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isBetween(160000, 169999);
      }
      // Refuse collisions before making cluster-wide changes; never repair shared roles.
      try (var rows =
          statement.executeQuery(
              "select count(*) from pg_catalog.pg_roles where rolname in ('"
                  + READER
                  + "','"
                  + PUBLISHER
                  + "','"
                  + DEFINER
                  + "')")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).as("Fresh disposable capability names required").isZero();
      }
      statement.execute("create database " + database);
      databaseCreated = true;
      for (String name : ACTORS) {
        statement.execute(
            "create role "
                + login(name)
                + " login nosuperuser nocreatedb nocreaterole inherit noreplication nobypassrls"
                + " password '"
                + password(name)
                + "'");
        createdRoles.add(login(name));
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication fixture provisioning failed");
    }
    try {
      Flyway.configure()
          .dataSource(databaseUrl, required("ADMIN_USER"), password("admin"))
          .load()
          .migrate();
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Publication fixture migration failed");
    }
    try (var connection = admin(databaseUrl);
        var statement = connection.createStatement()) {
      // Execute the exact accepted base file. Do not change or mirror its SQL in this harness.
      statement.execute(Files.readString(operation("provider-evidence-roles.sql")));
      createdRoles.add(READER);
      createdRoles.add(PUBLISHER);
      for (String name : List.of("health", "prepare", "unregistered", "recreated")) {
        statement.execute("grant " + PUBLISHER + " to " + login(name));
      }
      statement.execute("grant " + READER + " to " + login("reader"));
      createdRoles.add(DEFINER);
      proveUnsafeCapabilityRefusedWithoutRepair(connection, PUBLISHER);
      proveUnsafeCapabilityRefusedWithoutRepair(connection, READER);
      installActualPublicationScript(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication base capability installation failed");
    }
    // Every operator connection is closed before JUnit performs worker actions.
  }

  private void proveUnsafeCapabilityRefusedWithoutRepair(Connection operator, String capability)
      throws Exception {
    try (var statement = operator.createStatement()) {
      statement.execute(
          "grant update(state) on public.provider_credential_integrity to " + capability);
      try {
        statement.execute(Files.readString(operation("provider-evidence-publication.sql")));
        throw new AssertionError("Installer must refuse a contaminated evidence capability");
      } catch (SQLException expected) {
        assertThat(expected.getSQLState()).isEqualTo("P0001");
      } finally {
        statement.execute("rollback");
      }
      try (var rows =
          statement.executeQuery(
              "select"
                  + " has_column_privilege('"
                  + capability
                  + "','public.provider_credential_integrity','state','UPDATE'),"
                  + " (select count(*) from pg_catalog.pg_roles where rolname='"
                  + DEFINER
                  + "'), (select count(*) from pg_catalog.pg_namespace where"
                  + " nspname='agentos_evidence_private')")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).as("No silent privilege repair").isTrue();
        assertThat(rows.getLong(2)).isZero();
        assertThat(rows.getLong(3)).isZero();
      }
      statement.execute(
          "revoke update(state) on public.provider_credential_integrity from " + capability);
      if (capability.equals(PUBLISHER)) unsafePublisherRefused = true;
      else unsafeReaderRefused = true;
    }
  }

  @Test
  void contaminatedBaseCapabilityWasRefusedAtomicallyBeforeSuccessfulInstallation() {
    assertThat(unsafePublisherRefused).isTrue();
  }

  @Test
  void contaminatedReaderCapabilityWasRefusedWithoutRepair() {
    assertThat(unsafeReaderRefused).isTrue();
  }

  @BeforeEach
  void resetOnlyDisposableFixtureRows() throws Exception {
    try (var connection = admin(databaseUrl);
        var statement = connection.createStatement()) {
      statement.execute(
          "truncate agentos_evidence_private.integrity_provenance,"
              + "agentos_evidence_private.integrity_assertions,"
              + "agentos_evidence_private.publication_attempts,"
              + "agentos_evidence_private.publisher_permissions,"
              + "agentos_evidence_private.publisher_principals,"
              + "public.provider_evidence_publishers,public.organizations cascade");
    }
  }

  private ProviderPublicationClient client(String name) {
    return new ProviderPublicationClient(
        new DriverManagerDataSource(databaseUrl, login(name), password(name)));
  }

  private ProviderPublicationClient.Scope seed() throws Exception {
    UUID org = UUID.randomUUID();
    UUID connection = UUID.randomUUID();
    update(
        "insert into public.organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,'Publication',?,'ACTIVE',statement_timestamp(),statement_timestamp())",
        org,
        org.toString());
    update(
        "insert into public.provider_credential_versions values"
            + " (?,?,'OPENAI',1,'ref','external-v1','hmac-v1','v2',?,statement_timestamp())",
        org,
        connection,
        "A".repeat(43) + "=");
    update(
        "insert into public.provider_connections values"
            + " (?,?,'OPENAI','OPENAI_API_KEY','Publication',null,'ENABLED',1,1,statement_timestamp(),statement_timestamp())",
        connection,
        org);
    register("health", org, "HEALTH", "INTEGRITY_FAILURE");
    register("prepare", org, "PREPARE");
    return new ProviderPublicationClient.Scope(org, connection, "OPENAI", 1);
  }

  private void register(String name, UUID org, String... kinds) throws Exception {
    UUID publisher = UUID.randomUUID();
    update(
        "insert into public.provider_evidence_publishers values (?,1,?)",
        publisher,
        UUID.randomUUID());
    update(
        "insert into agentos_evidence_private.publisher_principals"
            + " select ?,rolname,oid,?,true from pg_catalog.pg_roles where rolname=?",
        publisher,
        org,
        login(name));
    for (String kind : kinds) {
      update(
          "insert into agentos_evidence_private.publisher_permissions values (?,'OPENAI',?)",
          publisher,
          kind);
    }
  }

  private void update(String sql, Object... args) throws Exception {
    try (var connection = admin(databaseUrl);
        var statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication fixture update failed");
    }
  }

  private long count(String sql) throws Exception {
    try (var connection = admin(databaseUrl);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private String fingerprint() throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    for (String table :
        List.of(
            "public.organizations",
            "public.provider_connections",
            "public.provider_credential_versions",
            "public.provider_evidence_publishers",
            "public.provider_credential_integrity",
            "public.provider_credential_health",
            "agentos_evidence_private.publisher_principals",
            "agentos_evidence_private.publisher_permissions",
            "agentos_evidence_private.publication_attempts",
            "agentos_evidence_private.integrity_assertions",
            "agentos_evidence_private.integrity_provenance")) {
      digest.update(table.getBytes(StandardCharsets.UTF_8));
      try (var connection = admin(databaseUrl);
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "select pg_catalog.row_to_json(t)::text from "
                      + table
                      + " t order by pg_catalog.row_to_json(t)::text collate \"C\"")) {
        while (rows.next()) {
          digest.update(rows.getString(1).getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
        }
      }
    }
    // Only a digest is included in assertion failures; never print private captured row contents.
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  @Test
  void actualInstallerExposesSixFunctionsAndDefinerHasSelectOnlyVersions() throws Exception {
    assertThat(
            count(
                "select count(*) from pg_catalog.pg_proc p join pg_catalog.pg_namespace n"
                    + " on n.oid=p.pronamespace where n.nspname='agentos_evidence_private' and"
                    + " has_function_privilege('"
                    + PUBLISHER
                    + "',p.oid,'EXECUTE')"))
        .isEqualTo(6);
    assertThat(
            count(
                "select count(*) from pg_catalog.pg_roles where rolname='"
                    + DEFINER
                    + "' and not rolcanlogin and not rolsuper and not rolcreatedb and not"
                    + " rolcreaterole and not rolreplication and not rolbypassrls and not"
                    + " rolinherit"))
        .isEqualTo(1);
    assertThat(
            count(
                "select has_any_column_privilege('"
                    + DEFINER
                    + "','public.provider_credential_versions','UPDATE')::int"))
        .isZero();
    assertThat(
            count(
                "select count(*) from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on"
                    + " n.oid=p.pronamespace where n.nspname='agentos_evidence_private' and"
                    + " p.prosecdef and p.proconfig=array['search_path=pg_catalog,"
                    + " agentos_evidence_private, pg_temp']"))
        .isEqualTo(10);
    try (var connection = actor("health")) {
      denied(connection, "select agentos_evidence_private.cleanup_attempts(null,1)");
      denied(connection, "select * from agentos_evidence_private.publication_attempts");
      denied(connection, "set role " + DEFINER);
    }
  }

  @Test
  void realRegisteredClientsPublishHealthAndLinkedPreparationWithNoAutomaticSuccess()
      throws Exception {
    var scope = seed();
    assertThat(count("select count(*) from public.provider_credential_health")).isZero();
    var denied = client("unregistered").beginHealth(scope);
    assertThat(denied.reason()).isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
    assertThat(denied.value()).isNull();
    assertThat(client("health").beginPreparationAssertion(scope).reason())
        .isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
    var prepared = client("prepare").beginPreparationAssertion(scope);
    assertThat(prepared.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(client("prepare").assertPrepared(prepared.value().attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var captured = client("health").beginHealth(scope);
    assertThat(captured.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var published =
        client("health")
            .publishHealth(
                captured.value().attemptId(),
                ProviderPublicationClient.HealthOutcome.USABLE,
                false,
                null);
    assertThat(published.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(published.value().observedAt()).isEqualTo(captured.value().observedAt());
    assertThat(published.value().validUntil()).isEqualTo(captured.value().deadline());
    assertThat(
            client("health")
                .publishHealth(
                    captured.value().attemptId(),
                    ProviderPublicationClient.HealthOutcome.USABLE,
                    false,
                    null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.ATTEMPT);
    assertThat(
            count(
                "select count(*) from agentos_evidence_private.integrity_provenance q join"
                    + " agentos_evidence_private.integrity_assertions s on"
                    + " s.assertion_id=q.assertion_id join public.provider_credential_integrity i"
                    + " on i.organization_id=q.organization_id and i.connection_id=q.connection_id"
                    + " and i.provider=q.provider and i.credential_revision=q.credential_revision"
                    + " where i.publication_revision=q.publication_revision"))
        .isEqualTo(1);
  }

  @Test
  void casLossInvalidPresenceAndTimeBeforeFailedPreserveEvidence() throws Exception {
    var scope = seed();
    var first = client("health").beginHealth(scope).value();
    var second = client("health").beginHealth(scope).value();
    assertThat(
            client("health")
                .publishHealth(
                    first.attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    String beforeConflict = fingerprint();
    assertThat(
            client("health")
                .publishHealth(
                    second.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.CONFLICT);
    assertThat(fingerprint()).isEqualTo(beforeConflict);
    var fresh = client("health").beginHealth(scope).value();
    String beforePair = fingerprint();
    assertThat(
            client("health")
                .publishHealth(
                    fresh.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, true, null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.ATTEMPT);
    assertThat(fingerprint()).isEqualTo(beforePair);
    var failure = client("health").beginIntegrityVerification(scope).value();
    assertThat(client("health").publishIntegrityFailure(failure.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=statement_timestamp()-interval '61"
            + " seconds',deadline=statement_timestamp()-interval '1 second' where attempt_id=?",
        fresh.attemptId());
    String beforeTime = fingerprint();
    assertThat(
            client("health")
                .publishHealth(
                    fresh.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.TIME);
    assertThat(fingerprint()).isEqualTo(beforeTime);
    assertThat(count("select publication_revision from public.provider_credential_health"))
        .isEqualTo(1);
  }

  @Test
  void delayedHistoricalFailureCannotMutateReplacementVersionOrItsHealth() throws Exception {
    var scope = seed();
    var staleHealth = client("health").beginHealth(scope).value();
    var delayedFailure = client("health").beginIntegrityVerification(scope).value();
    try (var operator = admin(databaseUrl);
        var statement = operator.createStatement()) {
      operator.setAutoCommit(false);
      statement
          .executeQuery(
              "select id from public.organizations where id='"
                  + scope.organizationId()
                  + "' for update")
          .close();
      statement
          .executeQuery(
              "select id from public.provider_connections where id='"
                  + scope.connectionId()
                  + "' for update")
          .close();
      statement.execute(
          "insert into public.provider_credential_versions select"
              + " organization_id,connection_id,provider,2,"
              + "external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,clock_timestamp()"
              + " from public.provider_credential_versions where credential_revision=1");
      statement.execute(
          "update public.provider_connections set configuration_revision=2,credential_revision=2");
      operator.commit();
    }
    var replacement =
        new ProviderPublicationClient.Scope(
            scope.organizationId(), scope.connectionId(), "OPENAI", 2);
    var current = client("health").beginHealth(replacement).value();
    assertThat(
            client("health")
                .publishHealth(
                    current.attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    String beforeStale = fingerprint();
    assertThat(
            client("health")
                .publishHealth(
                    staleHealth.attemptId(),
                    ProviderPublicationClient.HealthOutcome.USABLE,
                    false,
                    null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.BINDING);
    assertThat(fingerprint()).isEqualTo(beforeStale);
    assertThat(client("health").publishIntegrityFailure(delayedFailure.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(
            count(
                "select count(*) from public.provider_credential_integrity where"
                    + " credential_revision=1 and state='FAILED'"))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from public.provider_credential_integrity where"
                    + " credential_revision=2"))
        .isZero();
    assertThat(
            count(
                "select publication_revision from public.provider_credential_health where"
                    + " credential_revision=2"))
        .isEqualTo(1);
  }

  @Test
  void confirmedFailureDominatesNewPreparationAndIsHistoricalAfterRotation() throws Exception {
    var scope = seed();
    var failure = client("health").beginIntegrityVerification(scope).value();
    var prepared = client("prepare").beginPreparationAssertion(scope).value();
    assertThat(client("prepare").assertPrepared(prepared.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(client("health").publishIntegrityFailure(failure.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var again = client("health").beginIntegrityVerification(scope).value();
    update(
        "update public.provider_credential_integrity set publication_revision=9223372036854775807");
    assertThat(client("health").publishIntegrityFailure(again.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ALREADY_FAILED);
    update(
        "insert into public.provider_credential_versions select"
            + " organization_id,connection_id,provider,2,"
            + "external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at"
            + " from public.provider_credential_versions");
    update("update public.provider_connections set configuration_revision=2,credential_revision=2");
    var historical = client("health").beginIntegrityVerification(scope);
    assertThat(historical.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(client("health").beginHealth(scope).reason())
        .isEqualTo(ProviderPublicationClient.Reason.BINDING);
    assertThat(
            count(
                "select count(*) from public.provider_credential_integrity where"
                    + " credential_revision=2"))
        .isZero();
  }

  @Test
  void canonicalVerifierAndChangedFenceAreEnforcedByActualFunctions() throws Exception {
    var scope = seed();
    var attempt = client("health").beginHealth(scope).value();
    update(
        "update public.provider_evidence_publishers set publisher_epoch=2,publisher_fence=?",
        UUID.randomUUID());
    assertThat(
            client("health")
                .publishHealth(
                    attempt.attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
    update("update public.provider_credential_versions set verifier=?", "A".repeat(42) + "B=");
    assertThat(client("health").beginHealth(scope).reason())
        .isEqualTo(ProviderPublicationClient.Reason.BINDING);
    assertThat(count("select count(*) from public.provider_credential_health")).isZero();
  }

  @Test
  void orgCapacitySerializesLastSlotAndOperatorCleanupPreservesPinnedHistory() throws Exception {
    var scope = seed();
    register("unregistered", scope.organizationId(), "HEALTH");
    assertThat(client("health").beginHealth(scope).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    update(
        "insert into agentos_evidence_private.publication_attempts select"
            + " gen_random_uuid(),kind,login_name,login_oid,publisher_id,publisher_epoch,publisher_fence,"
            + "organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,"
            + "hmac_key_version,hmac_domain_version,verifier,integrity_present,integrity_revision,health_present,health_revision,observed_at,deadline,capture_xid,true"
            + " from agentos_evidence_private.publication_attempts cross join"
            + " generate_series(1,4094)");
    var executor = Executors.newFixedThreadPool(2);
    try {
      var left = executor.submit(() -> client("health").beginHealth(scope));
      var right = executor.submit(() -> client("unregistered").beginHealth(scope));
      var results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
      assertThat(
              results.stream()
                  .filter(r -> r.status() == ProviderPublicationClient.Status.ACCEPTED)
                  .count())
          .isEqualTo(1);
      assertThat(
              results.stream()
                  .filter(r -> r.reason() == ProviderPublicationClient.Reason.CAPACITY)
                  .count())
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
        .isEqualTo(4096);
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=statement_timestamp()-interval '7"
            + " minutes',deadline=statement_timestamp()-interval '6 minutes'");
    assertThat(
            count(
                "select agentos_evidence_private.cleanup_attempts('"
                    + scope.organizationId()
                    + "',4096)"))
        .isEqualTo(4096);
    var preparation = client("prepare").beginPreparationAssertion(scope).value();
    assertThat(client("prepare").assertPrepared(preparation.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=statement_timestamp()-interval '7"
            + " minutes',deadline=statement_timestamp()-interval '6 minutes'");
    assertThat(
            count(
                "select agentos_evidence_private.cleanup_attempts('"
                    + scope.organizationId()
                    + "',4096)"))
        .isZero();
    assertThat(count("select count(*) from agentos_evidence_private.integrity_assertions"))
        .isEqualTo(1);
  }

  @Test
  void captureRejectsRegistrationReplacementAfterObservedOrganizationLockWait() throws Exception {
    var scope = seed();
    var executor = Executors.newSingleThreadExecutor();
    try (var operator = admin(databaseUrl);
        var statement = operator.createStatement()) {
      operator.setAutoCommit(false);
      statement
          .executeQuery(
              "select id from public.organizations where id='"
                  + scope.organizationId()
                  + "' for update")
          .close();
      var capture = executor.submit(() -> client("health").beginHealth(scope));
      awaitWorkerLock("health");
      UUID replacement = UUID.randomUUID();
      statement.execute(
          "update public.provider_evidence_publishers set"
              + " publisher_epoch=publisher_epoch+1,publisher_fence=gen_random_uuid() where"
              + " publisher_id in (select publisher_id from"
              + " agentos_evidence_private.publisher_principals where login_name='"
              + login("health")
              + "')");
      statement.execute(
          "delete from agentos_evidence_private.publisher_permissions where publisher_id in (select"
              + " publisher_id from agentos_evidence_private.publisher_principals where"
              + " login_name='"
              + login("health")
              + "')");
      statement.execute(
          "delete from agentos_evidence_private.publisher_principals where login_name='"
              + login("health")
              + "'");
      statement.execute(
          "insert into public.provider_evidence_publishers values ('"
              + replacement
              + "',1,gen_random_uuid())");
      statement.execute(
          "insert into agentos_evidence_private.publisher_principals select '"
              + replacement
              + "',rolname,oid,'"
              + scope.organizationId()
              + "',true from pg_catalog.pg_roles where rolname='"
              + login("health")
              + "'");
      statement.execute(
          "insert into agentos_evidence_private.publisher_permissions values ('"
              + replacement
              + "','OPENAI','HEALTH')");
      operator.commit();
      var result = capture.get(10, TimeUnit.SECONDS);
      assertThat(result.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(result.reason()).isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
      assertThat(result.value()).isNull();
      assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
          .isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiryDuringObservedOrganizationWaitUsesPostLockDatabaseClock() throws Exception {
    var scope = seed();
    var capture = client("health").beginHealth(scope).value();
    // Controlled timestamp fixture leaves a short real deadline; no JVM clock supplies SQL truth.
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=statement_timestamp()-interval '59 seconds',"
            + " deadline=statement_timestamp()+interval '1 second' where attempt_id=?",
        capture.attemptId());
    var executor = Executors.newSingleThreadExecutor();
    try (var operator = admin(databaseUrl);
        var statement = operator.createStatement()) {
      operator.setAutoCommit(false);
      statement
          .executeQuery(
              "select id from public.organizations where id='"
                  + scope.organizationId()
                  + "' for update")
          .close();
      var publication =
          executor.submit(
              () ->
                  client("health")
                      .publishHealth(
                          capture.attemptId(),
                          ProviderPublicationClient.HealthOutcome.USABLE,
                          false,
                          null));
      awaitWorkerLock("health");
      long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean expired = false;
      while (!expired && System.nanoTime() < timeout) {
        try (var rows =
            statement.executeQuery(
                "select clock_timestamp()>=deadline from"
                    + " agentos_evidence_private.publication_attempts where attempt_id='"
                    + capture.attemptId()
                    + "'")) {
          assertThat(rows.next()).isTrue();
          expired = rows.getBoolean(1);
        }
        if (!expired) Thread.sleep(10);
      }
      assertThat(expired).isTrue();
      operator.commit();
      assertThat(publication.get(10, TimeUnit.SECONDS).reason())
          .isEqualTo(ProviderPublicationClient.Reason.TIME);
      assertThat(count("select count(*) from public.provider_credential_health")).isZero();
      assertThat(
              count(
                  "select count(*) from agentos_evidence_private.publication_attempts where"
                      + " consumed"))
          .isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void captureCannotPublishBeforeCommitOrAfterRollback() throws Exception {
    var scope = seed();
    UUID token;
    try (var worker = actor("health");
        var statement = worker.createStatement()) {
      worker.setAutoCommit(false);
      worker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try (var rows =
          statement.executeQuery(
              "select * from agentos_evidence_private.begin_health('"
                  + scope.organizationId()
                  + "','"
                  + scope.connectionId()
                  + "','OPENAI',1)")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("status")).isEqualTo("ACCEPTED");
        token = rows.getObject("attempt_id", UUID.class);
      }
      try (var rows =
          statement.executeQuery(
              "select * from agentos_evidence_private.publish_health('"
                  + token
                  + "','UNAVAILABLE',false,null)")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("reason")).isEqualTo("ATTEMPT");
      }
      worker.rollback();
    }
    assertThat(
            client("health")
                .publishHealth(
                    token, ProviderPublicationClient.HealthOutcome.UNAVAILABLE, false, null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.ATTEMPT);
    assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
        .isZero();
  }

  private void awaitWorkerLock(String name) throws Exception {
    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < timeout) {
      try (var connection = admin(databaseUrl);
          var statement =
              connection.prepareStatement(
                  "select count(*) from pg_catalog.pg_stat_activity"
                      + " where usename=? and wait_event_type='Lock'")) {
        statement.setString(1, login(name));
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          if (rows.getLong(1) > 0) return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected an observed PostgreSQL worker lock wait");
  }

  @AfterAll
  void removeOnlyFixtureOwnedObjects() {
    if (!databaseCreated && createdRoles.isEmpty()) return;
    try (var connection = admin(adminUrl);
        var statement = connection.createStatement()) {
      if (databaseCreated) statement.execute("drop database " + database + " with (force)");
      for (int i = createdRoles.size() - 1; i >= 0; i--) {
        statement.execute("drop role if exists " + createdRoles.get(i));
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication fixture cleanup failed");
    }
  }

  @Test
  void actualLoginSessionsAreDistinctAndUnprivileged() throws Exception {
    for (String name : ACTORS) {
      try (var connection = actor(name);
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "select session_user,current_user,rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,"
                      + "rolreplication,rolbypassrls from pg_catalog.pg_roles"
                      + " where rolname=session_user")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(login(name));
        assertThat(rows.getString(2)).isEqualTo(login(name));
        assertThat(rows.getBoolean(3)).isTrue();
        for (int column = 4; column <= 8; column++) {
          assertThat(rows.getBoolean(column)).isFalse();
        }
        assertThat(rows.next()).isFalse();
      }
    }
    // A trust-authenticated local service would falsely pass identity tests; require
    // SCRAM/password.
    try (var unexpected =
        DriverManager.getConnection(databaseUrl, login("health"), password("web"))) {
      throw new AssertionError("Disposable PostgreSQL must reject an incorrect LOGIN password");
    } catch (SQLException expected) {
      assertThat(expected.getSQLState()).isEqualTo("28P01");
    }
  }

  @Test
  void realLoginCannotAssumeAnotherIdentityAndSetRolePreservesSessionIdentity() throws Exception {
    try (var connection = actor("unregistered");
        var statement = connection.createStatement()) {
      denied(connection, "set session authorization " + login("health"));
      denied(connection, "set role " + login("health"));
      statement.execute("set role " + PUBLISHER);
      try (var rows = statement.executeQuery("select session_user,current_user")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(login("unregistered"));
        assertThat(rows.getString(2)).isEqualTo(PUBLISHER);
      }
    }
  }

  @Test
  void baseCapabilitiesHaveNoEvidenceWritesAndReaderRetainsOnlyReads() throws Exception {
    for (String name : ACTORS) {
      try (var connection = actor(name);
          var statement = connection.createStatement()) {
        for (String table : TABLES) {
          denied(connection, "delete from public." + table);
          denied(connection, "truncate public." + table + " cascade");
          denied(connection, "alter table public." + table + " add column bypass integer");
          if (name.equals("reader")) {
            try (var rows = statement.executeQuery("select count(*) from public." + table)) {
              assertThat(rows.next()).isTrue();
              assertThat(rows.getLong(1)).isZero();
            }
          } else denied(connection, "select * from public." + table);
        }
        denied(connection, "update public.provider_credential_versions set verifier='changed'");
        denied(connection, "update public.provider_evidence_publishers set publisher_epoch=2");
        denied(connection, "update public.provider_credential_health set publication_revision=2");
        denied(
            connection, "update public.provider_credential_integrity set publication_revision=2");
        denied(connection, "create table public.publication_bypass(id integer)");
      }
    }
  }

  @Test
  void recreatedLoginHasNewOidEvenWhenItsNameAndCapabilityAreReused() throws Exception {
    var scope = seed();
    register("recreated", scope.organizationId(), "HEALTH");
    var captured = client("recreated").beginHealth(scope);
    assertThat(captured.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    long original = roleOid("recreated");
    try (var connection = admin(adminUrl);
        var statement = connection.createStatement()) {
      statement.execute("drop role " + login("recreated"));
      createdRoles.remove(login("recreated"));
      statement.execute(
          "create role "
              + login("recreated")
              + " login nosuperuser nocreatedb nocreaterole inherit noreplication nobypassrls"
              + " password '"
              + password("recreated")
              + "'");
      createdRoles.add(login("recreated"));
      statement.execute("grant " + PUBLISHER + " to " + login("recreated"));
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication recreated-LOGIN fixture failed");
    }
    assertThat(roleOid("recreated")).isNotEqualTo(original);
    assertThat(client("recreated").beginHealth(scope).reason())
        .isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
    assertThat(
            client("recreated")
                .publishHealth(
                    captured.value().attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
  }

  private long roleOid(String name) throws Exception {
    try (var connection = actor(name);
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select oid::bigint from pg_catalog.pg_roles where rolname=session_user")) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private static void denied(Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
      throw new AssertionError("Publication fixture expected insufficient privilege");
    } catch (SQLException expected) {
      // A syntax, missing-object or constraint error is never evidence of permission denial.
      assertThat(expected.getSQLState()).isEqualTo("42501");
    }
  }
}

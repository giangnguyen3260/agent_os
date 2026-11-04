package com.agentos.providerpublication;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.AgentOsApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
  private boolean publisherReadRefused;

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
      // Static installer diagnostics only: never attach the driver exception or its message.
      throw safeSqlFailure("Publication installer failed", failure);
    }
  }

  private static IllegalStateException safeSqlFailure(String label, SQLException failure) {
    String state = failure.getSQLState();
    if (state == null || !state.matches("[0-9A-Z]{5}")) state = "XXXXX";
    int position = 0;
    if (failure instanceof org.postgresql.util.PSQLException postgres
        && postgres.getServerErrorMessage() != null) {
      position = Math.max(0, postgres.getServerErrorMessage().getPosition());
    }
    return new IllegalStateException(label + " [SQLSTATE=" + state + ",position=" + position + "]");
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
      proveUnsafeCapabilityRefusedWithoutRepair(connection, PUBLISHER, "UPDATE", "state");
      proveUnsafeCapabilityRefusedWithoutRepair(connection, PUBLISHER, "SELECT", "state");
      proveUnsafeCapabilityRefusedWithoutRepair(connection, READER, "UPDATE", "state");
      installActualPublicationScript(connection);
      statement.execute(Files.readString(operation("provider-list-evidence-reader.sql")));
      createdRoles.add("agentos_provider_list_evidence");
      statement.execute("grant agentos_provider_list_evidence to " + login("web"));
    } catch (SQLException failure) {
      throw new IllegalStateException("Publication base capability installation failed");
    }
    // Every operator connection is closed before JUnit performs worker actions.
  }

  private void proveUnsafeCapabilityRefusedWithoutRepair(
      Connection operator, String capability, String privilege, String column) throws Exception {
    try (var statement = operator.createStatement()) {
      statement.execute(
          "grant "
              + privilege
              + "("
              + column
              + ") on public.provider_credential_integrity to "
              + capability);
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
                  + "','public.provider_credential_integrity','"
                  + column
                  + "','"
                  + privilege
                  + "'),"
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
          "revoke "
              + privilege
              + "("
              + column
              + ") on public.provider_credential_integrity from "
              + capability);
      if (capability.equals(PUBLISHER) && privilege.equals("SELECT")) publisherReadRefused = true;
      else if (capability.equals(PUBLISHER)) unsafePublisherRefused = true;
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

  @Test
  void publisherColumnReadGrantWasRefusedWithoutRepair() {
    assertThat(publisherReadRefused).isTrue();
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

  private ProviderPublicationClient.Result<ProviderPublicationClient.Capture> checkedCapture(
      String name, ProviderPublicationClient.Scope scope, String kind) throws Exception {
    var publisher = client(name);
    var result =
        switch (kind) {
          case "HEALTH" -> publisher.beginHealth(scope);
          case "PREPARE" -> publisher.beginPreparationAssertion(scope);
          case "INTEGRITY_FAILURE" -> publisher.beginIntegrityVerification(scope);
          default -> throw new IllegalArgumentException("Unknown fixture capture kind");
        };
    if (result.status() != ProviderPublicationClient.Status.ACCEPTED) {
      // This diagnostic is test-only, uses a real LOGIN, and always rolls its extra probe back.
      String raw = rawCaptureDiagnostic(name, scope, kind);
      throw new AssertionError(
          "Expected accepted capture [clientStatus="
              + result.status()
              + ",clientReason="
              + result.reason()
              + ",raw="
              + raw
              + "]");
    }
    assertThat(result.value()).isNotNull();
    return result;
  }

  private String rawCaptureDiagnostic(
      String name, ProviderPublicationClient.Scope scope, String kind) throws Exception {
    String sql =
        switch (kind) {
          case "HEALTH" ->
              "select * from"
                  + " agentos_evidence_private.begin_health(?::uuid,?::uuid,?::text,?::bigint)";
          case "PREPARE" ->
              "select * from"
                  + " agentos_evidence_private.begin_preparation_assertion(?::uuid,?::uuid,?::text,?::bigint)";
          case "INTEGRITY_FAILURE" ->
              "select * from"
                  + " agentos_evidence_private.begin_integrity_verification(?::uuid,?::uuid,?::text,?::bigint)";
          default -> throw new IllegalArgumentException("Unknown fixture capture kind");
        };
    try (var worker = actor(name)) {
      worker.setAutoCommit(false);
      worker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try (var statement = worker.prepareStatement(sql)) {
        statement.setObject(1, scope.organizationId());
        statement.setObject(2, scope.connectionId());
        statement.setString(3, scope.provider());
        statement.setLong(4, scope.revision());
        try (var rows = statement.executeQuery()) {
          if (!rows.next()) return "NO_ENVELOPE";
          try {
            var status = ProviderPublicationClient.Status.valueOf(rows.getString("status"));
            var reason = ProviderPublicationClient.Reason.valueOf(rows.getString("reason"));
            return status.name() + "/" + reason.name();
          } catch (IllegalArgumentException | NullPointerException malformed) {
            return "INVALID_ENVELOPE";
          }
        }
      } catch (SQLException failure) {
        return safeSqlFailure("Capture SQL failure", failure).getMessage();
      } finally {
        try {
          worker.rollback();
        } catch (SQLException failure) {
          throw safeSqlFailure("Capture diagnostic rollback failed", failure);
        }
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Capture diagnostic close failed", failure);
    }
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
      throw safeSqlFailure("Publication fixture update failed", failure);
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
    try (var connection = admin(databaseUrl)) {
      return fingerprint(connection);
    }
  }

  private String fingerprint(Connection connection) throws Exception {
    return fingerprint(connection, true);
  }

  private String fingerprintWithoutAttempts() throws Exception {
    try (var connection = admin(databaseUrl)) {
      return fingerprint(connection, false);
    }
  }

  private String fingerprint(Connection connection, boolean includeAttempts) throws Exception {
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
      if (!includeAttempts && table.equals("agentos_evidence_private.publication_attempts"))
        continue;
      digest.update(table.getBytes(StandardCharsets.UTF_8));
      try (var statement = connection.createStatement();
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
  void authorityAndForeignConnectionScopeDenialsPreserveAllRows() throws Exception {
    var scope = seed();
    var other = seed();
    var foreignConnection =
        new ProviderPublicationClient.Scope(
            other.organizationId(), scope.connectionId(), "OPENAI", 1);
    var missingOrganization =
        new ProviderPublicationClient.Scope(UUID.randomUUID(), scope.connectionId(), "OPENAI", 1);
    var wrongProvider =
        new ProviderPublicationClient.Scope(
            scope.organizationId(), scope.connectionId(), "ANTHROPIC", 1);
    for (var request : List.of(foreignConnection, missingOrganization, wrongProvider)) {
      String before = fingerprint();
      var result = client("health").beginHealth(request);
      assertThat(result.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(result.reason())
          .isEqualTo(
              request.equals(foreignConnection)
                  ? ProviderPublicationClient.Reason.BINDING
                  : ProviderPublicationClient.Reason.AUTHORITY);
      assertThat(result.value()).isNull();
      assertThat(fingerprint()).isEqualTo(before);
    }
  }

  @Test
  void disablingRegistrationOrRemovingPermissionInvalidatesCommittedCaptures() throws Exception {
    for (String mode : List.of("DISABLE", "REMOVE_PERMISSION")) {
      var scope = seed();
      var capture = checkedCapture("health", scope, "HEALTH").value();
      if (mode.equals("DISABLE")) {
        update(
            "update agentos_evidence_private.publisher_principals set enabled=false"
                + " where organization_id=? and login_name=?",
            scope.organizationId(),
            login("health"));
      } else {
        update(
            "delete from agentos_evidence_private.publisher_permissions where kind='HEALTH'"
                + " and publisher_id in (select publisher_id from"
                + " agentos_evidence_private.publisher_principals"
                + " where organization_id=? and login_name=?)",
            scope.organizationId(),
            login("health"));
      }
      String before = fingerprint();
      var rejectedCapture = client("health").beginHealth(scope);
      assertThat(rejectedCapture.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(rejectedCapture.reason()).isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
      assertThat(rejectedCapture.value()).isNull();
      var rejectedPublication =
          client("health")
              .publishHealth(
                  capture.attemptId(),
                  ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                  false,
                  null);
      assertThat(rejectedPublication.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(rejectedPublication.reason())
          .isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
      assertThat(rejectedPublication.value()).isNull();
      assertThat(fingerprint()).isEqualTo(before);
    }
  }

  @Test
  void eachMetadataAndVerifierSubstitutionInvalidatesCapturedPublicationWithoutWrites()
      throws Exception {
    for (String field :
        List.of("external_locator", "external_key_version", "hmac_key_version", "verifier")) {
      var scope = seed();
      var capture = checkedCapture("health", scope, "HEALTH").value();
      if (field.equals("verifier")) {
        byte[] replacement = new byte[32];
        java.util.Arrays.fill(replacement, (byte) 1);
        update(
            "update public.provider_credential_versions set verifier=?"
                + " where organization_id=? and connection_id=?",
            java.util.Base64.getEncoder().encodeToString(replacement),
            scope.organizationId(),
            scope.connectionId());
      } else {
        replaceBindingMetadata(scope, field, "substituted-v2");
      }
      // Privileged corruption is a controlled fixture, not an allowed worker mutation path.
      String before = fingerprint();
      var result =
          client("health")
              .publishHealth(
                  capture.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
      assertThat(result.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(result.reason()).isEqualTo(ProviderPublicationClient.Reason.BINDING);
      assertThat(result.value()).isNull();
      assertThat(fingerprint()).isEqualTo(before);
    }
  }

  @Test
  void registryFenceChangeDuringObservedPublicationWaitRejectsWithoutAnyPublicationWrites()
      throws Exception {
    var scope = seed();
    var capture = checkedCapture("health", scope, "HEALTH").value();
    var executor = Executors.newSingleThreadExecutor();
    try (var operator = admin(databaseUrl)) {
      operator.setAutoCommit(false);
      int blocker;
      try (var statement = operator.createStatement();
          var rows = statement.executeQuery("select pg_backend_pid()")) {
        assertThat(rows.next()).isTrue();
        blocker = rows.getInt(1);
      }
      try (var statement =
          operator.prepareStatement(
              "select publisher_id from public.provider_evidence_publishers"
                  + " where publisher_id in (select publisher_id from"
                  + " agentos_evidence_private.publisher_principals"
                  + " where organization_id=? and login_name=?) for update")) {
        statement.setObject(1, scope.organizationId());
        statement.setString(2, login("health"));
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.next()).isFalse();
        }
      }
      var publication =
          executor.submit(
              () ->
                  client("health")
                      .publishHealth(
                          capture.attemptId(),
                          ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                          false,
                          null));
      awaitWorkerBlockedBy("health", blocker);
      try (var statement =
          operator.prepareStatement(
              "update public.provider_evidence_publishers set publisher_epoch=publisher_epoch+1,"
                  + " publisher_fence=gen_random_uuid() where publisher_id in"
                  + " (select publisher_id from agentos_evidence_private.publisher_principals"
                  + " where organization_id=? and login_name=?)")) {
        statement.setObject(1, scope.organizationId());
        statement.setString(2, login("health"));
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      // See our intended fence update while the worker is provably blocked. A baseline taken
      // only after releasing the lock could accidentally include a forbidden worker write.
      String expected = fingerprint(operator);
      operator.commit();
      var result = publication.get(10, TimeUnit.SECONDS);
      assertThat(result.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(result.reason()).isEqualTo(ProviderPublicationClient.Reason.AUTHORITY);
      assertThat(result.value()).isNull();
      assertThat(fingerprint()).isEqualTo(expected);
    } finally {
      executor.shutdownNow();
    }
  }

  private void awaitWorkerBlockedBy(String name, int blocker) throws Exception {
    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < timeout) {
      try (var connection = admin(databaseUrl);
          var statement =
              connection.prepareStatement(
                  "select count(*) from pg_catalog.pg_stat_activity where usename=? and"
                      + " wait_event_type='Lock' and ?=any(pg_catalog.pg_blocking_pids(pid))")) {
        statement.setString(1, login(name));
        statement.setInt(2, blocker);
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          if (rows.getLong(1) == 1) return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected a worker lock wait blocked by the fixture operator");
  }

  @Test
  void injectedEvidenceAssertionAndProvenanceFailuresRollBackAllPublicationRows() throws Exception {
    for (String table :
        List.of(
            "public.provider_credential_integrity",
            "public.provider_credential_health",
            "agentos_evidence_private.integrity_assertions",
            "agentos_evidence_private.integrity_provenance")) {
      var scope = seed();
      boolean health = table.equals("public.provider_credential_health");
      String actor = health ? "health" : "prepare";
      String kind = health ? "HEALTH" : "PREPARE";
      var capture = checkedCapture(actor, scope, kind).value();
      try (var operator = admin(databaseUrl);
          var statement = operator.createStatement()) {
        statement.execute(
            "create function agentos_evidence_private.reject_publication_fixture()"
                + " returns trigger language plpgsql as $fixture$ begin"
                + " raise exception using errcode='P0001',"
                + " message='Controlled publication fixture rejection'; end; $fixture$");
        try {
          statement.execute(
              "create trigger reject_publication_fixture before insert on "
                  + table
                  + " for each row execute function"
                  + " agentos_evidence_private.reject_publication_fixture()");
          String before = fingerprint();
          var rejected =
              health
                  ? client(actor)
                      .publishHealth(
                          capture.attemptId(),
                          ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                          false,
                          null)
                  : client(actor).assertPrepared(capture.attemptId());
          assertThat(rejected.status()).isEqualTo(ProviderPublicationClient.Status.FAILED);
          assertThat(rejected.reason())
              .isEqualTo(ProviderPublicationClient.Reason.DEPENDENCY_UNAVAILABLE);
          assertThat(rejected.value()).isNull();
          assertThat(fingerprint()).isEqualTo(before);
        } finally {
          statement.execute("drop trigger if exists reject_publication_fixture on " + table);
          statement.execute("drop function agentos_evidence_private.reject_publication_fixture()");
        }
      } catch (SQLException failure) {
        throw safeSqlFailure("Atomic rollback fixture failed", failure);
      }
      // A new independent controlled observation succeeds after removing the injected fault.
      var fresh = checkedCapture(actor, scope, kind).value();
      var accepted =
          health
              ? client(actor)
                  .publishHealth(
                      fresh.attemptId(),
                      ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                      false,
                      null)
              : client(actor).assertPrepared(fresh.attemptId());
      assertThat(accepted.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    }
  }

  @Test
  void integrityUpdateAndPostConsumptionFaultsRestoreVerifiedHistoryExactly() throws Exception {
    for (var fault :
        List.of(
            new MutationFault("public.provider_credential_integrity", "update", false),
            new MutationFault("agentos_evidence_private.integrity_assertions", "insert", false),
            new MutationFault("agentos_evidence_private.integrity_provenance", "insert", false),
            new MutationFault("agentos_evidence_private.publication_attempts", "update", true))) {
      var scope = seed();
      var prepared = checkedCapture("prepare", scope, "PREPARE").value();
      assertThat(client("prepare").assertPrepared(prepared.attemptId()).status())
          .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      var health = checkedCapture("health", scope, "HEALTH").value();
      assertThat(
              client("health")
                  .publishHealth(
                      health.attemptId(),
                      ProviderPublicationClient.HealthOutcome.USABLE,
                      false,
                      null)
                  .status())
          .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
      assertMutationRollback(
          fault, () -> client("health").publishIntegrityFailure(failure.attemptId()));

      var fresh = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
      var accepted = client("health").publishIntegrityFailure(fresh.attemptId());
      assertThat(accepted.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      assertThat(accepted.value().state()).isEqualTo("FAILED");
      assertThat(accepted.value().publicationRevision()).isEqualTo(2);
      assertThat(
              count(
                  "select count(*) from public.provider_credential_health where organization_id='"
                      + scope.organizationId()
                      + "' and publication_revision=1 and state='USABLE'"))
          .isEqualTo(1);
    }
  }

  @Test
  void healthUpdateAndPostConsumptionFaultsRestorePreviousObservationExactly() throws Exception {
    for (var fault :
        List.of(
            new MutationFault("public.provider_credential_health", "update", false),
            new MutationFault("agentos_evidence_private.publication_attempts", "update", true))) {
      var scope = seed();
      var first = checkedCapture("health", scope, "HEALTH").value();
      assertThat(
              client("health")
                  .publishHealth(
                      first.attemptId(),
                      ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                      false,
                      null)
                  .status())
          .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      var next = checkedCapture("health", scope, "HEALTH").value();
      assertMutationRollback(
          fault,
          () ->
              client("health")
                  .publishHealth(
                      next.attemptId(),
                      ProviderPublicationClient.HealthOutcome.USABLE,
                      false,
                      null));
      var fresh = checkedCapture("health", scope, "HEALTH").value();
      var accepted =
          client("health")
              .publishHealth(
                  fresh.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
      assertThat(accepted.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      assertThat(accepted.value().publicationRevision()).isEqualTo(2);
      assertThat(accepted.value().state()).isEqualTo("USABLE");
    }
  }

  @Test
  void firstEvidencePostConsumptionFaultsRestoreAbsenceForEveryPublicationKind() throws Exception {
    var fault = new MutationFault("agentos_evidence_private.publication_attempts", "update", true);
    for (String kind : List.of("PREPARE", "HEALTH", "INTEGRITY_FAILURE")) {
      var scope = seed();
      String actor = kind.equals("PREPARE") ? "prepare" : "health";
      Function<UUID, ProviderPublicationClient.Result<ProviderPublicationClient.Publication>>
          publish =
              attempt ->
                  switch (kind) {
                    case "PREPARE" -> client(actor).assertPrepared(attempt);
                    case "HEALTH" ->
                        client(actor)
                            .publishHealth(
                                attempt,
                                ProviderPublicationClient.HealthOutcome.USABLE,
                                false,
                                null);
                    case "INTEGRITY_FAILURE" -> client(actor).publishIntegrityFailure(attempt);
                    default ->
                        throw new IllegalArgumentException("Unknown fixture publication kind");
                  };
      var captured = checkedCapture(actor, scope, kind).value();
      assertMutationRollback(fault, () -> publish.apply(captured.attemptId()));
      var fresh = checkedCapture(actor, scope, kind).value();
      var accepted = publish.apply(fresh.attemptId());
      String expectedState =
          switch (kind) {
            case "PREPARE" -> "VERIFIED_AT_WRITE";
            case "HEALTH" -> "USABLE";
            case "INTEGRITY_FAILURE" -> "FAILED";
            default -> throw new IllegalArgumentException("Unknown fixture publication kind");
          };
      assertThat(accepted.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      assertThat(accepted.value().publicationRevision()).isEqualTo(1);
      assertThat(accepted.value().state()).isEqualTo(expectedState);
      if (!kind.equals("HEALTH")) {
        assertThat(currentIntegrityPinCount(fresh.attemptId(), expectedState, 1)).isEqualTo(1);
        assertThat(pinnedFingerprint(fresh.attemptId())).isNotEmpty();
      }
    }
  }

  private record MutationFault(String table, String operation, boolean consumptionOnly) {}

  private void assertMutationRollback(
      MutationFault fault,
      Supplier<ProviderPublicationClient.Result<ProviderPublicationClient.Publication>> publication)
      throws Exception {
    try (var operator = admin(databaseUrl);
        var statement = operator.createStatement()) {
      statement.execute("create sequence agentos_evidence_private.fixture_fault_witness start 1");
      try {
        // This test-only sequence is nontransactional. A pulse proves the AFTER trigger actually
        // ran at the requested phase, rather than an earlier guard merely rejecting the call.
        // Only the existing definer gets USAGE; publisher LOGIN privileges are unchanged.
        statement.execute(
            "grant usage on sequence agentos_evidence_private.fixture_fault_witness to " + DEFINER);
        statement.execute(
            "create function agentos_evidence_private.fixture_mutation_fault() returns trigger"
                + " language plpgsql as $fixture$ begin perform pg_catalog.nextval("
                + "'agentos_evidence_private.fixture_fault_witness'::pg_catalog.regclass); raise"
                + " exception using"
                + " errcode='P0001',message='PRIVATE_NATIVE_PUBLICATION_FAULT_SENTINEL'; end;"
                + " $fixture$");
        statement.execute(
            "create trigger fixture_mutation_fault after "
                + fault.operation()
                + " on "
                + fault.table()
                + " for each row "
                + (fault.consumptionOnly() ? "when (new.consumed and not old.consumed) " : "")
                + "execute function agentos_evidence_private.fixture_mutation_fault()");
        String before = fingerprint();
        var output = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;
        ProviderPublicationClient.Result<ProviderPublicationClient.Publication> rejected;
        try (var captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
          System.setOut(captured);
          System.setErr(captured);
          rejected = publication.get();
        } finally {
          System.setOut(originalOut);
          System.setErr(originalErr);
        }
        assertThat(
                output
                    .toString(StandardCharsets.UTF_8)
                    .contains("PRIVATE_NATIVE_PUBLICATION_FAULT_SENTINEL"))
            .isFalse();
        assertThat(rejected.toString().contains("PRIVATE_NATIVE_PUBLICATION_FAULT_SENTINEL"))
            .isFalse();
        assertThat(rejected.status()).isEqualTo(ProviderPublicationClient.Status.FAILED);
        assertThat(rejected.reason())
            .isEqualTo(ProviderPublicationClient.Reason.DEPENDENCY_UNAVAILABLE);
        assertThat(rejected.value()).isNull();
        assertThat(
                count(
                    "select case when is_called then last_value else 0 end from"
                        + " agentos_evidence_private.fixture_fault_witness"))
            .isEqualTo(1);
        assertThat(fingerprint()).isEqualTo(before);
      } finally {
        statement.execute("drop trigger if exists fixture_mutation_fault on " + fault.table());
        statement.execute(
            "drop function if exists agentos_evidence_private.fixture_mutation_fault()");
        statement.execute("drop sequence agentos_evidence_private.fixture_fault_witness");
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Mutation rollback fixture failed", failure);
    }
  }

  @Test
  void cleanupBoundsEligibleRowsAndPreservesBeforeGraceAndOtherOrganizationExactly()
      throws Exception {
    var scope = seed();
    var other = seed();
    var first = checkedCapture("health", scope, "HEALTH").value();
    var second = checkedCapture("health", scope, "HEALTH").value();
    var beforeGrace = checkedCapture("health", scope, "HEALTH").value();
    var foreign = checkedCapture("health", other, "HEALTH").value();
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=statement_timestamp()-interval '7 minutes',"
            + " deadline=statement_timestamp()-interval '6 minutes'"
            + " where attempt_id in (?,?,?)",
        first.attemptId(),
        second.attemptId(),
        foreign.attemptId());
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=statement_timestamp()-interval '4 minutes',"
            + " deadline=statement_timestamp()-interval '3 minutes' where attempt_id=?",
        beforeGrace.attemptId());
    String beforeGraceRows = attemptFingerprint(beforeGrace.attemptId());
    String foreignRows = attemptFingerprint(foreign.attemptId());
    String durableRows = fingerprintWithoutAttempts();
    try (var operator = admin(databaseUrl)) {
      assertThat(cleanupAttempts(operator, scope.organizationId(), 1)).isEqualTo(1);
      assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
          .isEqualTo(3);
      assertThat(
              count(
                  "select count(*) from agentos_evidence_private.publication_attempts where"
                      + " attempt_id in ('"
                      + first.attemptId()
                      + "','"
                      + second.attemptId()
                      + "')"))
          .isEqualTo(1);
      assertThat(attemptFingerprint(beforeGrace.attemptId())).isEqualTo(beforeGraceRows);
      assertThat(attemptFingerprint(foreign.attemptId())).isEqualTo(foreignRows);
      assertThat(fingerprintWithoutAttempts()).isEqualTo(durableRows);
      assertThat(cleanupAttempts(operator, scope.organizationId(), 4096)).isEqualTo(1);
      assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
          .isEqualTo(2);
      assertThat(attemptFingerprint(beforeGrace.attemptId())).isEqualTo(beforeGraceRows);
      assertThat(attemptFingerprint(foreign.attemptId())).isEqualTo(foreignRows);
      assertThat(fingerprintWithoutAttempts()).isEqualTo(durableRows);
    }
  }

  @Test
  void cleanupSamplesGraceAfterObservedOrganizationLockWait() throws Exception {
    var scope = seed();
    var capture = checkedCapture("health", scope, "HEALTH").value();
    var executor = Executors.newSingleThreadExecutor();
    try (var operator = admin(databaseUrl);
        var cleaner = admin(databaseUrl)) {
      int blocker = backendPid(operator);
      int waiter = backendPid(cleaner);
      operator.setAutoCommit(false);
      try (var statement =
          operator.prepareStatement("select id from public.organizations where id=? for update")) {
        statement.setObject(1, scope.organizationId());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
      var cleanup = executor.submit(() -> cleanupAttempts(cleaner, scope.organizationId(), 10));
      awaitBackendBlockedBy(waiter, blocker);
      // Set a near grace boundary only after the actual wait is established. A clock captured
      // before the wait cannot observe eligibility, while the post-lock clock must observe it.
      try (var statement =
          operator.prepareStatement(
              "update agentos_evidence_private.publication_attempts set"
                  + " observed_at=statement_timestamp()-interval '6 minutes'+interval '1 second',"
                  + " deadline=statement_timestamp()-interval '5 minutes'+interval '1 second'"
                  + " where attempt_id=?")) {
        statement.setObject(1, capture.attemptId());
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean eligible = false;
      while (!eligible && System.nanoTime() < timeout) {
        try (var statement =
            operator.prepareStatement(
                "select clock_timestamp()>=deadline+interval '5 minutes' from"
                    + " agentos_evidence_private.publication_attempts where attempt_id=?")) {
          statement.setObject(1, capture.attemptId());
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            eligible = rows.getBoolean(1);
          }
        }
        if (!eligible) Thread.sleep(10);
      }
      assertThat(eligible).isTrue();
      String expectedDurableRows = fingerprint(operator, false);
      operator.commit();
      assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
          .isZero();
      assertThat(fingerprintWithoutAttempts()).isEqualTo(expectedDurableRows);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cleanupWaitsForPublishingFailureAndRetainsBothPinnedTransitions() throws Exception {
    var scope = seed();
    var prepared = checkedCapture("prepare", scope, "PREPARE").value();
    assertThat(client("prepare").assertPrepared(prepared.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(currentIntegrityPinCount(prepared.attemptId(), "VERIFIED_AT_WRITE", 1)).isEqualTo(1);
    var operational = checkedCapture("health", scope, "HEALTH").value();
    // Controlled temporal fixtures model old committed history without a six-minute test sleep.
    // Keep the assertion's capture/acceptance and public recording clocks aligned with that age.
    update(
        "update agentos_evidence_private.publication_attempts set"
            + " observed_at=observed_at-interval '7 minutes',deadline=deadline-interval '7 minutes'"
            + " where attempt_id in (?,?)",
        prepared.attemptId(),
        operational.attemptId());
    update(
        "update agentos_evidence_private.integrity_assertions set captured_at=captured_at-interval"
            + " '7 minutes',accepted_at=accepted_at-interval '7 minutes' where attempt_id=?",
        prepared.attemptId());
    update(
        "update public.provider_credential_integrity set recorded_at=recorded_at-interval '7"
            + " minutes' where organization_id=? and connection_id=?",
        scope.organizationId(),
        scope.connectionId());
    String originalPin = pinnedFingerprint(prepared.attemptId());
    var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
    var executor = Executors.newSingleThreadExecutor();
    try (var worker = actor("health");
        var cleaner = admin(databaseUrl)) {
      int blocker = backendPid(worker);
      int waiter = backendPid(cleaner);
      worker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      worker.setAutoCommit(false);
      try (var statement =
          worker.prepareStatement(
              "select * from agentos_evidence_private.publish_integrity_failure(?::uuid)")) {
        statement.setObject(1, failure.attemptId());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("status")).isEqualTo("ACCEPTED");
          assertThat(rows.getString("reason")).isEqualTo("NONE");
          assertThat(rows.getLong("publication_revision")).isEqualTo(2);
          assertThat(rows.getString("state")).isEqualTo("FAILED");
          assertThat(rows.next()).isFalse();
        }
      }
      // The SQL result is provisional. Retain the real publisher's organization mutex until
      // cleanup is observed blocked by that exact backend, then acknowledge the worker commit.
      var cleanup = executor.submit(() -> cleanupAttempts(cleaner, scope.organizationId(), 4096));
      awaitBackendBlockedBy(waiter, blocker);
      assertThat(cleanup.isDone()).isFalse();
      worker.commit();
      assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(pinnedFingerprint(prepared.attemptId())).isEqualTo(originalPin);
      assertThat(pinnedFingerprint(failure.attemptId())).isNotEmpty();
      assertThat(count("select count(*) from agentos_evidence_private.publication_attempts"))
          .isEqualTo(2);
      assertThat(count("select count(*) from agentos_evidence_private.integrity_assertions"))
          .isEqualTo(2);
      assertThat(count("select count(*) from agentos_evidence_private.integrity_provenance"))
          .isEqualTo(2);
      assertThat(
              count(
                  "select count(*) from public.provider_credential_integrity where"
                      + " state='FAILED' and publication_revision=2"))
          .isEqualTo(1);
      assertThat(currentIntegrityPinCount(failure.attemptId(), "FAILED", 2)).isEqualTo(1);
    } catch (SQLException failureException) {
      throw safeSqlFailure("Cleanup pin serialization fixture failed", failureException);
    } finally {
      executor.shutdownNow();
    }
  }

  private long cleanupAttempts(Connection connection, UUID organization, int batch)
      throws Exception {
    try (var statement =
        connection.prepareStatement(
            "select agentos_evidence_private.cleanup_attempts(?::uuid,?::integer)")) {
      statement.setQueryTimeout(15);
      statement.setObject(1, organization);
      statement.setInt(2, batch);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        long removed = rows.getLong(1);
        assertThat(rows.wasNull()).isFalse();
        assertThat(rows.next()).isFalse();
        return removed;
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Cleanup fixture execution failed", failure);
    }
  }

  private int backendPid(Connection connection) throws Exception {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("select pg_backend_pid()")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private void awaitBackendBlockedBy(int waiter, int blocker) throws Exception {
    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < timeout) {
      try (var connection = admin(databaseUrl);
          var statement =
              connection.prepareStatement(
                  "select count(*) from pg_catalog.pg_stat_activity where pid=? and"
                      + " wait_event_type='Lock' and ?=any(pg_catalog.pg_blocking_pids(pid))")) {
        statement.setInt(1, waiter);
        statement.setInt(2, blocker);
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          if (rows.getLong(1) == 1) return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected cleanup blocked by the exact fixture backend");
  }

  private String attemptFingerprint(UUID attempt) throws Exception {
    return projectionFingerprint(
        "select pg_catalog.row_to_json(a)::text from"
            + " agentos_evidence_private.publication_attempts a where a.attempt_id=?",
        attempt);
  }

  private long currentIntegrityPinCount(UUID attempt, String state, long revision)
      throws Exception {
    try (var operator = admin(databaseUrl);
        var statement =
            operator.prepareStatement(
                "select count(*) from public.provider_credential_integrity i join"
                    + " agentos_evidence_private.integrity_provenance q using"
                    + " (organization_id,connection_id,provider,credential_revision,publication_revision)"
                    + " join agentos_evidence_private.integrity_assertions s on"
                    + " s.assertion_id=q.assertion_id where s.attempt_id=? and i.state=? and"
                    + " i.publication_revision=?")) {
      statement.setObject(1, attempt);
      statement.setString(2, state);
      statement.setLong(3, revision);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private String pinnedFingerprint(UUID attempt) throws Exception {
    return projectionFingerprint(
        "select pg_catalog.row_to_json(t)::text from (select a,s,q from"
            + " agentos_evidence_private.publication_attempts a join"
            + " agentos_evidence_private.integrity_assertions s on s.attempt_id=a.attempt_id join"
            + " agentos_evidence_private.integrity_provenance q on q.assertion_id=s.assertion_id"
            + " where a.attempt_id=? and a.consumed and a.kind=s.kind and s.protocol_version=1 and"
            + " row(q.organization_id,q.connection_id,q.provider,q.credential_revision)="
            + " row(s.organization_id,s.connection_id,s.provider,s.credential_revision) and"
            + " row(s.organization_id,s.connection_id,s.provider,s.credential_revision,"
            + " s.external_locator,s.external_key_version,s.hmac_key_version,s.hmac_domain_version,"
            + " s.publisher_id,s.publisher_epoch,s.publisher_fence,s.login_name,s.login_oid,s.captured_at)="
            + " row(a.organization_id,a.connection_id,a.provider,a.credential_revision,"
            + " a.external_locator,a.external_key_version,a.hmac_key_version,a.hmac_domain_version,"
            + " a.publisher_id,a.publisher_epoch,a.publisher_fence,a.login_name,a.login_oid,a.observed_at))"
            + " t",
        attempt);
  }

  private String projectionFingerprint(String sql, UUID attempt) throws Exception {
    try (var operator = admin(databaseUrl);
        var statement = operator.prepareStatement(sql)) {
      statement.setObject(1, attempt);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(rows.getString(1).getBytes(StandardCharsets.UTF_8));
        assertThat(rows.next()).isFalse();
        return java.util.HexFormat.of().formatHex(digest.digest());
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Private fixture projection failed", failure);
    }
  }

  @Test
  void publisherTemporaryTablesCannotShadowAuthorityOrPublicationRows() throws Exception {
    var scope = seed();
    try (var worker = actor("health");
        var setup = worker.createStatement()) {
      setup.execute("create temp table publisher_principals (publisher_id uuid)");
      setup.execute("create temp table organizations (id uuid)");
      setup.execute("create temp table publication_attempts (attempt_id uuid)");
      setup.execute("set search_path to pg_temp, public, pg_catalog");
      UUID attempt;
      try (var capture =
          worker.prepareStatement(
              "select status,attempt_id from agentos_evidence_private.begin_health(?,?,?,?)")) {
        capture.setObject(1, scope.organizationId());
        capture.setObject(2, scope.connectionId());
        capture.setString(3, "OPENAI");
        capture.setLong(4, 1);
        try (var rows = capture.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("ACCEPTED");
          attempt = rows.getObject(2, UUID.class);
        }
      }
      try (var publish =
          worker.prepareStatement(
              "select status from"
                  + " agentos_evidence_private.publish_health(?,'USABLE',false,null)")) {
        publish.setObject(1, attempt);
        try (var rows = publish.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("ACCEPTED");
        }
      }
      try (var rows = setup.executeQuery("select count(*) from pg_temp.publication_attempts")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isZero();
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Temporary shadow fixture failed", failure);
    }
    assertThat(count("select count(*) from public.provider_credential_health")).isEqualTo(1);
  }

  @Test
  void futureDefinerFunctionDoesNotInheritPublicOrPublisherExecute() throws Exception {
    try (var operator = admin(databaseUrl);
        var statement = operator.createStatement()) {
      operator.setAutoCommit(false);
      try {
        statement.execute("set local role " + DEFINER);
        statement.execute(
            "create function agentos_evidence_private.fixture_default_acl()"
                + " returns integer language sql as 'select 1'");
        try (var rows =
            statement.executeQuery(
                "select count(*) from pg_catalog.pg_proc p"
                    + " cross join lateral pg_catalog.aclexplode(coalesce(p.proacl,"
                    + " pg_catalog.acldefault('f',p.proowner))) a"
                    + " where p.oid='agentos_evidence_private.fixture_default_acl()'::regprocedure"
                    + " and a.grantee=0 and a.privilege_type='EXECUTE'")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isZero();
        }
        try (var rows =
            statement.executeQuery(
                "select pg_catalog.has_function_privilege('"
                    + PUBLISHER
                    + "','agentos_evidence_private.fixture_default_acl()','EXECUTE')")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getBoolean(1)).isFalse();
        }
      } finally {
        operator.rollback();
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Default function ACL fixture failed", failure);
    }
  }

  @Test
  void actualInstallerExposesSixFunctionsAndDefinerHasSelectOnlyVersions() throws Exception {
    var publisherFunctions = new ArrayList<String>();
    var internalFunctions = new ArrayList<String>();
    try (var operator = admin(databaseUrl);
        var statement =
            operator.prepareStatement(
                "select p.proname || '(' || pg_catalog.oidvectortypes(p.proargtypes) || ')',"
                    + " pg_catalog.has_function_privilege(?::name,p.oid,'EXECUTE'),"
                    + " pg_catalog.pg_get_userbyid(p.proowner), p.prosecdef,"
                    + " exists(select 1 from pg_catalog.aclexplode(coalesce(p.proacl,"
                    + " pg_catalog.acldefault('f',p.proowner))) a where a.grantee=0)"
                    + " from pg_catalog.pg_proc p join pg_catalog.pg_namespace n"
                    + " on n.oid=p.pronamespace where n.nspname='agentos_evidence_private'")) {
      statement.setString(1, PUBLISHER);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          String signature = rows.getString(1);
          assertThat(rows.getString(3)).as("Owner of %s", signature).isEqualTo(DEFINER);
          assertThat(rows.getBoolean(4))
              .as("Security definer flag of %s", signature)
              .isEqualTo(!signature.startsWith("valid_binding("));
          assertThat(rows.getBoolean(5)).as("PUBLIC ACL of %s", signature).isFalse();
          (rows.getBoolean(2) ? publisherFunctions : internalFunctions).add(signature);
        }
      }
    }
    assertThat(publisherFunctions)
        .containsExactlyInAnyOrder(
            "begin_health(uuid, uuid, text, bigint)",
            "begin_preparation_assertion(uuid, uuid, text, bigint)",
            "begin_integrity_verification(uuid, uuid, text, bigint)",
            "publish_health(uuid, text, boolean, timestamp with time zone)",
            "assert_prepared(uuid)",
            "publish_integrity_failure(uuid)");
    assertThat(internalFunctions)
        .containsExactlyInAnyOrder(
            "authority(uuid, text, text)",
            "valid_binding(text, text, text, text, text)",
            "capture_attempt(text, uuid, uuid, text, bigint)",
            "publish_attempt(text, uuid, text, boolean, timestamp with time zone)",
            "cleanup_attempts(uuid, integer)");
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
  void realLoginCapturesEachBindingFieldAtOne255And256Characters() throws Exception {
    for (String column : List.of("external_locator", "external_key_version", "hmac_key_version")) {
      for (int length : List.of(1, 255, 256)) {
        var scope = seed();
        // Exercise the complete allowed alphabet, including the literal trailing hyphen.
        String alphabet = "Az09_./:@-";
        String value = alphabet.repeat(29).substring(0, length);
        replaceBindingMetadata(scope, column, value);
        var expected =
            new ProviderPublicationClient.Binding(
                scope,
                column.equals("external_locator") ? value : "ref",
                column.equals("external_key_version") ? value : "external-v1",
                column.equals("hmac_key_version") ? value : "hmac-v1",
                "v2");
        for (String kind : List.of("HEALTH", "PREPARE", "INTEGRITY_FAILURE")) {
          var captured = checkedCapture(kind.equals("PREPARE") ? "prepare" : "health", scope, kind);
          assertThat(captured.status())
              .as("%s length %s / %s", column, length, kind)
              .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
          assertThat(captured.value().binding()).isEqualTo(expected);
        }
      }
    }
  }

  @Test
  void realLoginRejectsEachEmptyOrInvalidBindingFieldWithoutAnyWrites() throws Exception {
    for (String column : List.of("external_locator", "external_key_version", "hmac_key_version")) {
      for (String value : List.of("", "invalid!")) {
        var scope = seed();
        replaceBindingMetadata(scope, column, value);
        for (String kind : List.of("HEALTH", "PREPARE", "INTEGRITY_FAILURE")) {
          String before = fingerprint();
          var publisher = client(kind.equals("PREPARE") ? "prepare" : "health");
          var rejected =
              switch (kind) {
                case "HEALTH" -> publisher.beginHealth(scope);
                case "PREPARE" -> publisher.beginPreparationAssertion(scope);
                case "INTEGRITY_FAILURE" -> publisher.beginIntegrityVerification(scope);
                default -> throw new IllegalArgumentException("Unknown fixture capture kind");
              };
          assertThat(rejected.status())
              .as("%s length %s / %s", column, value.length(), kind)
              .isEqualTo(ProviderPublicationClient.Status.REJECTED);
          assertThat(rejected.reason()).isEqualTo(ProviderPublicationClient.Reason.BINDING);
          assertThat(rejected.value()).isNull();
          assertThat(fingerprint()).isEqualTo(before);
        }
      }
    }
  }

  @Test
  void storageRejectsEach257CharacterBindingFieldWithoutAnyWrites() throws Exception {
    var scope = seed();
    // V022 varchar(256) rejects this fixture before capture can run. Preserve that real
    // guard: do not widen columns or grant publishers access to the private validation helper.
    for (String column : List.of("external_locator", "external_key_version", "hmac_key_version")) {
      String before = fingerprint();
      try {
        replaceBindingMetadata(scope, column, "A".repeat(257));
        throw new AssertionError("Storage must reject a 257-character binding field");
      } catch (SQLException expected) {
        assertThat(expected.getSQLState()).as("%s storage bound", column).isEqualTo("22001");
      }
      assertThat(fingerprint()).isEqualTo(before);
    }
  }

  /** Operator-only fixture mutation before capture; publishers still use the unchanged protocol. */
  private void replaceBindingMetadata(
      ProviderPublicationClient.Scope scope, String column, String value) throws Exception {
    if (!List.of("external_locator", "external_key_version", "hmac_key_version").contains(column)) {
      throw new IllegalArgumentException("Unknown fixture binding column");
    }
    try (var operator = admin(databaseUrl);
        var statement =
            operator.prepareStatement(
                "update public.provider_credential_versions set "
                    + column
                    + "=?"
                    + " where organization_id=? and connection_id=? and provider=?"
                    + " and credential_revision=?")) {
      statement.setString(1, value);
      statement.setObject(2, scope.organizationId());
      statement.setObject(3, scope.connectionId());
      statement.setString(4, scope.provider());
      statement.setLong(5, scope.revision());
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  @Test
  void providerListHttpReadsRealRowsThroughRestrictedRuntimeLogin() throws Exception {
    var scope = seed();
    UUID account = UUID.randomUUID();
    String email = account + "@example.test";
    String humanPassword = UUID.randomUUID().toString();
    update(
        "insert into public.human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'List fixture',?)",
        account,
        email,
        new BCryptPasswordEncoder(12).encode(humanPassword));
    update(
        "insert into public.organization_memberships(account_id,organization_id,role)"
            + " values (?,?,'ADMIN')",
        account,
        scope.organizationId());
    String role = login("web");
    try (var operator = admin(databaseUrl);
        var statement = operator.createStatement()) {
      statement.execute(
          "grant select on public.organizations,public.organization_memberships,"
              + "public.provider_connections,public.human_accounts to "
              + role);
      statement.execute("grant update(id) on public.human_accounts to " + role);
      statement.execute(
          "grant select,insert,update on public.authentication_attempts,"
              + "public.provider_read_admission to "
              + role);
      statement.execute("grant insert on public.authentication_audit to " + role);
      statement.execute(
          "grant select,insert,update,delete on public.spring_session,"
              + "public.spring_session_attributes to "
              + role);
    }
    try (var application =
        new SpringApplicationBuilder(AgentOsApplication.class)
            .logStartupInfo(false)
            .run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--server.servlet.session.cookie.secure=false",
                "--spring.main.banner-mode=off",
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.datasource.url=" + databaseUrl,
                "--spring.datasource.username=" + role,
                "--spring.datasource.password=" + password("web"),
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--agentos.provider-list.enabled=true")) {
      int port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
      String root = "http://127.0.0.1:" + port;
      String path = "/api/v1/organizations/" + scope.organizationId() + "/provider-connections";
      var http =
          HttpClient.newBuilder()
              .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
              .connectTimeout(Duration.ofSeconds(10))
              .build();
      var json = new ObjectMapper();
      assertThat(listHttp(http, root + path + "?page=00", "GET").statusCode()).isEqualTo(401);
      var csrf = listHttp(http, root + "/api/v1/auth/csrf", "GET");
      assertThat(csrf.statusCode()).isEqualTo(200);
      var token = json.readTree(csrf.body()).path("data");
      var signIn =
          HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
              .timeout(Duration.ofSeconds(20))
              .header(token.path("headerName").asText(), token.path("token").asText())
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "username="
                          + URLEncoder.encode(email, StandardCharsets.UTF_8)
                          + "&password="
                          + humanPassword))
              .build();
      var loginResponse = http.send(signIn, HttpResponse.BodyHandlers.discarding());
      assertThat(loginResponse.statusCode()).isEqualTo(200);
      assertThat(listHttp(http, root + path + "?page=00", "GET").statusCode()).isEqualTo(400);
      assertThat(listHttp(http, root + path, "HEAD").statusCode()).isEqualTo(405);
      var initial = listHttp(http, root + path, "GET");
      assertThat(initial.statusCode()).isEqualTo(200);
      assertThat(initial.headers().firstValue("Cache-Control")).contains("no-store");
      var missing = json.readTree(initial.body()).at("/data/items/0");
      assertThat(missing.path("credentialState").asText()).isEqualTo("UNAVAILABLE");
      assertThat(missing.path("credentialMask").isNull()).isTrue();

      var preparation = checkedCapture("prepare", scope, "PREPARE").value();
      assertThat(client("prepare").assertPrepared(preparation.attemptId()).status())
          .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      var health = checkedCapture("health", scope, "HEALTH").value();
      var published =
          client("health")
              .publishHealth(
                  health.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
      assertThat(published.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      String before = fingerprint();
      var result = listHttp(http, root + path + "?page=0", "GET");
      assertThat(result.statusCode()).isEqualTo(200);
      var item = json.readTree(result.body()).at("/data/items/0");
      assertThat(item.path("credentialState").asText()).isEqualTo("PRESENT");
      assertThat(item.path("credentialMask").asText()).isEqualTo("••••••••");
      assertThat(item.size()).isEqualTo(13);
      assertThat(fingerprint()).isEqualTo(before);

      update(
          "update public.provider_evidence_publishers set publisher_epoch=publisher_epoch+1"
              + " where publisher_id=?",
          published.value().publisherId());
      var fenced = listHttp(http, root + path, "GET");
      assertThat(fenced.statusCode()).isEqualTo(200);
      assertThat(json.readTree(fenced.body()).at("/data/items/0/credentialState").asText())
          .isEqualTo("UNAVAILABLE");

      try (var operator = admin(databaseUrl);
          var statement = operator.createStatement()) {
        statement.execute(
            "grant select(verifier) on public.provider_credential_versions to " + role);
        try {
          assertThat(listHttp(http, root + path, "GET").statusCode()).isEqualTo(503);
        } finally {
          statement.execute(
              "revoke select(verifier) on public.provider_credential_versions from " + role);
        }
      }

      update(
          "update public.organization_memberships set role='MEMBER'"
              + " where account_id=? and organization_id=?",
          account,
          scope.organizationId());
      assertThat(listHttp(http, root + path + "?page=00", "GET").statusCode()).isEqualTo(403);
    }
  }

  private static HttpResponse<String> listHttp(HttpClient http, String url, String method)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void listEvidenceRequiresPreparedProvenanceFreshHealthAndCurrentFence() throws Exception {
    var scope = seed();
    var health = checkedCapture("health", scope, "HEALTH").value();
    var publication =
        client("health")
            .publishHealth(
                health.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
    assertThat(publication.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(listEvidence(scope)).isFalse();

    var preparation = checkedCapture("prepare", scope, "PREPARE").value();
    var prepared = client("prepare").assertPrepared(preparation.attemptId());
    assertThat(prepared.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(listEvidence(scope)).isTrue();
    assertThat(
            listEvidence(
                new ProviderPublicationClient.Scope(
                    UUID.randomUUID(), scope.connectionId(), scope.provider(), scope.revision())))
        .isFalse();
    update(
        "update public.provider_credential_health set observed_at=observed_at-interval '2 minutes',"
            + " published_at=published_at-interval '2 minutes',"
            + " valid_until=valid_until-interval '2 minutes' where connection_id=?",
        scope.connectionId());
    assertThat(listEvidence(scope)).isFalse();
    update(
        "update public.provider_credential_health set observed_at=?,published_at=?,valid_until=?"
            + " where connection_id=?",
        publication.value().observedAt(),
        publication.value().publishedAt(),
        publication.value().validUntil(),
        scope.connectionId());
    assertThat(listEvidence(scope)).isTrue();
    try (var reader = actor("web")) {
      denied(reader, "select * from agentos_evidence_private.integrity_assertions");
      denied(reader, "select verifier from public.provider_credential_versions");
    }
    try (var publisher = actor("health")) {
      denied(publisher, "select agentos_list_read.credential_available(null,null)");
    }

    update(
        "update public.provider_evidence_publishers set publisher_epoch=publisher_epoch+1"
            + " where publisher_id=?",
        publication.value().publisherId());
    assertThat(listEvidence(scope)).isFalse();
  }

  private boolean listEvidence(ProviderPublicationClient.Scope scope) throws Exception {
    try (var reader = actor("web");
        var statement =
            reader.prepareStatement(
                "select agentos_list_read.credential_available(?::uuid,?::uuid)")) {
      statement.setObject(1, scope.organizationId());
      statement.setObject(2, scope.connectionId());
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        boolean available = rows.getBoolean(1);
        assertThat(rows.next()).isFalse();
        return available;
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("List evidence fixture failed", failure);
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
    var prepared = checkedCapture("prepare", scope, "PREPARE");
    assertThat(prepared.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(client("prepare").assertPrepared(prepared.value().attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var captured = checkedCapture("health", scope, "HEALTH");
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
  void postCommitAcknowledgementLossStaysUnknownAfterFreshHealthReplacement() throws Exception {
    var scope = seed();
    // Normal client capture is acknowledged and committed before the faulted publication begins.
    var captured = checkedCapture("health", scope, "HEALTH").value();

    // Controlled post-commit JDBC wrapper, not an actual TCP partition, native wire fault,
    // or server-log privacy proof. Never expose underlying driver diagnostics.
    class LostAcknowledgementDataSource extends DriverManagerDataSource {
      int borrows;
      int statements;
      int commits;
      int rollbacks;

      LostAcknowledgementDataSource() {
        super(databaseUrl, login("health"), password("health"));
      }

      @Override
      public Connection getConnection() throws SQLException {
        borrows++;
        Connection actualConnection = super.getConnection();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  switch (method.getName()) {
                    case "commit":
                      commits++;
                      actualConnection.commit();
                      throw new SQLException(
                          "Controlled post-commit acknowledgement loss", "08006");
                    case "prepareStatement":
                    case "createStatement":
                    case "prepareCall":
                      statements++;
                      break;
                    case "rollback":
                      rollbacks++;
                      break;
                    default:
                      break;
                  }
                  try {
                    return method.invoke(actualConnection, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    }

    var faultedDataSource = new LostAcknowledgementDataSource();
    var original =
        new ProviderPublicationClient(faultedDataSource)
            .publishHealth(
                captured.attemptId(),
                ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                false,
                null);
    assertThat(original.status()).isEqualTo(ProviderPublicationClient.Status.UNKNOWN);
    assertThat(original.reason()).isEqualTo(ProviderPublicationClient.Reason.COMPLETION_UNKNOWN);
    assertThat(original.value()).isNull();
    assertThat(faultedDataSource.borrows).isEqualTo(1);
    assertThat(faultedDataSource.statements).isEqualTo(1);
    assertThat(faultedDataSource.commits).isEqualTo(1);
    assertThat(faultedDataSource.rollbacks).isZero();

    // Independent operator reads prove durable effects, without client readback or retry.
    assertThat(count("select count(*) from public.provider_credential_health")).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from public.provider_credential_health"
                    + " where publication_revision=1 and state='UNAVAILABLE'"))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from agentos_evidence_private.publication_attempts"
                    + " where attempt_id='"
                    + captured.attemptId()
                    + "' and consumed"))
        .isEqualTo(1);

    var fresh = checkedCapture("health", scope, "HEALTH").value();
    assertThat(fresh.attemptId()).isNotEqualTo(captured.attemptId());
    var replacement =
        client("health")
            .publishHealth(
                fresh.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
    assertThat(replacement.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(replacement.value().publicationRevision()).isEqualTo(2);
    assertThat(replacement.value().state()).isEqualTo("USABLE");
    assertThat(count("select count(*) from public.provider_credential_health")).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from public.provider_credential_health"
                    + " where publication_revision=2 and state='USABLE'"))
        .isEqualTo(1);

    // A later successful replacement cannot resolve the historical client's unknown completion.
    assertThat(original.status()).isEqualTo(ProviderPublicationClient.Status.UNKNOWN);
    assertThat(original.reason()).isEqualTo(ProviderPublicationClient.Reason.COMPLETION_UNKNOWN);
    assertThat(original.value()).isNull();
    assertThat(faultedDataSource.borrows).isEqualTo(1);
    assertThat(faultedDataSource.statements).isEqualTo(1);
    assertThat(faultedDataSource.commits).isEqualTo(1);
    assertThat(faultedDataSource.rollbacks).isZero();
  }

  @Test
  void loopbackCommitAckDropStaysUnknownAfterFreshHealthReplacement() throws Exception {
    var scope = seed();
    // Direct normal capture is committed before any proxy connection exists.
    var captured = checkedCapture("health", scope, "HEALTH").value();
    // Controlled real loopback transport loss, not an internet partition, exhaustive uncertainty
    // coverage, or server-log privacy proof. The proxy never inspects frontend SQL or SCRAM data.
    try (var proxy = new PostgresCommitAckProxy(databaseUrl)) {
      class WorkerDataSource extends DriverManagerDataSource {
        int borrows;
        int statements;
        int commits;
        int rollbacks;
        boolean commitConnectionFailure;

        WorkerDataSource() {
          super(proxy.jdbcUrl(), login("health"), password("health"));
        }

        @Override
        public Connection getConnection() throws SQLException {
          borrows++;
          Connection actualConnection = super.getConnection();
          // Counting only: the actual TCP proxy, not this JDBC wrapper, injects the loss.
          return (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (connection, method, args) -> {
                    switch (method.getName()) {
                      case "prepareStatement":
                      case "createStatement":
                      case "prepareCall":
                        statements++;
                        break;
                      case "commit":
                        commits++;
                        break;
                      case "rollback":
                        rollbacks++;
                        break;
                      default:
                        break;
                    }
                    try {
                      return method.invoke(actualConnection, args);
                    } catch (InvocationTargetException failure) {
                      if (method.getName().equals("commit")
                          && failure.getCause() instanceof SQLException sql
                          && sql.getSQLState() != null
                          && sql.getSQLState().startsWith("08")) {
                        commitConnectionFailure = true;
                      }
                      throw failure.getCause();
                    }
                  });
        }
      }

      var workerDataSource = new WorkerDataSource();
      var original =
          new ProviderPublicationClient(workerDataSource)
              .publishHealth(
                  captured.attemptId(),
                  ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                  false,
                  null);
      assertThat(proxy.awaitCommittedDrop())
          .as(
              "Actual backend COMMIT completion and idle ReadyForQuery withheld before socket"
                  + " close")
          .isTrue();
      assertThat(original.status()).isEqualTo(ProviderPublicationClient.Status.UNKNOWN);
      assertThat(workerDataSource.commitConnectionFailure).isTrue();
      assertThat(original.reason()).isEqualTo(ProviderPublicationClient.Reason.COMPLETION_UNKNOWN);
      assertThat(original.value()).isNull();
      assertThat(workerDataSource.borrows).isEqualTo(1);
      assertThat(workerDataSource.statements).isEqualTo(1);
      assertThat(workerDataSource.commits).isEqualTo(1);
      assertThat(workerDataSource.rollbacks).isZero();
      assertThat(proxy.acceptedConnections()).isEqualTo(1);

      // Independent private fixture reads prove the commit survived the dropped acknowledgement.
      assertThat(count("select count(*) from public.provider_credential_health")).isEqualTo(1);
      assertThat(
              count(
                  "select count(*) from public.provider_credential_health"
                      + " where publication_revision=1 and state='UNAVAILABLE'"))
          .isEqualTo(1);
      assertThat(
              count(
                  "select count(*) from agentos_evidence_private.publication_attempts"
                      + " where attempt_id='"
                      + captured.attemptId()
                      + "' and consumed"))
          .isEqualTo(1);

      var fresh = checkedCapture("health", scope, "HEALTH").value();
      assertThat(fresh.attemptId()).isNotEqualTo(captured.attemptId());
      var replacement =
          client("health")
              .publishHealth(
                  fresh.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
      assertThat(replacement.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
      assertThat(replacement.value().publicationRevision()).isEqualTo(2);
      assertThat(replacement.value().state()).isEqualTo("USABLE");
      assertThat(count("select count(*) from public.provider_credential_health")).isEqualTo(1);
      assertThat(
              count(
                  "select count(*) from public.provider_credential_health"
                      + " where publication_revision=2 and state='USABLE'"))
          .isEqualTo(1);
      assertThat(original.status()).isEqualTo(ProviderPublicationClient.Status.UNKNOWN);
      assertThat(original.reason()).isEqualTo(ProviderPublicationClient.Reason.COMPLETION_UNKNOWN);
      assertThat(original.value()).isNull();
      assertThat(workerDataSource.borrows).isEqualTo(1);
      assertThat(workerDataSource.statements).isEqualTo(1);
      assertThat(workerDataSource.commits).isEqualTo(1);
      assertThat(workerDataSource.rollbacks).isZero();
      assertThat(workerDataSource.commitConnectionFailure).isTrue();
      assertThat(proxy.acceptedConnections()).isEqualTo(1);
    }
  }

  @Test
  void casLossInvalidPresenceAndTimeBeforeFailedPreserveEvidence() throws Exception {
    var scope = seed();
    var first = checkedCapture("health", scope, "HEALTH").value();
    var second = checkedCapture("health", scope, "HEALTH").value();
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
    var fresh = checkedCapture("health", scope, "HEALTH").value();
    String beforePair = fingerprint();
    assertThat(
            client("health")
                .publishHealth(
                    fresh.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, true, null)
                .reason())
        .isEqualTo(ProviderPublicationClient.Reason.ATTEMPT);
    assertThat(fingerprint()).isEqualTo(beforePair);
    var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
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
    var staleHealth = checkedCapture("health", scope, "HEALTH").value();
    var delayedFailure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
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
    var current = checkedCapture("health", replacement, "HEALTH").value();
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
    var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
    var prepared = checkedCapture("prepare", scope, "PREPARE").value();
    assertThat(client("prepare").assertPrepared(prepared.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(client("health").publishIntegrityFailure(failure.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var again = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
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
    var historical = checkedCapture("health", scope, "INTEGRITY_FAILURE");
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
  void verifiedAtMaxRejectsConfirmedMismatchAsExhaustedWithoutAnyWrites() throws Exception {
    var scope = seed();
    var prepared = checkedCapture("prepare", scope, "PREPARE").value();
    assertThat(client("prepare").assertPrepared(prepared.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    update(
        "update public.provider_credential_integrity set publication_revision=9223372036854775807");
    // Keep exact current provenance consistent with the synthetic MAX evidence revision.
    update(
        "update agentos_evidence_private.integrity_provenance"
            + " set publication_revision=9223372036854775807 where organization_id=?"
            + " and connection_id=? and provider=? and credential_revision=?",
        scope.organizationId(),
        scope.connectionId(),
        scope.provider(),
        scope.revision());
    assertThat(
            count(
                "select count(*) from agentos_evidence_private.integrity_provenance q join"
                    + " public.provider_credential_integrity i using"
                    + " (organization_id,connection_id,provider,credential_revision,publication_revision)"
                    + " where i.state='VERIFIED_AT_WRITE' and"
                    + " i.publication_revision=9223372036854775807"))
        .isEqualTo(1);
    var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
    String before = fingerprint();
    var rejected = client("health").publishIntegrityFailure(failure.attemptId());
    assertThat(rejected.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
    assertThat(rejected.reason()).isEqualTo(ProviderPublicationClient.Reason.EXHAUSTED);
    assertThat(rejected.value()).isNull();
    assertThat(fingerprint()).isEqualTo(before);
    assertThat(
            count(
                "select count(*) from public.provider_credential_integrity where"
                    + " state='VERIFIED_AT_WRITE' and publication_revision=9223372036854775807"))
        .isEqualTo(1);
  }

  @Test
  void healthAtMaxWithMatchingCaptureRejectsAsExhaustedWithoutAnyWrites() throws Exception {
    var scope = seed();
    var first = checkedCapture("health", scope, "HEALTH").value();
    assertThat(
            client("health")
                .publishHealth(
                    first.attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    update("update public.provider_credential_health set publication_revision=9223372036854775807");
    // Capture after the operator fixture change, so CAS matches and cannot mask MAX.
    var fresh = checkedCapture("health", scope, "HEALTH").value();
    for (var outcome : ProviderPublicationClient.HealthOutcome.values()) {
      String before = fingerprint();
      var rejected = client("health").publishHealth(fresh.attemptId(), outcome, false, null);
      assertThat(rejected.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(rejected.reason()).isEqualTo(ProviderPublicationClient.Reason.EXHAUSTED);
      assertThat(rejected.value()).isNull();
      assertThat(fingerprint()).isEqualTo(before);
    }
  }

  @Test
  void confirmedFailureBeforeCapturedPreparationRemainsStickyWithoutAnyWrites() throws Exception {
    var scope = seed();
    var prepared = checkedCapture("prepare", scope, "PREPARE").value();
    var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
    var failed = client("health").publishIntegrityFailure(failure.attemptId());
    assertThat(failed.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(failed.value().state()).isEqualTo("FAILED");
    var afterFailure = checkedCapture("prepare", scope, "PREPARE").value();
    for (var attempt : List.of(prepared, afterFailure)) {
      String before = fingerprint();
      var rejected = client("prepare").assertPrepared(attempt.attemptId());
      assertThat(rejected.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
      assertThat(rejected.reason()).isEqualTo(ProviderPublicationClient.Reason.INTEGRITY_FAILED);
      assertThat(rejected.value()).isNull();
      assertThat(fingerprint()).isEqualTo(before);
    }
    assertThat(
            count(
                "select count(*) from agentos_evidence_private.integrity_assertions"
                    + " where kind='PREPARE'"))
        .isZero();
  }

  @Test
  void healthAdvancementDoesNotVetoCapturedConfirmedMismatch() throws Exception {
    var scope = seed();
    var prepared = checkedCapture("prepare", scope, "PREPARE").value();
    assertThat(client("prepare").assertPrepared(prepared.attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var failure = checkedCapture("health", scope, "INTEGRITY_FAILURE").value();
    var health = checkedCapture("health", scope, "HEALTH").value();
    var published =
        client("health")
            .publishHealth(
                health.attemptId(), ProviderPublicationClient.HealthOutcome.USABLE, false, null);
    assertThat(published.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var failed = client("health").publishIntegrityFailure(failure.attemptId());
    assertThat(failed.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(failed.value().state()).isEqualTo("FAILED");
    assertThat(failed.value().publicationRevision()).isEqualTo(2);
    assertThat(
            count(
                "select count(*) from public.provider_credential_health"
                    + " where publication_revision=1 and state='USABLE'"))
        .isEqualTo(1);
    assertThat(count("select count(*) from agentos_evidence_private.integrity_assertions"))
        .isEqualTo(2);
    assertThat(count("select count(*) from agentos_evidence_private.integrity_provenance"))
        .isEqualTo(2);
  }

  @Test
  void fullOrganizationRejectsCaptureWithoutWritesButAllowsPublicationAndOtherOrganization()
      throws Exception {
    var full = seed();
    var other = seed();
    var existing = checkedCapture("health", full, "HEALTH").value();
    // Consumed attempts still occupy capacity; clone only the intended source attempt.
    update(
        "insert into agentos_evidence_private.publication_attempts select"
            + " gen_random_uuid(),kind,login_name,login_oid,publisher_id,publisher_epoch,publisher_fence,"
            + "organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,"
            + "hmac_key_version,hmac_domain_version,verifier,integrity_present,integrity_revision,health_present,health_revision,observed_at,deadline,capture_xid,true"
            + " from agentos_evidence_private.publication_attempts cross join"
            + " generate_series(1,4095) where attempt_id=?",
        existing.attemptId());
    String before = fingerprint();
    var rejected = client("health").beginHealth(full);
    assertThat(rejected.status()).isEqualTo(ProviderPublicationClient.Status.REJECTED);
    assertThat(rejected.reason()).isEqualTo(ProviderPublicationClient.Reason.CAPACITY);
    assertThat(rejected.value()).isNull();
    assertThat(fingerprint()).isEqualTo(before);
    assertThat(
            client("health")
                .publishHealth(
                    existing.attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var independent = checkedCapture("health", other, "HEALTH").value();
    assertThat(
            client("health")
                .publishHealth(
                    independent.attemptId(),
                    ProviderPublicationClient.HealthOutcome.UNAVAILABLE,
                    false,
                    null)
                .status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(
            count(
                "select count(*) from agentos_evidence_private.publication_attempts"
                    + " where organization_id='"
                    + full.organizationId()
                    + "'"))
        .isEqualTo(4096);
    assertThat(
            count(
                "select count(*) from agentos_evidence_private.publication_attempts"
                    + " where organization_id='"
                    + other.organizationId()
                    + "'"))
        .isEqualTo(1);
  }

  @Test
  void realLoginRawDeadlineArgumentsRejectNullPresenceAndInvalidPairsWithoutAnyWrites()
      throws Exception {
    var scope = seed();
    var capture = checkedCapture("health", scope, "HEALTH").value();
    String finite = capture.deadline().minusSeconds(10).toString();
    for (Boolean present : new Boolean[] {null, false, true}) {
      for (String deadline : new String[] {null, finite, "infinity", "-infinity"}) {
        if (Boolean.FALSE.equals(present) && deadline == null
            || Boolean.TRUE.equals(present) && finite.equals(deadline)) {
          continue; // The two valid pairs are exercised separately through real LOGIN SQL below.
        }
        assertRawHealthDeadlineRejected(capture, present, deadline, "ATTEMPT");
      }
    }
    assertRawHealthDeadlineRejected(
        capture, true, capture.observedAt().minusSeconds(1).toString(), "TIME");
  }

  private void assertRawHealthDeadlineRejected(
      ProviderPublicationClient.Capture capture, Boolean present, String deadline, String reason)
      throws Exception {
    String before = fingerprint();
    try (var worker = actor("health");
        var statement =
            worker.prepareStatement(
                "select * from agentos_evidence_private.publish_health(?::uuid,'USABLE',"
                    + "?::boolean,?::timestamptz)")) {
      statement.setObject(1, capture.attemptId());
      statement.setObject(2, present);
      statement.setString(3, deadline);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("status")).isEqualTo("REJECTED");
        assertThat(rows.getString("reason")).isEqualTo(reason);
        for (int column = 3; column <= rows.getMetaData().getColumnCount(); column++) {
          assertThat(rows.getObject(column) == null)
              .as("Rejected descriptor column %s must be null", column)
              .isTrue();
        }
        assertThat(rows.next()).isFalse();
      }
    } catch (SQLException failure) {
      throw safeSqlFailure("Raw deadline rejection fixture failed", failure);
    }
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void realLoginRawValidDeadlinePairsShortenButNeverExtendCaptureDeadline() throws Exception {
    for (String mode : List.of("ABSENT", "SHORTER", "LATER")) {
      var scope = seed();
      var capture = checkedCapture("health", scope, "HEALTH").value();
      var componentDeadline =
          mode.equals("SHORTER")
              ? capture.deadline().minusSeconds(20)
              : capture.deadline().plusSeconds(60);
      var expectedDeadline = mode.equals("SHORTER") ? componentDeadline : capture.deadline();
      try (var worker = actor("health");
          var statement =
              worker.prepareStatement(
                  "select * from agentos_evidence_private.publish_health(?::uuid,'USABLE',"
                      + "?::boolean,?::timestamptz)")) {
        statement.setObject(1, capture.attemptId());
        statement.setBoolean(2, !mode.equals("ABSENT"));
        statement.setString(3, mode.equals("ABSENT") ? null : componentDeadline.toString());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("status")).as("%s deadline", mode).isEqualTo("ACCEPTED");
          assertThat(rows.getString("reason")).isEqualTo("NONE");
          assertThat(rows.getObject("observed_at", java.time.OffsetDateTime.class))
              .isEqualTo(capture.observedAt());
          assertThat(rows.getObject("valid_until", java.time.OffsetDateTime.class))
              .isEqualTo(expectedDeadline);
          assertThat(rows.next()).isFalse();
        }
      } catch (SQLException failure) {
        throw safeSqlFailure("Raw valid deadline fixture failed", failure);
      }
      try (var operator = admin(databaseUrl);
          var statement =
              operator.prepareStatement(
                  "select valid_until from public.provider_credential_health"
                      + " where organization_id=? and connection_id=?")) {
        statement.setObject(1, scope.organizationId());
        statement.setObject(2, scope.connectionId());
        try (var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getObject(1, java.time.OffsetDateTime.class)).isEqualTo(expectedDeadline);
          assertThat(rows.next()).isFalse();
        }
      }
    }
  }

  @Test
  void canonicalVerifierAndChangedFenceAreEnforcedByActualFunctions() throws Exception {
    var scope = seed();
    var attempt = checkedCapture("health", scope, "HEALTH").value();
    update(
        "update public.provider_evidence_publishers set publisher_epoch=2,publisher_fence=?"
            + " where publisher_id in (select publisher_id from"
            + " agentos_evidence_private.publisher_principals where login_name=?)",
        UUID.randomUUID(),
        login("health"));
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
    assertThat(checkedCapture("health", scope, "HEALTH").status())
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
    var preparation = checkedCapture("prepare", scope, "PREPARE").value();
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
    var capture = checkedCapture("health", scope, "HEALTH").value();
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
    var captured = checkedCapture("recreated", scope, "HEALTH");
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

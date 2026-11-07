package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.agentos.providerpreparation.ProviderPreparationJournal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Disposable browser, real HTTP/session, PostgreSQL and TLS Vault acceptance. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"agentos.provider-create.enabled=true", "agentos.provider-list.enabled=true"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AGENTOS_TEST_VAULT_URL", matches = "https://.*")
class ProviderCreateVaultHttpIT {
  private static final String PASSWORD = "Vault-browser-fixture-93847";
  private static final String SECRET = "browser-vault-disposable-synthetic-key";

  @LocalServerPort int port;
  private final JdbcTemplate jdbc = administrator();
  @Autowired PasswordEncoder passwords;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry properties) {
    provisionReader();
    properties.add("spring.flyway.enabled", () -> "false");
    properties.add("spring.datasource.url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("spring.datasource.username", () -> "vault_browser_web");
    properties.add("spring.datasource.password", () -> WEB_PASSWORD);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add(
        "agentos.provider-vault.writer-jdbc-url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("agentos.provider-vault.writer-username", () -> "vault_browser_writer");
    properties.add(
        "agentos.provider-vault.writer-password-file", () -> writerPasswordFile.toString());
    properties.add("agentos.provider-vault.url", () -> required("AGENTOS_TEST_VAULT_URL"));
    properties.add("agentos.provider-vault.mount", () -> "agentos");
    properties.add("agentos.provider-vault.ca-file", () -> required("AGENTOS_TEST_VAULT_CA"));
    properties.add(
        "agentos.provider-vault.app-token-file", () -> required("AGENTOS_TEST_VAULT_TOKEN_FILE"));
    properties.add(
        "agentos.provider-vault.hmac-token-file", () -> required("AGENTOS_TEST_HMAC_TOKEN_FILE"));
    properties.add("agentos.provider-vault.credential-key-version", () -> "2");
    properties.add("agentos.provider-vault.command-key-version", () -> "1");
  }

  @Test
  void browserCreatesAndRecoversCommittedReceiptThroughRealVault() throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID();
    String email = actor + "@example.test";
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Vault browser',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Vault"
            + " browser',?)",
        actor,
        email,
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    assertThat(
            jdbc.queryForObject(
                "select openai_enabled from provider_deployment_policy where id=1", Boolean.class))
        .isTrue();

    Path frontend = Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("frontend");
    if (!Files.isDirectory(frontend))
      frontend = frontend.getParent().getParent().resolve("frontend");
    assertThat(Files.isRegularFile(frontend.resolve("dist/agentos-frontend/browser/index.html")))
        .as("built frontend exists for browser acceptance")
        .isTrue();
    int proxyPort;
    try (var available = new ServerSocket(0)) {
      proxyPort = available.getLocalPort();
    }
    String base = "http://127.0.0.1:" + proxyPort;
    var serverCommand = new ProcessBuilder("node", "scripts/provider-vault-server.mjs");
    serverCommand.directory(frontend.toFile());
    serverCommand.environment().put("PORT", Integer.toString(proxyPort));
    serverCommand.environment().put("BACKEND_URL", "http://127.0.0.1:" + port);
    serverCommand.environment().put("DROP_CREATE_RESPONSE", "once");
    serverCommand.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    serverCommand.redirectError(ProcessBuilder.Redirect.DISCARD);
    var server = serverCommand.start();
    try {
      awaitProxy(server, base);
      var command =
          new ProcessBuilder(
              "node",
              "node_modules/@playwright/test/cli.js",
              "test",
              "tests/e2e/provider-create-live.spec.mjs",
              "--project",
              "desktop",
              "--workers",
              "1",
              "--retries",
              "0",
              "--reporter",
              "line");
      command.directory(frontend.toFile());
      command.environment().put("E2E_BASE_URL", base);
      command.environment().put("PROVIDER_VAULT_LIVE", "true");
      command.environment().put("PROVIDER_VAULT_ORG", org.toString());
      command.environment().put("PROVIDER_VAULT_EMAIL", email);
      command.environment().put("PROVIDER_VAULT_PASSWORD", PASSWORD);
      command.environment().put("PROVIDER_VAULT_SECRET", SECRET);
      Path diagnostics =
          Files.createTempFile(
              "provider-browser-",
              ".log",
              java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
      command.redirectOutput(diagnostics.toFile());
      command.redirectErrorStream(true);
      var browser = command.start();
      try {
        assertThat(browser.waitFor(90, TimeUnit.SECONDS))
            .as("browser completed within its budget")
            .isTrue();
        if (browser.exitValue() != 0) {
          // Only fixed source locations are emitted, never browser values or artifacts.
          String locations =
              Files.readAllLines(diagnostics).stream()
                  .filter(line -> line.matches(".*provider-create-live\\.spec\\.mjs:[0-9]+.*"))
                  .map(
                      line ->
                          line.replaceAll(".*(provider-create-live\\.spec\\.mjs:[0-9]+).*", "$1"))
                  .distinct()
                  .reduce("", (left, right) -> left + " " + right);
          String status =
              Files.readAllLines(diagnostics).stream()
                  .filter(
                      line ->
                          line.matches("PROVIDER_LIVE_DIAGNOSTIC creates=[0-9]+ statuses=[0-9,]*"))
                  .findFirst()
                  .orElse("");
          throw new AssertionError(
              "Browser acceptance exit=" + browser.exitValue() + locations + " " + status);
        }
      } finally {
        browser.destroyForcibly();
        browser.waitFor(10, TimeUnit.SECONDS);
        Files.deleteIfExists(diagnostics);
      }
    } finally {
      server.destroyForcibly();
      server.waitFor(10, TimeUnit.SECONDS);
    }

    var row =
        jdbc.queryForMap(
            "select c.id,v.external_locator,v.external_key_version,v.hmac_key_version,v.verifier,"
                + " p.id"
                + " preparation_id,p.create_actor_id,p.create_idempotency_key,r.idempotency_key"
                + " from provider_connections c join provider_credential_versions v on"
                + " v.organization_id=c.organization_id and v.connection_id=c.id join"
                + " provider_secret_preparations p on p.organization_id=v.organization_id and"
                + " p.connection_id=v.connection_id and p.external_locator=v.external_locator and"
                + " p.external_version=v.external_key_version join provider_command_receipts r on"
                + " r.organization_id=c.organization_id and r.connection_id=c.id and"
                + " r.actor_id=p.create_actor_id and r.idempotency_key=p.create_idempotency_key"
                + " where c.organization_id=? and c.name='Live Vault browser fixture' and"
                + " c.configuration_revision=1 and v.credential_revision=1 and p.state='WRITTEN'"
                + " and r.operation='CREATE'",
            org);
    UUID connection = (UUID) row.get("id");
    var scope = new Scope(org, connection, Provider.OPENAI, 1);
    var reference =
        new Reference(
            scope, (String) row.get("external_locator"), (String) row.get("external_key_version"));
    assertThat(row).containsEntry("create_actor_id", actor);
    assertThat(row.get("create_idempotency_key")).isEqualTo(row.get("idempotency_key"));
    for (String table :
        new String[] {
          "provider_connections",
          "provider_credential_versions",
          "provider_secret_preparations",
          "provider_command_receipts",
          "provider_connection_events"
        }) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?", Long.class, org))
          .as("one browser Create row in %s", table)
          .isEqualTo(1L);
    }
    var journal = new ProviderPreparationJournal(jdbc.getDataSource());
    assertThat(
            journal.observeCreate(
                new ProviderPreparationJournal.Preparation(
                    (UUID) row.get("preparation_id"), scope, reference.locator()),
                new CredentialBoundary.CreateAttempt(actor, (UUID) row.get("idempotency_key"))))
        .isEqualTo(ProviderPreparationJournal.Observation.RETAINED_REFERENCE_FOUND);
    readBack(scope, reference, (String) row.get("hmac_key_version"), (String) row.get("verifier"));
    String encoded = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.US_ASCII));
    for (String table :
        jdbc.queryForList(
            "select tablename from pg_catalog.pg_tables where schemaname='public'", String.class)) {
      assertThat(table.matches("[a-z_][a-z0-9_]*")).isTrue();
      for (String stored :
          jdbc.queryForList(
              "select row_to_json(t)::text from public." + table + " t", String.class)) {
        assertThat(stored.contains(SECRET) || stored.contains(encoded))
            .as("no browser credential in public DB rows")
            .isFalse();
      }
    }
  }

  private static final String WEB_PASSWORD = UUID.randomUUID().toString();
  private static Path writerPasswordFile;

  private static JdbcTemplate administrator() {
    var datasource = new DriverManagerDataSource();
    datasource.setUrl(required("AGENTOS_TEST_JOURNAL_URL"));
    datasource.setUsername("vault_journal_test");
    datasource.setPassword(file("AGENTOS_TEST_PG_PASSWORD_FILE"));
    return new JdbcTemplate(datasource);
  }

  private static void provisionReader() {
    var admin = administrator();
    Path operations = Path.of("operations");
    if (!Files.isDirectory(operations)) operations = Path.of("backend", "operations");
    try (var connection = admin.getDataSource().getConnection();
        var statement = connection.createStatement()) {
      for (String script :
          new String[] {
            "provider-evidence-roles.sql",
            "provider-evidence-publication.sql",
            "provider-list-evidence-reader.sql"
          }) {
        statement.execute(Files.readString(operations.resolve(script)));
      }
      // Fresh disposable login; random generated password contains only UUID characters.
      statement.execute(
          "create role vault_browser_web login nosuperuser nocreatedb nocreaterole"
              + " noreplication nobypassrls inherit password '"
              + WEB_PASSWORD
              + "'");
      String writerPassword = UUID.randomUUID().toString();
      writerPasswordFile =
          Files.createTempFile(
              "provider-browser-writer-",
              ".token",
              java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
      Files.writeString(writerPasswordFile, writerPassword);
      writerPasswordFile.toFile().deleteOnExit();
      statement.execute(
          "create role vault_browser_writer login nosuperuser nocreatedb nocreaterole"
              + " noreplication nobypassrls noinherit password '"
              + writerPassword
              + "'");
      statement.execute("grant usage on schema public to vault_browser_writer");
      statement.execute(
          "grant select,insert,update on public.provider_connections,"
              + "public.provider_credential_versions,public.provider_secret_preparations,"
              + "public.provider_command_receipts,public.provider_connection_events,"
              + "public.provider_write_admission to vault_browser_writer");
      statement.execute(
          "grant select,update on"
              + " public.organizations,public.human_accounts,public.organization_memberships,public.provider_deployment_policy"
              + " to vault_browser_writer");
      statement.execute("grant agentos_provider_list_evidence to vault_browser_web");
      statement.execute("grant usage on schema public to vault_browser_web");
      statement.execute(
          "grant select on public.organizations,public.organization_memberships,"
              + "public.provider_connections,public.human_accounts,public.provider_deployment_policy,public.provider_connection_events,public.provider_command_receipts"
              + " to vault_browser_web");
      statement.execute("grant update(id) on public.human_accounts to vault_browser_web");
      statement.execute(
          "grant select,insert,update on public.authentication_attempts,"
              + "public.provider_read_admission to vault_browser_web");
      statement.execute("grant insert on public.authentication_audit to vault_browser_web");
      statement.execute(
          "grant select,insert,update,delete on public.spring_session,"
              + "public.spring_session_attributes to vault_browser_web");
    } catch (Exception failure) {
      throw new IllegalStateException("Disposable browser reader provisioning failed");
    }
  }

  private static void readBack(Scope scope, Reference reference, String version, String verifier)
      throws Exception {
    var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    try (var input = Files.newInputStream(Path.of(required("AGENTOS_TEST_VAULT_CA")))) {
      trustStore.setCertificateEntry(
          "vault", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trusts.init(trustStore);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trusts.getTrustManagers(), null);
    var keys =
        new VaultHmacKeys(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            () -> file("AGENTOS_TEST_HMAC_TOKEN_FILE").toCharArray(),
            VaultHmacKeys.Purpose.CREDENTIAL_INTEGRITY,
            2,
            tls.getSocketFactory());
    var secrets =
        new VaultCredentialSecrets(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            () -> file("AGENTOS_TEST_VAULT_TOKEN_FILE").toCharArray(),
            (ignored, possible) -> {
              throw new CredentialBoundary.Failure();
            },
            tls.getSocketFactory());
    byte[] expected = SECRET.getBytes(StandardCharsets.US_ASCII);
    try {
      new CredentialBoundary(keys, secrets)
          .use(
              scope,
              new CredentialBoundary.Metadata(scope, version, reference, verifier),
              borrowed -> assertThat(Arrays.equals(borrowed, expected)).isTrue());
    } finally {
      Arrays.fill(expected, (byte) 0);
    }
  }

  private static void awaitProxy(Process server, String base) throws Exception {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < end && server.isAlive()) {
      try {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(base + "/login"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) return;
      } catch (java.io.IOException ignored) {
        // The proxy may still be binding its local socket.
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("Disposable frontend proxy did not become ready");
  }

  private static String file(String name) {
    try {
      return Files.readString(Path.of(required(name))).strip();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Disposable fixture file unavailable");
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Disposable fixture missing: " + name);
    return value;
  }
}

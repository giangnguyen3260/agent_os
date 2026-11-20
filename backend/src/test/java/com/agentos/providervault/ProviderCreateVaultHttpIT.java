package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.auth.HumanIdentity;
import com.agentos.providerchecks.ProviderAccessCheckDispatch;
import com.agentos.providerchecks.ProviderAccessCheckPoller;
import com.agentos.providerchecks.ProviderAccessCheckRunner;
import com.agentos.providerchecks.ProviderModelDiscoveryRunner;
import com.agentos.providerchecks.ProviderModelsTransport;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.agentos.providerpreparation.ProviderPreparationJournal;
import com.agentos.providerpublication.ProviderPublicationClient;
import com.agentos.providertransport.ProviderCreateBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Disposable browser, real HTTP/session, PostgreSQL and TLS Vault acceptance. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "agentos.provider-create.enabled=true",
      "agentos.provider-list.enabled=true",
      "agentos.provider-rename.enabled=true",
      "agentos.provider-rename.http-enabled=true",
      "agentos.provider-rotate.enabled=true",
      "agentos.provider-rotate.http-enabled=true",
      "agentos.provider-access-check.http-enabled=true",
      "agentos.provider-model-discovery.http-enabled=true",
      "agentos.execution.openai.enabled=true",
      "agentos.execution.openai.poll-enabled=false",
      "agentos.execution.openai.model=fixture-model-01",
      "agentos.execution.openai.max-output-tokens=100",
      "agentos.execution.openai.input-rate-microusd-per-million=1000000",
      "agentos.execution.openai.output-rate-microusd-per-million=1000000",
      "agentos.execution.openai.maximum-microusd=20000"
    })
@ActiveProfiles("test")
@Import(ProviderCreateVaultHttpIT.UploadReadContinue.class)
@EnabledIfEnvironmentVariable(named = "AGENTOS_TEST_VAULT_URL", matches = "https://.*")
class ProviderCreateVaultHttpIT {
  @TestConfiguration(proxyBeanMethods = false)
  static class UploadReadContinue {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> acknowledgeBodyRead() {
      // Test-only observation barrier: the managed timeout and admission stay unchanged.
      return factory ->
          factory.addConnectorCustomizers(
              connector ->
                  ((AbstractHttp11Protocol<?>) connector.getProtocolHandler())
                      .setContinueResponseTiming("onRead"));
    }
  }

  private static final String PASSWORD = "Vault-browser-fixture-93847";
  private static final String BROWSER_ROTATION = "browser-vault-rotate-recovery-synthetic";
  private static final String SECRET = "browser-vault-disposable-synthetic-key";

  @LocalServerPort int port;
  private final JdbcTemplate jdbc = administrator();
  @Autowired PasswordEncoder passwords;
  @Autowired ObjectMapper json;
  @Autowired ProviderCreateCoordinator creates;
  @Autowired Environment environment;
  @Autowired com.agentos.orchestration.application.OpenAiExecutionFlow openAiExecution;

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
    properties.add("agentos.provider-rename.url", () -> required("AGENTOS_TEST_VAULT_URL"));
    properties.add("agentos.provider-rename.mount", () -> "agentos");
    properties.add("agentos.provider-rename.ca-file", () -> required("AGENTOS_TEST_VAULT_CA"));
    properties.add(
        "agentos.provider-rename.command-token-file",
        () -> required("AGENTOS_TEST_RENAME_COMMAND_TOKEN_FILE"));
    properties.add("agentos.provider-rename.command-key-version", () -> "1");
    properties.add(
        "agentos.provider-rename.writer-jdbc-url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("agentos.provider-rename.writer-username", () -> "vault_browser_writer");
    properties.add(
        "agentos.provider-rename.writer-password-file", () -> writerPasswordFile.toString());
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

  @Test
  void browserRenamesAndRecoversMalformedReplyWithoutChangingTheStoredCredential()
      throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID();
    String email = actor + "@example.test";
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Rename browser',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Rename"
            + " browser',?)",
        actor,
        email,
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    String input =
        "{\"provider\":\"OPENAI\",\"credentialMode\":\"OPENAI_API_KEY\","
            + "\"name\":\"Live Rename before\",\"options\":{},\"apiKey\":\""
            + SECRET
            + "\"}";
    UUID connection;
    try (var body = ProviderCreateBody.consume(input.getBytes(StandardCharsets.UTF_8))) {
      var seeded =
          creates.create(
              new HumanIdentity(actor, email, "Rename browser", 0), org, UUID.randomUUID(), body);
      assertThat(seeded.state()).isEqualTo(WriteState.COMMITTED);
      connection = seeded.result().receipt().connectionId();
    }
    var original =
        jdbc.queryForMap(
            "select external_locator,external_key_version,hmac_key_version,verifier from"
                + " provider_credential_versions where organization_id=? and connection_id=?",
            org,
            connection);
    byte[] credentialBefore = credentialFingerprint(org, connection);

    runConnectionBrowser("rename", org, actor, connection);

    var current =
        jdbc.queryForMap(
            "select name,configuration_revision,credential_revision,provider,mode,state from"
                + " provider_connections where organization_id=? and id=?",
            org,
            connection);
    assertThat(current.get("name")).isEqualTo("Live Rename recovered");
    assertThat(((Number) current.get("configuration_revision")).longValue()).isEqualTo(3);
    assertThat(((Number) current.get("credential_revision")).longValue()).isEqualTo(1);
    assertThat(current.get("provider")).isEqualTo("OPENAI");
    assertThat(current.get("mode")).isEqualTo("OPENAI_API_KEY");
    assertThat(current.get("state")).isEqualTo("ENABLED");
    assertThat(Arrays.equals(credentialBefore, credentialFingerprint(org, connection))).isTrue();
    for (String table :
        new String[] {
          "provider_connections", "provider_credential_versions", "provider_secret_preparations"
        }) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?", Long.class, org))
          .isEqualTo(1L);
    }
    for (String table : new String[] {"provider_command_receipts", "provider_connection_events"}) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?", Long.class, org))
          .isEqualTo(3L);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from provider_command_receipts where organization_id=? and"
                    + " actor_id=? and connection_id=? and operation='RENAME' and key_version='1'",
                Long.class,
                org,
                actor,
                connection))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForList(
                "select name from provider_connection_events where organization_id=? and"
                    + " connection_id=? order by configuration_revision",
                String.class,
                org,
                connection))
        .containsExactly("Live Rename before", "Live Rename confirmed", "Live Rename recovered");
    var scope = new Scope(org, connection, Provider.OPENAI, 1);
    readBack(
        scope,
        new Reference(
            scope,
            (String) original.get("external_locator"),
            (String) original.get("external_key_version")),
        (String) original.get("hmac_key_version"),
        (String) original.get("verifier"));
  }

  @Test
  void rotatesThroughManagedHttpAndVaultAndRecoversTheSameReceipt() throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Rotate HTTP',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Rotate"
            + " HTTP',?)",
        actor,
        actor + "@example.test",
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);

    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    String base = "http://127.0.0.1:" + port;
    String collection = base + "/api/v1/organizations/" + org + "/provider-connections";
    var auth = authenticate(client, base, actor);
    var initial =
        postCredential(
            client,
            collection,
            auth,
            UUID.randomUUID(),
            "{\"provider\":\"OPENAI\",\"credentialMode\":\"OPENAI_API_KEY\","
                + "\"name\":\"Rotate fixture\",\"options\":{},\"apiKey\":\""
                + SECRET
                + "\"}");
    assertThat(initial.statusCode()).isEqualTo(201);
    var connection =
        UUID.fromString(json.readTree(initial.body()).at("/data/connectionId").asText());
    String path = collection + "/" + connection + "/rotate";
    UUID key = UUID.randomUUID();
    String newSecret = "rotation-disposable-synthetic-key";
    String body = "{\"expectedRevision\":\"1\",\"apiKey\":\"" + newSecret + "\"}";

    var noCsrf =
        client.send(
            HttpRequest.newBuilder(URI.create(path))
                .timeout(Duration.ofSeconds(10))
                .header("Idempotency-Key", key.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(noCsrf.statusCode()).isEqualTo(403);
    var first = postCredential(client, path, auth, key, body);
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(first.body()).doesNotContain(newSecret, "external_locator", "verifier");
    var receipt = json.readTree(first.body()).path("data");
    assertThat(receipt.path("operation").asText()).isEqualTo("ROTATE");
    assertThat(receipt.at("/before/credentialRevision").asText()).isEqualTo("1");
    assertThat(receipt.at("/after/credentialRevision").asText()).isEqualTo("2");
    assertThat(receipt.at("/after/revision").asText()).isEqualTo("2");

    var replay = postCredential(client, path, auth, key, body);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json.readTree(replay.body()).path("data")).isEqualTo(receipt);
    var lookup =
        client.send(
            HttpRequest.newBuilder(URI.create(collection + "/receipts/" + key))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(lookup.statusCode()).isEqualTo(200);
    assertThat(json.readTree(lookup.body()).at("/data/receipt")).isEqualTo(receipt);

    var scope = new Scope(org, connection, Provider.OPENAI, 2);
    var stored =
        jdbc.queryForMap(
            "select external_locator,external_key_version,hmac_key_version,verifier"
                + " from provider_credential_versions where organization_id=? and connection_id=?"
                + " and credential_revision=2",
            org,
            connection);
    var reference =
        new Reference(
            scope,
            (String) stored.get("external_locator"),
            (String) stored.get("external_key_version"));
    readBack(
        scope,
        reference,
        (String) stored.get("hmac_key_version"),
        (String) stored.get("verifier"),
        newSecret);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from provider_secret_preparations where organization_id=?",
                Long.class,
                org))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select state from provider_secret_preparations where organization_id=? and"
                    + " external_locator=?",
                String.class,
                org,
                reference.locator()))
        .isEqualTo("WRITTEN");

    runConnectionBrowser("rotate", org, actor, connection);
    var rotatedScope = new Scope(org, connection, Provider.OPENAI, 3);
    var rotated =
        jdbc.queryForMap(
            "select external_locator,external_key_version,hmac_key_version,verifier"
                + " from provider_credential_versions where organization_id=? and connection_id=?"
                + " and credential_revision=3",
            org,
            connection);
    readBack(
        rotatedScope,
        new Reference(
            rotatedScope,
            (String) rotated.get("external_locator"),
            (String) rotated.get("external_key_version")),
        (String) rotated.get("hmac_key_version"),
        (String) rotated.get("verifier"),
        BROWSER_ROTATION);
    assertThat(
            jdbc.queryForList(
                "select operation from provider_command_receipts where organization_id=? and"
                    + " connection_id=? order by recorded_at",
                String.class,
                org,
                connection))
        .containsExactly("CREATE", "ROTATE", "ROTATE");
    assertThat(
            jdbc.queryForList(
                "select credential_revision from provider_secret_preparations where"
                    + " organization_id=? and connection_id=? and state='WRITTEN' order by"
                    + " credential_revision",
                Long.class,
                org,
                connection))
        .containsExactly(1L, 2L, 3L);
  }

  @Test
  void browserAccessCheckRunsThroughManagedCredentialAndWorker() throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID();
    var human = seedCheckBrowserAdmin(org, actor, "Access Check");
    String keyFile = System.getenv("AGENTOS_ACCESS_CHECK_OPENAI_KEY_FILE");
    UUID connection;
    try (var body = checkCreateBody(keyFile, Provider.OPENAI, "Access Check fixture")) {
      var created = creates.create(human, org, UUID.randomUUID(), body);
      assertThat(created.state()).isEqualTo(WriteState.COMMITTED);
      connection = created.result().receipt().connectionId();
    }
    publishCheckEvidence(org, connection, Provider.OPENAI);

    var configured = ProviderCredentialVaultRuntime.assemble(environment);
    var runtime = configured.runtime();
    var dispatch = new ProviderAccessCheckDispatch(runtime.datasource());
    var sent = new AtomicInteger();
    var clock = Clock.systemUTC();
    try (var cancellations = Executors.newSingleThreadScheduledExecutor()) {
      var live = new ProviderModelsTransport(clock, cancellations);
      var runner =
          new ProviderAccessCheckRunner(
              dispatch,
              runtime.connections(),
              configured.credentials(),
              (provider, workspace, key, deadline) -> {
                if (provider != Provider.OPENAI || workspace != null)
                  return ProviderAccessCheckDispatch.Result.PROTOCOL_ERROR;
                sent.incrementAndGet();
                if (keyFile != null) return live.request(provider, workspace, key, deadline);
                return Arrays.equals(key, SECRET.getBytes(StandardCharsets.US_ASCII))
                    ? ProviderAccessCheckDispatch.Result.ACCESSIBLE
                    : ProviderAccessCheckDispatch.Result.PROTOCOL_ERROR;
              },
              clock);
      var poller = new ProviderAccessCheckPoller(runtime.datasource(), dispatch, runner);
      var worker = new ProviderAccessCheckWorkerConfiguration.Worker(poller::poll, null, 100);
      try {
        runConnectionBrowser("access_check", org, actor, connection);
      } finally {
        worker.destroy();
      }
    }
    assertThat(sent.get()).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "select outcome from provider_access_checks where organization_id=? and"
                    + " connection_id=?",
                String.class,
                org,
                connection))
        .containsExactly("ACCESSIBLE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from provider_access_check_outbox where organization_id=?",
                Long.class,
                org))
        .isZero();
  }

  @Test
  void browserDiscoversBindsGrantsAndRevokesAModelThroughAgentStudio() throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID();
    var human = seedCheckBrowserAdmin(org, actor, "Model Discovery");
    UUID draft = UUID.randomUUID();
    jdbc.update(
        """
        insert into agents
          (id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)
        values (?,?,'Discovery fixture Agent','Reviewer','MID','DRAFT','OBSERVE',
                current_timestamp,current_timestamp)
        """,
        draft,
        org);
    jdbc.update(
        """
        insert into agent_draft_config
          (agent_id,responsibilities,expected_input,output_format,acceptance_criteria,model)
        values (?,'Review','Notes','Summary','Complete','Legacy model label')
        """,
        draft);
    boolean enabled =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select anthropic_enabled from provider_deployment_policy where id=1",
                Boolean.class));
    if (!enabled) updateAnthropicPolicy(true);
    try {
      UUID connection;
      try (var body = checkCreateBody(null, Provider.ANTHROPIC, "Model Discovery fixture")) {
        var created = creates.create(human, org, UUID.randomUUID(), body);
        assertThat(created.state()).isEqualTo(WriteState.COMMITTED);
        connection = created.result().receipt().connectionId();
      }
      publishCheckEvidence(org, connection, Provider.ANTHROPIC);

      var configured = ProviderCredentialVaultRuntime.assemble(environment);
      var runtime = configured.runtime();
      var dispatch = ProviderAccessCheckDispatch.forDiscovery(runtime.datasource());
      var sends = new AtomicInteger();
      var models =
          java.util.stream.IntStream.rangeClosed(1, 21)
              .mapToObj(
                  index ->
                      new ProviderModelsTransport.Model(
                          String.format(java.util.Locale.ROOT, "fixture-model-%02d", index),
                          "Fixture model " + index))
              .toList();
      var runner =
          new ProviderModelDiscoveryRunner(
              dispatch,
              runtime.connections(),
              configured.credentials(),
              (provider, workspace, key, deadline, cursor, remaining) -> {
                int page = sends.incrementAndGet();
                var visible = ProviderAccessCheckDispatch.Result.ACCESSIBLE;
                if (provider == Provider.ANTHROPIC
                    && workspace == null
                    && Arrays.equals(key, SECRET.getBytes(StandardCharsets.US_ASCII))) {
                  if (page == 1 && cursor == null)
                    return new ProviderModelsTransport.Page(
                        visible, models.subList(0, 20), models.get(19).id(), 2048);
                  if (page == 2 && models.get(19).id().equals(cursor))
                    return new ProviderModelsTransport.Page(
                        visible, models.subList(20, 21), null, 128);
                }
                return new ProviderModelsTransport.Page(
                    ProviderAccessCheckDispatch.Result.PROTOCOL_ERROR, List.of(), null, 0);
              },
              Clock.systemUTC());
      var poller = ProviderAccessCheckPoller.forDiscovery(runtime.datasource(), dispatch, runner);
      var worker =
          new ProviderAccessCheckWorkerConfiguration.Worker(
              poller::dispatchNext, poller::expireOutstanding, 100);
      try {
        runConnectionBrowser("model_discovery", org, actor, connection, draft);
      } finally {
        worker.destroy();
      }

      assertThat(sends.get()).isEqualTo(2);
      assertThat(
              jdbc.queryForMap(
                  "select connection_id,model_id from agent_model_bindings"
                      + " where organization_id=? and agent_id=?",
                  org,
                  draft))
          .containsEntry("connection_id", connection)
          .containsEntry("model_id", "fixture-model-01");
      assertThat(
              jdbc.queryForMap(
                  "select revision,enabled,binding_revision from agent_model_grants"
                      + " where organization_id=? and agent_id=?",
                  org,
                  draft))
          .containsEntry("revision", 2)
          .containsEntry("enabled", false)
          .containsEntry("binding_revision", 1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from agent_model_grant_receipts"
                      + " where organization_id=? and agent_id=?",
                  Long.class,
                  org,
                  draft))
          .isEqualTo(2L);
      assertThat(
              jdbc.queryForObject(
                  "select model from agent_draft_config where agent_id=?", String.class, draft))
          .isEqualTo("Legacy model label");
      assertThat(jdbc.queryForObject("select status from agents where id=?", String.class, draft))
          .isEqualTo("DRAFT");
      assertThat(
              jdbc.queryForList(
                  "select outcome from provider_access_checks where organization_id=?"
                      + " and connection_id=? and check_kind='MODEL_DISCOVERY'",
                  String.class,
                  org,
                  connection))
          .containsExactly("ACCESSIBLE");
      assertThat(
              jdbc.queryForList(
                  "select page_ordinal from provider_model_discovery_pages"
                      + " where organization_id=? and connection_id=? order by page_ordinal",
                  Integer.class,
                  org,
                  connection))
          .containsExactly(1, 2);
      assertThat(
              jdbc.queryForList(
                  "select catalogue_state from provider_model_discovery_snapshots"
                      + " where organization_id=? and connection_id=?",
                  String.class,
                  org,
                  connection))
          .containsExactly("COMPLETE");
      assertThat(
              jdbc.queryForList(
                  "select model_id from provider_model_discovery_entries"
                      + " where organization_id=? and connection_id=? order by model_ordinal",
                  String.class,
                  org,
                  connection))
          .containsExactlyElementsOf(
              models.stream().map(ProviderModelsTransport.Model::id).toList());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from provider_access_check_outbox"
                      + " where organization_id=? and connection_id=?",
                  Long.class,
                  org,
                  connection))
          .isZero();
    } finally {
      if (!enabled) updateAnthropicPolicy(false);
    }
  }

  @Test
  void browserExecutesReworksAndAcceptsTaskUsingManagedVaultCredential() throws Exception {
    UUID org = UUID.randomUUID(),
        actor = UUID.randomUUID(),
        team = UUID.randomUUID(),
        mission = UUID.randomUUID(),
        task = UUID.randomUUID(),
        agent = UUID.randomUUID();
    var human = seedCheckBrowserAdmin(org, actor, "OpenAI execution");
    UUID connection;
    try (var body = checkCreateBody(null, Provider.OPENAI, "Execution fixture")) {
      var created = creates.create(human, org, UUID.randomUUID(), body);
      assertThat(created.state()).isEqualTo(WriteState.COMMITTED);
      connection = created.result().receipt().connectionId();
    }
    publishCheckEvidence(org, connection, Provider.OPENAI);
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at)"
            + " values(?,?,'Execution team','ACTIVE',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values(?,?,?,'Execution"
            + " Agent','Reviewer','MID','ACTIVE','SUGGEST',current_timestamp,current_timestamp)",
        agent,
        org,
        team);
    jdbc.update(
        "insert into"
            + " agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria)"
            + " values(?,'Review','Input','Text','Evidence')",
        agent);
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values(?,?,?,'Execution"
            + " Mission','Goal','RUNNING','NORMAL',current_timestamp,current_timestamp)",
        mission,
        org,
        team);
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id)"
            + " values(?,?,?,?,'Vault execution Task','READY',?)",
        task,
        org,
        team,
        mission,
        agent);
    UUID check = UUID.randomUUID();
    var at = java.time.OffsetDateTime.now().minusSeconds(2);
    var until = at.plusMinutes(5);
    long policy =
        jdbc.queryForObject(
            "select revision from provider_deployment_policy where id=1", Long.class);
    jdbc.update(
        """
insert into provider_access_checks(id,organization_id,connection_id,actor_id,actor_credential_version,connection_revision,credential_revision,policy_revision,accepted_at,queue_expires_at,worker_owner_id,dispatch_token,started_at,dispatch_deadline,authorized_at,completed_at,outcome,check_kind)
values(?,?,?,?,0,1,1,?,?,?,?,?,?,?,?,?,'ACCESSIBLE','MODEL_DISCOVERY')
""",
        check,
        org,
        connection,
        actor,
        policy,
        at,
        until,
        UUID.randomUUID(),
        UUID.randomUUID(),
        at,
        until,
        at,
        at.plusSeconds(1));
    jdbc.update(
        "insert into"
            + " provider_model_discovery_snapshots(organization_id,connection_id,check_id,catalogue_state)"
            + " values(?,?,?,'COMPLETE')",
        org,
        connection,
        check);
    jdbc.update(
        "insert into"
            + " provider_model_discovery_entries(organization_id,connection_id,check_id,model_ordinal,model_id)"
            + " values(?,?,?,1,'fixture-model-01')",
        org,
        connection,
        check);
    jdbc.update(
        "insert into"
            + " agent_model_bindings(organization_id,agent_id,revision,connection_id,check_id,model_id)"
            + " values(?,?,1,?,?,'fixture-model-01')",
        org,
        agent,
        connection,
        check);
    jdbc.update(
        "insert into agent_model_grants(organization_id,agent_id,revision,binding_revision,enabled)"
            + " values(?,?,1,1,true)",
        org,
        agent);
    for (var scope :
        java.util.Map.of("ORGANIZATION", org, "TEAM", team, "AGENT", agent, "MISSION", mission)
            .entrySet()) {
      jdbc.update(
          "insert into"
              + " execution_budget_buckets(id,organization_id,scope,subject_id,opens_at,closes_at,limit_microusd)"
              + " values(?,?,?,?,?,?,1000000)",
          UUID.randomUUID(),
          org,
          scope.getKey(),
          scope.getValue(),
          at.minusHours(1),
          at.plusHours(1));
      jdbc.update(
          "insert into"
              + " execution_capacity_limits(organization_id,scope,subject_id,max_concurrent,max_admissions,window_seconds)"
              + " values(?,?,?,5,100,60)",
          org,
          scope.getKey(),
          scope.getValue());
    }
    var sent = new AtomicInteger();
    var authorized = new java.util.concurrent.atomic.AtomicBoolean(true);
    var committed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var provider =
        com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext(
        "/completion",
        exchange -> {
          int ordinal = sent.incrementAndGet();
          byte[] request = exchange.getRequestBody().readAllBytes();
          authorized.compareAndSet(
              true,
              ("Bearer " + SECRET).equals(exchange.getRequestHeaders().getFirst("Authorization")));
          String sha;
          try {
            sha =
                java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(request));
          } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
          }
          committed.compareAndSet(
              true,
              jdbc.queryForObject(
                      "select count(*) from execution_model_authorizations a join"
                          + " execution_budget_reservations b on b.run_id=a.run_id and"
                          + " b.action_id=a.action_id where a.organization_id=? and"
                          + " a.request_sha256=? and a.handed_off_at is not null and"
                          + " b.status='HELD'",
                      Integer.class,
                      org,
                      sha)
                  == 1);
          byte[] response =
              ("{\"id\":\"vault-completion-"
                      + ordinal
                      + "\",\"object\":\"chat.completion\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Vault"
                      + " verified task"
                      + " output\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    provider.start();
    var timer = Executors.newSingleThreadScheduledExecutor();
    var workers = Executors.newSingleThreadScheduledExecutor();
    var failures = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
    var transportField =
        com.agentos.orchestration.application.OpenAiExecutionFlow.class.getDeclaredField(
            "transport");
    transportField.setAccessible(true);
    var original = transportField.get(openAiExecution);
    try {
      // Only this test replaces the fixed production route; no runtime endpoint property exists.
      var ctor =
          com.agentos.model.infrastructure.OpenAiTextTransport.class.getDeclaredConstructor(
              URI.class,
              Clock.class,
              java.util.concurrent.ScheduledExecutorService.class,
              boolean.class);
      ctor.setAccessible(true);
      transportField.set(
          openAiExecution,
          ctor.newInstance(
              URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/completion"),
              Clock.systemUTC(),
              timer,
              true));
      workers.scheduleWithFixedDelay(
          () -> {
            try {
              openAiExecution.poll();
            } catch (RuntimeException failure) {
              failures.compareAndSet(null, failure);
            }
          },
          0,
          50,
          TimeUnit.MILLISECONDS);
      try {
        runConnectionBrowser("openai_execution", org, actor, connection, task);
      } catch (Exception | AssertionError failure) {
        if (failures.get() != null) failure.addSuppressed(failures.get());
        throw failure;
      }
      assertThat(failures.get()).isNull();
      assertThat(sent.get()).isEqualTo(2);
      assertThat(authorized.get())
          .as("Both requests borrow the exact credential created in TLS Vault")
          .isTrue();
      assertThat(committed.get())
          .as("Exact handoff and HELD budget are committed before each provider call")
          .isTrue();
      assertThat(jdbc.queryForObject("select status from tasks where id=?", String.class, task))
          .isEqualTo("COMPLETED");
      assertThat(
              jdbc.queryForList(
                  "select d.decision from execution_review_decisions d join execution_runs r on"
                      + " r.id=d.run_id where r.task_id=? order by d.decided_at",
                  String.class,
                  task))
          .containsExactly("REWORK", "ACCEPT");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from execution_start_dispatch where organization_id=? and"
                      + " state='FINISHED'",
                  Integer.class,
                  org))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from execution_model_usage u join execution_runs r on"
                      + " r.id=u.run_id where r.task_id=?",
                  Integer.class,
                  task))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from execution_budget_reservations b join execution_runs r on"
                      + " r.id=b.run_id where r.task_id=? and b.status='SETTLED' and"
                      + " b.actual_microusd=13",
                  Integer.class,
                  task))
          .isEqualTo(2);
    } finally {
      workers.shutdownNow();
      workers.awaitTermination(10, TimeUnit.SECONDS);
      transportField.set(openAiExecution, original);
      timer.shutdownNow();
      provider.stop(0);
    }
  }

  private HumanIdentity seedCheckBrowserAdmin(UUID org, UUID actor, String name) {
    String email = actor + "@example.test";
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,?,?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        name,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,?,?)",
        actor,
        email,
        name,
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    return new HumanIdentity(actor, email, name, 0);
  }

  private void updateAnthropicPolicy(boolean enabled) {
    jdbc.update(
        "update provider_deployment_policy set anthropic_enabled=?,revision=revision+1,"
            + " updated_at=clock_timestamp() where id=1",
        enabled);
  }

  private ProviderCreateBody checkCreateBody(String keyFile, Provider provider, String name)
      throws IOException {
    char[] key =
        keyFile == null
            ? SECRET.toCharArray()
            : ProviderVaultConfiguration.readToken(Path.of(keyFile));
    // The JSON copy is consumed by the body decoder. Clear the generator's output buffer too.
    var encoded =
        new ByteArrayOutputStream() {
          void clear() {
            Arrays.fill(buf, (byte) 0);
          }
        };
    try {
      try (var writer = json.getFactory().createGenerator(encoded)) {
        writer.writeStartObject();
        writer.writeStringField("provider", provider.name());
        writer.writeStringField(
            "credentialMode",
            switch (provider) {
              case OPENAI -> "OPENAI_API_KEY";
              case ANTHROPIC -> "ANTHROPIC_API_KEY";
              case GOOGLE_GEMINI -> "GEMINI_API_KEY";
            });
        writer.writeStringField("name", name);
        writer.writeObjectFieldStart("options");
        if (provider == Provider.ANTHROPIC) writer.writeNullField("workspaceId");
        writer.writeEndObject();
        writer.writeFieldName("apiKey");
        writer.writeString(key, 0, key.length);
        writer.writeEndObject();
      }
      return ProviderCreateBody.consume(encoded.toByteArray());
    } finally {
      Arrays.fill(key, '\0');
      encoded.clear();
    }
  }

  private void publishCheckEvidence(UUID org, UUID connection, Provider provider) {
    UUID publisher = UUID.randomUUID();
    String login = "vault_browser_evidence_" + publisher.toString().replace("-", "");
    String password = UUID.randomUUID().toString();
    jdbc.execute(
        "create role "
            + login
            + " login nosuperuser nocreatedb nocreaterole inherit password '"
            + password
            + "'");
    jdbc.execute("grant agentos_provider_evidence_publisher to " + login);
    jdbc.update(
        "insert into provider_evidence_publishers values (?,1,?)", publisher, UUID.randomUUID());
    jdbc.update(
        "insert into agentos_evidence_private.publisher_principals"
            + " select ?,rolname,oid,?,true from pg_catalog.pg_roles where rolname=?",
        publisher,
        org,
        login);
    jdbc.update(
        "insert into agentos_evidence_private.publisher_permissions values"
            + " (?,?,'PREPARE'),(?,?,'HEALTH')",
        publisher,
        provider.name(),
        publisher,
        provider.name());
    var evidence =
        new ProviderPublicationClient(
            new DriverManagerDataSource(required("AGENTOS_TEST_JOURNAL_URL"), login, password));
    var scope = new ProviderPublicationClient.Scope(org, connection, provider.name(), 1);
    var prepared = evidence.beginPreparationAssertion(scope);
    assertThat(prepared.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(evidence.assertPrepared(prepared.value().attemptId()).status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    var health = evidence.beginHealth(scope);
    assertThat(health.status()).isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
    assertThat(
            evidence
                .publishHealth(
                    health.value().attemptId(),
                    ProviderPublicationClient.HealthOutcome.USABLE,
                    false,
                    null)
                .status())
        .isEqualTo(ProviderPublicationClient.Status.ACCEPTED);
  }

  private record Csrf(String header, String token) {}

  private Csrf authenticate(HttpClient client, String base, UUID actor) throws Exception {
    var csrfResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(csrfResponse.statusCode()).isEqualTo(200);
    var token = json.readTree(csrfResponse.body()).path("data");
    var login =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(5))
                .header(token.path("headerName").asText(), token.path("token").asText())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "username=" + actor + "%40example.test&password=" + PASSWORD))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(login.statusCode()).isEqualTo(200);
    csrfResponse =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(csrfResponse.statusCode()).isEqualTo(200);
    token = json.readTree(csrfResponse.body()).path("data");
    String csrf = token.path("token").asText();
    String header = token.path("headerName").asText();
    return new Csrf(header, csrf);
  }

  private static HttpResponse<String> postCredential(
      HttpClient client, String url, Csrf csrf, UUID key, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header(csrf.header(), csrf.token())
            .header("Idempotency-Key", key.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private byte[] credentialFingerprint(UUID org, UUID connection) throws Exception {
    String stored =
        jdbc.queryForObject(
            "select to_jsonb(v)::text from provider_credential_versions v where organization_id=?"
                + " and connection_id=?",
            String.class,
            org,
            connection);
    return MessageDigest.getInstance("SHA-256").digest(stored.getBytes(StandardCharsets.UTF_8));
  }

  private void runConnectionBrowser(String operation, UUID org, UUID actor, UUID connection)
      throws Exception {
    runConnectionBrowser(operation, org, actor, connection, null);
  }

  private void runConnectionBrowser(
      String operation, UUID org, UUID actor, UUID connection, UUID draft) throws Exception {
    Path frontend = Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("frontend");
    if (!Files.isDirectory(frontend))
      frontend = frontend.getParent().getParent().resolve("frontend");
    assertThat(Files.isRegularFile(frontend.resolve("dist/agentos-frontend/browser/index.html")))
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
    if (operation.equals("rename"))
      serverCommand.environment().put("DROP_RENAME_RESPONSE", "second");
    serverCommand.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    serverCommand.redirectError(ProcessBuilder.Redirect.DISCARD);
    String spec = "provider-" + operation.replace('_', '-') + "-live.spec.mjs";
    var location = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(spec) + ":[0-9]+");
    var server = serverCommand.start();
    try {
      awaitProxy(server, base);
      var command =
          new ProcessBuilder(
              "node",
              "node_modules/@playwright/test/cli.js",
              "test",
              "tests/e2e/" + spec,
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
      String prefix = "PROVIDER_" + operation.toUpperCase(java.util.Locale.ROOT);
      command.environment().put(prefix + "_VAULT_LIVE", "true");
      command.environment().put("PROVIDER_VAULT_ORG", org.toString());
      command.environment().put("PROVIDER_VAULT_EMAIL", actor + "@example.test");
      command.environment().put("PROVIDER_VAULT_PASSWORD", PASSWORD);
      command.environment().put(prefix + "_CONNECTION", connection.toString());
      if (draft != null && operation.equals("model_discovery"))
        command.environment().put("PROVIDER_MODEL_DISCOVERY_AGENT", draft.toString());
      if (draft != null && operation.equals("openai_execution")) {
        command.environment().put("PROVIDER_OPENAI_EXECUTION_TASK", draft.toString());
        var scope = jdbc.queryForMap("select team_id,mission_id from tasks where id=?", draft);
        command
            .environment()
            .put("PROVIDER_OPENAI_EXECUTION_TEAM", scope.get("team_id").toString());
        command
            .environment()
            .put("PROVIDER_OPENAI_EXECUTION_MISSION", scope.get("mission_id").toString());
      }
      Path diagnostics =
          Files.createTempFile(
              "provider-" + operation + "-browser-",
              ".log",
              java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
      command.redirectOutput(diagnostics.toFile());
      command.redirectErrorStream(true);
      var browser = command.start();
      try {
        assertThat(browser.waitFor(120, TimeUnit.SECONDS))
            .as(operation + " browser completed within budget")
            .isTrue();
        if (browser.exitValue() != 0) {
          String locations =
              Files.readAllLines(diagnostics).stream()
                  .flatMap(line -> location.matcher(line).results())
                  .map(java.util.regex.MatchResult::group)
                  .distinct()
                  .collect(java.util.stream.Collectors.joining(" "));
          String numeric =
              Files.readAllLines(diagnostics).stream()
                  .filter(
                      line ->
                          line.matches(
                              "PROVIDER_RENAME_LIVE_DIAGNOSTIC posts=[0-9]+ statuses=[0-9,]*"))
                  .findFirst()
                  .orElse("");
          throw new AssertionError(
              operation
                  + " browser acceptance exit="
                  + browser.exitValue()
                  + locations
                  + " "
                  + numeric);
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
  }

  @Test
  void idleUploadsTimeOutAndReleaseEveryCreateAdmissionSlot() throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Idle upload',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'Idle upload',?)",
        actor,
        actor + "@example.test",
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    String base = "http://127.0.0.1:" + port;
    var auth = authenticate(client, base, actor);
    String csrf = auth.token();
    String header = auth.header();
    String cookie =
        String.join(
            "; ",
            cookies.getCookieStore().getCookies().stream()
                .map(value -> value.getName() + "=" + value.getValue())
                .toList());
    String path = "/api/v1/organizations/" + org + "/provider-connections";
    var incompleteKeys = new ArrayList<UUID>();
    var rejectedKeys = new ArrayList<UUID>();

    UUID baselineKey = UUID.randomUUID();
    rejectedKeys.add(baselineKey);
    assertThat(malformedProbe(client, base + path, header, csrf, baselineKey).statusCode())
        .as("authenticated baseline reaches parser before occupancy")
        .isEqualTo(400);
    try (Socket raw = partialUpload(path, cookie, header, csrf, UUID.randomUUID())) {
      raw.getOutputStream().write('}');
      raw.getOutputStream().flush();
      assertThat(readStatus(raw)).as("raw cookie/header baseline reaches parser").isEqualTo(400);
    }

    // Each raw request waits for onRead 100-continue after the command acquires its slot.
    // Only then can the ninth malformed request observe 503 without stealing a slot.
    var sockets = new ArrayList<Socket>();
    long stalledAt = System.nanoTime();
    try {
      for (int index = 0; index < 8; index++) {
        UUID key = UUID.randomUUID();
        incompleteKeys.add(key);
        sockets.add(partialUpload(path, cookie, header, csrf, key));
      }
      assertSaturation(client, base + path, header, csrf, rejectedKeys);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      for (Socket socket : sockets) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        assertThat(remaining).as("idle uploads complete within bounded budget").isPositive();
        socket.setSoTimeout((int) remaining);
        // Tomcat 10.1.31 InputBuffer.handleReadException marks sendError(408), even
        // when the parser catches it. Boot's /error dispatch can be denied with 403.
        // This cycle observes connector abort, not a public command error contract.
        // SocketTimeoutException is deliberately not accepted as server-side completion.
        try {
          assertThat(readStatus(socket)).isIn(400, 403, 408);
        } catch (EOFException | SocketException closed) {
          // A server-initiated EOF is a completed aborted upload, not a client timeout.
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stalledAt))
            .as("a stalled upload is not an immediate authentication or header rejection")
            .isGreaterThanOrEqualTo(8000L);
      }
    } finally {
      closeUploads(sockets);
    }

    // Reoccupy all eight slots, rather than inferring full recovery from one successful POST.
    sockets.clear();
    try {
      for (int index = 0; index < 8; index++) {
        UUID key = UUID.randomUUID();
        incompleteKeys.add(key);
        sockets.add(partialUpload(path, cookie, header, csrf, key));
      }
      assertSaturation(client, base + path, header, csrf, rejectedKeys);
      for (Socket socket : sockets) {
        socket.getOutputStream().write('}');
        socket.getOutputStream().flush();
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      for (Socket socket : sockets) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        assertThat(remaining).as("completed uploads release within bounded budget").isPositive();
        socket.setSoTimeout((int) remaining);
        assertThat(readStatus(socket)).isEqualTo(400);
      }
    } finally {
      closeUploads(sockets);
    }

    for (String table :
        new String[] {
          "provider_connections",
          "provider_credential_versions",
          "provider_secret_preparations",
          "provider_command_receipts",
          "provider_connection_events",
          "provider_write_admission"
        }) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?", Long.class, org))
          .as("stalled and rejected uploads never enter coordinator: %s", table)
          .isZero();
    }

    UUID committedKey = UUID.randomUUID();
    var created =
        client.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header(header, csrf)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", committedKey.toString())
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"provider\":\"OPENAI\",\"credentialMode\":\"OPENAI_API_KEY\","
                            + "\"name\":\"After idle upload\",\"apiKey\":\""
                            + SECRET
                            + "\",\"options\":{}}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
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
          .as("only completed upload creates a %s row", table)
          .isEqualTo(1L);
    }
    var persisted =
        jdbc.queryForMap(
            "select p.create_idempotency_key,r.idempotency_key,v.connection_id,"
                + "v.external_locator,v.external_key_version,v.hmac_key_version,v.verifier from"
                + " provider_secret_preparations p join provider_credential_versions v on"
                + " v.organization_id=p.organization_id and v.connection_id=p.connection_id and"
                + " v.external_locator=p.external_locator and"
                + " v.external_key_version=p.external_version join provider_command_receipts r on"
                + " r.organization_id=v.organization_id and r.connection_id=v.connection_id where"
                + " v.organization_id=?",
            org);
    assertThat(persisted.get("create_idempotency_key")).isEqualTo(committedKey);
    assertThat(persisted.get("idempotency_key")).isEqualTo(committedKey);
    assertThat(incompleteKeys).doesNotContain(committedKey);
    assertThat(rejectedKeys).isNotEmpty().doesNotContain(committedKey);
    var scope = new Scope(org, (UUID) persisted.get("connection_id"), Provider.OPENAI, 1);
    readBack(
        scope,
        new Reference(
            scope,
            (String) persisted.get("external_locator"),
            (String) persisted.get("external_key_version")),
        (String) persisted.get("hmac_key_version"),
        (String) persisted.get("verifier"));
  }

  private Socket partialUpload(String path, String cookie, String csrfHeader, String csrf, UUID key)
      throws IOException {
    var socket = new Socket();
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
      socket.setSoTimeout(25_000);
      String headers =
          "POST "
              + path
              + " HTTP/1.1\r\nHost: 127.0.0.1:"
              + port
              + "\r\nCookie: "
              + cookie
              + "\r\n"
              + csrfHeader
              + ": "
              + csrf
              + "\r\nIdempotency-Key: "
              + key
              + "\r\nAccept: application/json\r\nContent-Type: application/json"
              + "\r\nExpect: 100-continue\r\nConnection: close\r\nContent-Length: 2\r\n\r\n{";
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertThat(readStatus(socket))
          .as("body read follows successful slot acquisition")
          .isEqualTo(100);
      assertThat(socket.getInputStream().read()).isEqualTo((int) '\r');
      assertThat(socket.getInputStream().read()).isEqualTo((int) '\n');
      return socket;
    } catch (IOException | RuntimeException | Error failure) {
      try {
        socket.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private void assertSaturation(
      HttpClient client, String url, String header, String csrf, List<UUID> rejectedKeys)
      throws Exception {
    UUID key = UUID.randomUUID();
    rejectedKeys.add(key);
    assertThat(malformedProbe(client, url, header, csrf, key).statusCode())
        .as("all eight acknowledged uploads hold their admission slots")
        .isEqualTo(503);
  }

  private HttpResponse<Void> malformedProbe(
      HttpClient client, String url, String header, String csrf, UUID key) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header(header, csrf)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    return response;
  }

  private static int readStatus(Socket socket) throws IOException {
    var input = socket.getInputStream();
    var status = new StringBuilder();
    for (int index = 0; index < 1024; index++) {
      int next = input.read();
      if (next == -1) throw new EOFException();
      if (next == '\n') {
        String line = status.toString();
        if (!line.matches("HTTP/1[.][01] [0-9]{3} .*\r"))
          throw new IOException("Invalid disposable upload response status");
        return Integer.parseInt(line.substring(9, 12));
      }
      status.append((char) next);
    }
    throw new IOException("Oversized disposable upload response status");
  }

  private static void closeUploads(List<Socket> sockets) throws IOException {
    IOException failure = null;
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
    }
    if (failure != null) throw failure;
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
      statement.execute("grant usage on schema agentos_list_read to vault_browser_writer");
      statement.execute(
          "grant execute on function agentos_list_read.credential_available(uuid,uuid)"
              + " to vault_browser_writer");
      statement.execute(
          "grant insert on"
              + " public.provider_access_check_receipts,public.provider_access_check_events,public.provider_access_checks"
              + " to vault_browser_writer");
      statement.execute(
          "grant select,insert,delete on public.provider_access_check_outbox to"
              + " vault_browser_writer");
      statement.execute("grant update on public.provider_access_checks to vault_browser_writer");
      statement.execute(
          "grant select,insert on public.provider_model_discovery_pages to vault_browser_writer");
      statement.execute(
          "grant insert on public.provider_model_discovery_snapshots,"
              + "public.provider_model_discovery_entries to vault_browser_writer");
      statement.execute("grant usage on schema public to vault_browser_writer");
      statement.execute(
          "grant select on public.provider_access_check_receipts,public.provider_access_checks"
              + " to vault_browser_writer");
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
              + "public.provider_connections,public.human_accounts,public.provider_deployment_policy,public.provider_connection_events,public.provider_access_check_receipts,public.provider_access_checks,public.provider_command_receipts"
              + " to vault_browser_web");
      statement.execute(
          "grant select on public.provider_model_discovery_snapshots,"
              + "public.provider_model_discovery_entries to vault_browser_web");
      statement.execute(
          "grant select on public.agents,public.agent_draft_config to vault_browser_web");
      statement.execute(
          "grant select,insert,update on public.agent_model_bindings to vault_browser_web");
      statement.execute(
          "grant select,insert on public.agent_model_binding_receipts to vault_browser_web");
      statement.execute(
          "grant select,insert,update on public.agent_model_grants to vault_browser_web");
      statement.execute(
          "grant select,insert on public.agent_model_grant_receipts to vault_browser_web");
      statement.execute(
          "grant update(id) on"
              + " public.organizations,public.human_accounts,public.agents,public.provider_deployment_policy,public.provider_connections"
              + " to vault_browser_web");
      statement.execute(
          "grant update(account_id) on public.organization_memberships to vault_browser_web");
      statement.execute(
          "grant select,insert,update on public.authentication_attempts,"
              + "public.provider_read_admission to vault_browser_web");
      // Explicit runtime permissions on this disposable test login only. Provider command
      // metadata remains read-only; the credential writer continues to use its separate login.
      statement.execute(
          "grant select on"
              + " public.provider_credential_versions,public.task_dependencies,public.accepted_mission_plan_dependencies,public.execution_capacity_limits"
              + " to vault_browser_web");
      statement.execute(
          "grant select,insert,update on"
              + " public.teams,public.missions,public.tasks,public.accepted_mission_plans,public.accepted_mission_plan_tasks,public.mission_execution_controls,public.execution_runs,public.execution_attempts,public.execution_start_dispatch,public.execution_openai_requests,public.execution_claims,public.execution_budget_buckets,public.execution_budget_reservations,public.execution_budget_reservation_buckets,public.execution_model_authorizations,public.execution_model_usage,public.execution_attempt_reconciliations,public.execution_verified_output_content,public.execution_reconciled_review_submissions,public.execution_review_decisions"
              + " to vault_browser_web");
      statement.execute("grant select,insert on public.audit_events to vault_browser_web");
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
    readBack(scope, reference, version, verifier, SECRET);
  }

  private static void readBack(
      Scope scope, Reference reference, String version, String verifier, String expectedSecret)
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
    byte[] expected = expectedSecret.getBytes(StandardCharsets.US_ASCII);
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

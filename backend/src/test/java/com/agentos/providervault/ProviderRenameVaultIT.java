package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerrename.ProviderRenameCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.EOFException;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Managed Rename with real PostgreSQL/TLS command HMAC and no credential-store configuration. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "agentos.provider-rename.enabled=true",
      "agentos.provider-rename.http-enabled=true",
      "agentos.provider-create.enabled=false",
      "agentos.provider-list.enabled=false"
    })
@ActiveProfiles("test")
@Import(ProviderRenameVaultIT.UploadReadContinue.class)
@EnabledIfEnvironmentVariable(named = "AGENTOS_TEST_VAULT_URL", matches = "https://.*")
class ProviderRenameVaultIT {
  @TestConfiguration(proxyBeanMethods = false)
  static class UploadReadContinue {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> acknowledgeRenameBodyRead() {
      // Observe the existing production permit acquisition without changing its timeout.
      return factory ->
          factory.addConnectorCustomizers(
              connector ->
                  ((AbstractHttp11Protocol<?>) connector.getProtocolHandler())
                      .setContinueResponseTiming("onRead"));
    }
  }

  private static final String PASSWORD = "Rename-http-fixture-93847";
  private static final String READER = "vault_rename_web";
  private static final String WRITER = "vault_rename_writer";
  private static final String READER_PASSWORD = UUID.randomUUID().toString();
  private static Path writerPasswordFile;
  private static Path commandTokenFile;

  private final JdbcTemplate admin = new JdbcTemplate(administrator());
  @Autowired ProviderRenameCoordinator coordinator;
  @Autowired JdbcTemplate reader;
  @Autowired Environment environment;
  @Autowired ApplicationContext context;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder passwords;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry properties) {
    provisionPrincipals();
    properties.add("spring.flyway.enabled", () -> "false");
    properties.add("spring.datasource.url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("spring.datasource.username", () -> READER);
    properties.add("spring.datasource.password", () -> READER_PASSWORD);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("agentos.provider-rename.url", () -> required("AGENTOS_TEST_VAULT_URL"));
    properties.add("agentos.provider-rename.mount", () -> "agentos");
    properties.add("agentos.provider-rename.ca-file", () -> required("AGENTOS_TEST_VAULT_CA"));
    properties.add("agentos.provider-rename.command-token-file", () -> commandTokenFile.toString());
    properties.add("agentos.provider-rename.command-key-version", () -> "1");
    properties.add(
        "agentos.provider-rename.writer-jdbc-url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("agentos.provider-rename.writer-username", () -> WRITER);
    properties.add(
        "agentos.provider-rename.writer-password-file", () -> writerPasswordFile.toString());
  }

  @Test
  void managedRenameUsesOnlyCommandHmacAndItsDedicatedMetadataWriter() throws Exception {
    assertThat(context.getBeansOfType(ProviderCreateCoordinator.class)).isEmpty();
    for (String property :
        new String[] {"url", "app-token-file", "hmac-token-file", "credential-key-version"}) {
      assertThat(environment.getProperty("agentos.provider-vault." + property)).isNull();
    }
    assertThat(reader.queryForObject("select current_user", String.class)).isEqualTo(READER);
    assertThat(privilege(READER, "SELECT")).isFalse();
    assertThat(privilege(WRITER, "SELECT")).isTrue();
    for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"}) {
      assertThat(privilege(WRITER, operation)).isFalse();
    }

    UUID org = UUID.randomUUID(), actor = UUID.randomUUID(), connection = UUID.randomUUID();
    admin.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,'Rename managed',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    admin.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'Rename managed','unused')",
        actor,
        actor + "@example.test");
    admin.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    var human = new HumanIdentity(actor, actor + "@example.test", "Rename managed", 0);
    var seed = ProviderCommandRuntime.assemble(administrator());
    String digest = Base64.getEncoder().encodeToString(new byte[32]);
    var prepared =
        new CommandBoundary.Metadata(CommandBoundary.DIGEST_VERSION, "fixture-v1", digest);
    var scope =
        new CredentialBoundary.Scope(org, connection, CredentialBoundary.Provider.OPENAI, 1);
    var credential =
        new CredentialBoundary.Metadata(
            scope,
            "fixture-v1",
            new CredentialBoundary.Reference(scope, "not-a-live-credential-reference", "1"),
            digest);
    assertThat(
            seed.completion()
                .executePrepared(
                    human,
                    org,
                    UUID.randomUUID(),
                    new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Before", null),
                    prepared,
                    connection,
                    credential)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    assertThat(
            seed.completion()
                .executePrepared(
                    human,
                    org,
                    UUID.randomUUID(),
                    new RevisionCommand(Operation.DISABLE, connection, 1),
                    prepared,
                    null,
                    null)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    byte[] retainedCredential = fingerprint(org, "provider_credential_versions");
    Map<String, Object> policy =
        admin.queryForMap(
            "select openai_enabled,anthropic_enabled,gemini_enabled from"
                + " provider_deployment_policy where id=1");
    try {
      admin.update(
          "update provider_deployment_policy set openai_enabled=false,anthropic_enabled=true,"
              + " revision=revision+1,updated_at=clock_timestamp() where id=1");
      UUID key = UUID.randomUUID();
      var command = new Rename(connection, 2, " Managed renamed ");
      var first = coordinator.rename(human, org, key, command);
      assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(first.result().replayed()).isFalse();
      var receipt = first.result().receipt();
      assertThat(receipt.operation()).isEqualTo(Operation.RENAME);
      assertThat(receipt.before().revision()).isEqualTo(2);
      assertThat(receipt.after().revision()).isEqualTo(3);
      assertThat(receipt.after().name()).isEqualTo("Managed renamed");
      assertThat(receipt.after().state()).isEqualTo("DISABLED");
      assertThat(receipt.after().credentialRevision()).isEqualTo(1);
      assertThat(receipt.actorId()).isEqualTo(actor);
      assertThat(
              admin.queryForObject(
                  "select count(*) from provider_command_receipts where organization_id=? and"
                      + " actor_id=? and idempotency_key=? and operation='RENAME' and"
                      + " connection_id=? and key_version='1'",
                  Long.class,
                  org,
                  actor,
                  key,
                  connection))
          .isEqualTo(1L);
      var later =
          coordinator.rename(
              human, org, UUID.randomUUID(), new Rename(connection, 3, "Later name"));
      assertThat(later.state()).isEqualTo(WriteState.COMMITTED);
      String quota = samples(org, actor);
      var replay = coordinator.rename(human, org, key, command);
      assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(replay.result().replayed()).isTrue();
      assertThat(replay.result().receipt()).isEqualTo(receipt);
      assertThat(samples(org, actor)).isEqualTo(quota);
      assertThat(
              admin.queryForObject(
                  "select name from provider_connections where organization_id=? and id=?",
                  String.class,
                  org,
                  connection))
          .isEqualTo("Later name");
      assertThat(
              Arrays.equals(retainedCredential, fingerprint(org, "provider_credential_versions")))
          .isTrue();
      assertThat(
              admin.queryForObject(
                  "select count(*) from provider_secret_preparations where organization_id=?",
                  Long.class,
                  org))
          .isZero();

      byte[] beforeOutage = businessFingerprint(org);
      byte[] token = Files.readAllBytes(commandTokenFile);
      try {
        Files.delete(commandTokenFile);
        var unavailable =
            coordinator.rename(
                human, org, UUID.randomUUID(), new Rename(connection, 4, "No write"));
        assertThat(unavailable.state()).isEqualTo(WriteState.UNAVAILABLE);
        assertThat(unavailable.result()).isNull();
        assertThat(unavailable.failure().code()).isEqualTo(Code.COMMAND_UNAVAILABLE);
        assertThat(Arrays.equals(beforeOutage, businessFingerprint(org))).isTrue();
        assertThat(samples(org, actor).split(",")).hasSize(3);
      } finally {
        try {
          if (!Files.exists(commandTokenFile)) {
            Files.createFile(
                commandTokenFile,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
          }
          Files.write(commandTokenFile, token);
        } finally {
          Arrays.fill(token, (byte) 0);
        }
      }
      var recovered = coordinator.rename(human, org, key, command);
      assertThat(recovered.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(recovered.result().replayed()).isTrue();
      assertThat(recovered.result().receipt()).isEqualTo(receipt);
      assertThat(Arrays.equals(beforeOutage, businessFingerprint(org))).isTrue();
    } finally {
      admin.update(
          "update provider_deployment_policy set openai_enabled=?,anthropic_enabled=?,"
              + "gemini_enabled=?,revision=revision+1,updated_at=clock_timestamp() where id=1",
          policy.get("openai_enabled"),
          policy.get("anthropic_enabled"),
          policy.get("gemini_enabled"));
    }
  }

  @Test
  void renameHttpUsesRealSessionCsrfAndReturnsOnlyOwnHistoricalReceipts() throws Exception {
    var fixture = seedHttpFixture();
    String path = renamePath(fixture);
    String receiptRoot =
        "/api/v1/organizations/" + fixture.org() + "/provider-connections/receipts/";
    byte[] retained = fingerprint(fixture.org(), "provider_credential_versions");
    byte[] before = businessFingerprint(fixture.org());
    var anonymous = client();
    var denied = send(anonymous.client(), post(path, UUID.randomUUID(), "not-json", null));
    error(denied, 401, "UNAUTHORIZED");
    assertThat(denied.headers().allValues("Set-Cookie")).isEmpty();
    try (Socket socket = partialUpload(path, null, UUID.randomUUID(), false)) {
      assertThat(readStatus(socket))
          .as("anonymous authority gate does not await the body")
          .isEqualTo(401);
    }
    var session = login(fixture.actor());
    String body = "{\"expectedRevision\":\"1\",\"name\":\" HTTP renamed \"}";
    error(send(session.client(), post(path, UUID.randomUUID(), body, null)), 403, "FORBIDDEN");
    error(
        send(session.client(), post(path, UUID.randomUUID(), body, "foreign-csrf")),
        403,
        "FORBIDDEN");
    for (String invalid :
        new String[] {
          "{malformed",
          "{}",
          "{\"expectedRevision\":1,\"name\":\"HTTP renamed\"}",
          "{\"expectedRevision\":\"1\",\"name\":\"HTTP"
              + " renamed\",\"apiKey\":\"synthetic-forbidden-key\"}",
          "x".repeat(8193)
        }) {
      error(
          send(session.client(), post(path, UUID.randomUUID(), invalid, session.csrf())),
          400,
          "BAD_REQUEST");
    }
    error(
        send(
            session.client(),
            post(path + "?unexpected=1", UUID.randomUUID(), body, session.csrf())),
        400,
        "BAD_REQUEST");
    error(
        send(
            session.client(),
            post(
                path.replace(fixture.connection().toString(), "invalid-target"),
                UUID.randomUUID(),
                body,
                session.csrf())),
        400,
        "BAD_REQUEST");
    assertThat(Arrays.equals(before, businessFingerprint(fixture.org()))).isTrue();
    assertThat(writeAdmissionRows(fixture.org())).isZero();

    UUID key = UUID.randomUUID();
    var first = send(session.client(), post(path, key, body, session.csrf()));
    assertThat(first.statusCode()).isEqualTo(201);
    var firstData = data(first);
    assertThat(firstData.path("operation").asText()).isEqualTo("RENAME");
    assertThat(firstData.at("/before/name").asText()).isEqualTo("Before HTTP");
    assertThat(firstData.at("/after/name").asText()).isEqualTo("HTTP renamed");
    assertThat(firstData.at("/after/revision").asText()).isEqualTo("2");
    assertThat(firstData.at("/after/credentialRevision").asText()).isEqualTo("1");
    var replay = send(session.client(), post(path, key, body, session.csrf()));
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(data(replay)).isEqualTo(firstData);
    String laterBody = "{\"expectedRevision\":\"2\",\"name\":\"Later HTTP\"}";
    assertThat(
            send(session.client(), post(path, UUID.randomUUID(), laterBody, session.csrf()))
                .statusCode())
        .isEqualTo(201);
    var historical = send(session.client(), request(receiptRoot + key).GET());
    assertThat(historical.statusCode()).isEqualTo(200);
    assertThat(data(historical).path("receipt")).isEqualTo(firstData);
    var replayAfterLater = send(session.client(), post(path, key, body, session.csrf()));
    assertThat(replayAfterLater.statusCode()).isEqualTo(200);
    assertThat(data(replayAfterLater)).isEqualTo(firstData);
    assertThat(
            data(send(session.client(), request(receiptRoot + UUID.randomUUID()).GET()))
                .path("receipt")
                .isNull())
        .isTrue();
    assertThat(
            admin.queryForObject(
                "select name from provider_connections where organization_id=? and id=?",
                String.class,
                fixture.org(),
                fixture.connection()))
        .isEqualTo("Later HTTP");

    UUID otherActor = seedHttpAdmin(fixture.org());
    var otherSession = login(otherActor);
    var notOwned = send(otherSession.client(), request(receiptRoot + key).GET());
    assertThat(notOwned.statusCode()).isEqualTo(200);
    assertThat(data(notOwned).path("receipt").isNull()).isTrue();
    byte[] beforeKeyOutage = businessFingerprint(fixture.org());
    byte[] token = Files.readAllBytes(commandTokenFile);
    try {
      Files.delete(commandTokenFile);
      error(
          send(
              session.client(),
              post(
                  path,
                  UUID.randomUUID(),
                  "{\"expectedRevision\":\"3\",\"name\":\"No HMAC write\"}",
                  session.csrf())),
          503,
          "PROVIDER_CONNECTION_UNAVAILABLE");
      var duringOutage = send(session.client(), request(receiptRoot + key).GET());
      assertThat(duringOutage.statusCode()).isEqualTo(200);
      assertThat(data(duringOutage).path("receipt")).isEqualTo(firstData);
      assertThat(Arrays.equals(beforeKeyOutage, businessFingerprint(fixture.org()))).isTrue();
    } finally {
      restoreCommandToken(token);
    }

    byte[] beforeRevocation = businessFingerprint(fixture.org());
    String samplesBeforeRevocation = samples(fixture.org(), fixture.actor());
    admin.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        fixture.org(),
        fixture.actor());
    try (Socket socket = partialUpload(path, session, UUID.randomUUID(), false)) {
      assertThat(readStatus(socket))
          .as("current role denial does not await the body")
          .isEqualTo(403);
    }
    error(
        send(session.client(), post(path, UUID.randomUUID(), "not-json", session.csrf())),
        403,
        "FORBIDDEN");
    error(send(session.client(), request(receiptRoot + key).GET()), 403, "FORBIDDEN");
    assertThat(Arrays.equals(beforeRevocation, businessFingerprint(fixture.org()))).isTrue();
    assertThat(samples(fixture.org(), fixture.actor())).isEqualTo(samplesBeforeRevocation);
    admin.update(
        "update organization_memberships set role='ADMIN' where organization_id=? and account_id=?",
        fixture.org(),
        fixture.actor());
    error(
        send(session.client(), post(path, UUID.randomUUID(), body, session.csrf())),
        409,
        "REVISION_CONFLICT");
    error(
        send(
            session.client(),
            post(
                path,
                UUID.randomUUID(),
                "{\"expectedRevision\":\"3\",\"name\":\"Later HTTP\"}",
                session.csrf())),
        409,
        "NO_CHANGE");
    assertThat(Arrays.equals(retained, fingerprint(fixture.org(), "provider_credential_versions")))
        .isTrue();
    assertThat(
            admin.queryForObject(
                "select count(*) from provider_secret_preparations where organization_id=?",
                Long.class,
                fixture.org()))
        .isZero();
    admin.update(
        "update human_accounts set credential_version=credential_version+1 where id=?",
        fixture.actor());
    error(
        send(session.client(), post(path, UUID.randomUUID(), body, session.csrf())),
        401,
        "UNAUTHORIZED");
    assertThat(send(session.client(), request("/api/v1/auth/session").GET()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void renameIdleUploadsReleaseAndReoccupyAllEightSlotsWithoutCreateEnabled() throws Exception {
    var fixture = seedHttpFixture();
    var session = login(fixture.actor());
    String path = renamePath(fixture);
    byte[] before = businessFingerprint(fixture.org());
    var sockets = new ArrayList<Socket>();
    long started = System.nanoTime();
    try {
      for (int index = 0; index < 8; index++)
        sockets.add(partialUpload(path, session, UUID.randomUUID(), true));
      error(
          send(session.client(), post(path, UUID.randomUUID(), "{}", session.csrf())),
          503,
          "PROVIDER_CONNECTION_UNAVAILABLE");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      for (Socket socket : sockets) {
        setRemainingTimeout(socket, deadline);
        try {
          assertThat(readStatus(socket)).isIn(400, 403, 408);
        } catch (EOFException | SocketException aborted) {
          // A server close is allowed. Client SocketTimeoutException is deliberately not caught.
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            .isGreaterThanOrEqualTo(8000L);
      }
    } finally {
      closeUploads(sockets);
    }
    sockets.clear();
    try {
      for (int index = 0; index < 8; index++)
        sockets.add(partialUpload(path, session, UUID.randomUUID(), true));
      error(
          send(session.client(), post(path, UUID.randomUUID(), "{}", session.csrf())),
          503,
          "PROVIDER_CONNECTION_UNAVAILABLE");
      for (Socket socket : sockets) {
        socket.getOutputStream().write('}');
        socket.getOutputStream().flush();
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      for (Socket socket : sockets) {
        setRemainingTimeout(socket, deadline);
        assertThat(readStatus(socket)).isEqualTo(400);
      }
    } finally {
      closeUploads(sockets);
    }
    assertThat(Arrays.equals(before, businessFingerprint(fixture.org()))).isTrue();
    assertThat(writeAdmissionRows(fixture.org())).isZero();
    var created =
        send(
            session.client(),
            post(
                path,
                UUID.randomUUID(),
                "{\"expectedRevision\":\"1\",\"name\":\"After idle Rename\"}",
                session.csrf()));
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(data(created).at("/after/revision").asText()).isEqualTo("2");
    assertThat(data(created).at("/after/name").asText()).isEqualTo("After idle Rename");
    assertThat(
            admin.queryForObject(
                "select count(*) from provider_secret_preparations where organization_id=?",
                Long.class,
                fixture.org()))
        .isZero();
  }

  private record HttpFixture(UUID org, UUID actor, UUID connection) {}

  private record Session(HttpClient client, CookieManager cookies, String csrf) {}

  private HttpFixture seedHttpFixture() {
    UUID org = UUID.randomUUID(), connection = UUID.randomUUID();
    admin.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Rename"
            + " HTTP',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    UUID actor = seedHttpAdmin(org);
    var human = new HumanIdentity(actor, actor + "@example.test", "Rename HTTP", 0);
    var scope =
        new CredentialBoundary.Scope(org, connection, CredentialBoundary.Provider.OPENAI, 1);
    String digest = Base64.getEncoder().encodeToString(new byte[32]);
    var metadata =
        new CredentialBoundary.Metadata(
            scope,
            "fixture-v1",
            new CredentialBoundary.Reference(scope, "not-a-live-http-credential", "1"),
            digest);
    var prepared =
        new CommandBoundary.Metadata(CommandBoundary.DIGEST_VERSION, "fixture-v1", digest);
    assertThat(
            ProviderCommandRuntime.assemble(administrator())
                .completion()
                .executePrepared(
                    human,
                    org,
                    UUID.randomUUID(),
                    new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Before HTTP", null),
                    prepared,
                    connection,
                    metadata)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    return new HttpFixture(org, actor, connection);
  }

  private UUID seedHttpAdmin(UUID org) {
    UUID actor = UUID.randomUUID();
    admin.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Rename"
            + " HTTP',?)",
        actor,
        actor + "@example.test",
        passwords.encode(PASSWORD));
    admin.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    return actor;
  }

  private Session client() {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    return new Session(
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
        cookies,
        null);
  }

  private Session login(UUID actor) throws Exception {
    var session = client();
    var token = data(send(session.client(), request("/api/v1/auth/csrf").GET()));
    var login =
        request("/api/v1/auth/login")
            .header("X-CSRF-TOKEN", token.path("token").asText())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=" + actor + "%40example.test&password=" + PASSWORD));
    assertThat(send(session.client(), login).statusCode()).isEqualTo(200);
    token = data(send(session.client(), request("/api/v1/auth/csrf").GET()));
    assertThat(token.path("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
    return new Session(session.client(), session.cookies(), token.path("token").asText());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Accept", "application/json");
  }

  private HttpRequest.Builder post(String path, UUID key, String body, String csrf) {
    var request =
        request(path)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body));
    return csrf == null ? request : request.header("X-CSRF-TOKEN", csrf);
  }

  private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isIn(200, 201);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    return json.readTree(response.body()).path("data");
  }

  private void error(HttpResponse<String> response, int status, String code) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var body = json.readTree(response.body());
    assertThat(body.properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder("error", "meta");
    assertThat(body.at("/error/code").asText()).isEqualTo(code);
    assertThat(body.path("error").properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder("code", "message", "details");
    assertThat(body.at("/error/details").isEmpty()).isTrue();
  }

  private String renamePath(HttpFixture fixture) {
    return "/api/v1/organizations/"
        + fixture.org()
        + "/provider-connections/"
        + fixture.connection()
        + "/rename";
  }

  private long writeAdmissionRows(UUID org) {
    return admin.queryForObject(
        "select count(*) from provider_write_admission where organization_id=?", Long.class, org);
  }

  private void restoreCommandToken(byte[] token) throws Exception {
    try {
      if (!Files.exists(commandTokenFile))
        Files.createFile(
            commandTokenFile,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.write(commandTokenFile, token);
    } finally {
      Arrays.fill(token, (byte) 0);
    }
  }

  private Socket partialUpload(String path, Session session, UUID key, boolean acknowledge)
      throws Exception {
    var socket = new Socket();
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
      socket.setSoTimeout(5000);
      String auth = "";
      if (session != null) {
        String cookie =
            String.join(
                "; ",
                session.cookies().getCookieStore().getCookies().stream()
                    .map(value -> value.getName() + "=" + value.getValue())
                    .toList());
        auth = "Cookie: " + cookie + "\r\nX-CSRF-TOKEN: " + session.csrf() + "\r\n";
      }
      String headers =
          "POST "
              + path
              + " HTTP/1.1\r\nHost: 127.0.0.1:"
              + port
              + "\r\n"
              + auth
              + "Idempotency-Key: "
              + key
              + "\r\n"
              + "Accept: application/json\r\n"
              + "Content-Type: application/json\r\n"
              + "Connection: close\r\n"
              + "Content-Length: 2\r\n"
              + (acknowledge ? "Expect: 100-continue\r\n" : "")
              + "\r\n{";
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      if (acknowledge) {
        assertThat(readStatus(socket)).isEqualTo(100);
        assertThat(socket.getInputStream().read()).isEqualTo((int) '\r');
        assertThat(socket.getInputStream().read()).isEqualTo((int) '\n');
      }
      return socket;
    } catch (Exception | Error failure) {
      try {
        socket.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  private static int readStatus(Socket socket) throws IOException {
    var input = socket.getInputStream();
    var status = new StringBuilder();
    for (int index = 0; index < 1024; index++) {
      int next = input.read();
      if (next == -1) throw new EOFException();
      if (next == '\n') {
        String line = status.toString();
        if (!line.matches("HTTP/1[.][01] [0-9]{3} .*\\r"))
          throw new IOException("Invalid disposable Rename HTTP status");
        return Integer.parseInt(line.substring(9, 12));
      }
      status.append((char) next);
    }
    throw new IOException("Oversized disposable Rename HTTP status");
  }

  private static void setRemainingTimeout(Socket socket, long deadline) throws IOException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    assertThat(remaining).as("Rename uploads complete within shared budget").isPositive();
    socket.setSoTimeout((int) remaining);
  }

  private static void closeUploads(Iterable<Socket> sockets) throws IOException {
    IOException failure = null;
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException closed) {
        failure = closed;
      }
    }
    if (failure != null) throw failure;
  }

  private boolean privilege(String role, String operation) {
    return Boolean.TRUE.equals(
        admin.queryForObject(
            "select has_table_privilege(?, 'public.provider_credential_versions', ?)",
            Boolean.class,
            role,
            operation));
  }

  private String samples(UUID org, UUID actor) {
    return admin.queryForObject(
        "select recent_requests from provider_write_admission where organization_id=? and"
            + " actor_id=?",
        String.class,
        org,
        actor);
  }

  private byte[] businessFingerprint(UUID org) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    for (String table :
        new String[] {
          "provider_connections",
          "provider_credential_versions",
          "provider_connection_events",
          "provider_command_receipts",
          "provider_secret_preparations"
        }) {
      digest.update(fingerprint(org, table));
    }
    return digest.digest();
  }

  private byte[] fingerprint(UUID org, String table) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    for (String row :
        admin.queryForList(
            "select to_jsonb(t)::text from " + table + " t where organization_id=? order by 1",
            String.class,
            org)) {
      digest.update(row.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    return digest.digest();
  }

  private static DataSource administrator() {
    var datasource = new DriverManagerDataSource();
    datasource.setUrl(required("AGENTOS_TEST_JOURNAL_URL"));
    datasource.setUsername("vault_journal_test");
    try {
      datasource.setPassword(
          Files.readString(Path.of(required("AGENTOS_TEST_PG_PASSWORD_FILE"))).strip());
    } catch (Exception failure) {
      throw new IllegalStateException("Disposable Rename administrator unavailable");
    }
    return datasource;
  }

  private static void provisionPrincipals() {
    try (var connection = administrator().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "create role "
              + READER
              + " login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit"
              + " password '"
              + READER_PASSWORD
              + "'");
      String password = UUID.randomUUID().toString();
      writerPasswordFile = privateFile("rename-writer-");
      Files.writeString(writerPasswordFile, password);
      commandTokenFile = privateFile("rename-command-");
      byte[] commandToken =
          Files.readAllBytes(Path.of(required("AGENTOS_TEST_RENAME_COMMAND_TOKEN_FILE")));
      try {
        Files.write(commandTokenFile, commandToken);
      } finally {
        Arrays.fill(commandToken, (byte) 0);
      }
      statement.execute(
          "create role "
              + WRITER
              + " login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit"
              + " password '"
              + password
              + "'");
      statement.execute("grant usage on schema public to " + READER + "," + WRITER);
      statement.execute(
          "grant select on public.organizations,public.human_accounts,"
              + "public.organization_memberships,public.provider_connection_events,"
              + "public.provider_command_receipts to "
              + READER);
      statement.execute("grant update(id) on public.human_accounts to " + READER);
      statement.execute(
          "grant select,insert,update on public.authentication_attempts,"
              + "public.provider_read_admission to "
              + READER);
      statement.execute("grant insert on public.authentication_audit to " + READER);
      statement.execute(
          "grant select,insert,update,delete on public.spring_session,"
              + "public.spring_session_attributes to "
              + READER);
      statement.execute(
          "grant select on public.organizations,public.human_accounts,"
              + "public.organization_memberships,public.provider_deployment_policy,"
              + "public.provider_connections,public.provider_credential_versions,"
              + "public.provider_connection_events,public.provider_command_receipts,"
              + "public.provider_write_admission to "
              + WRITER);
      statement.execute(
          "grant update(id) on public.organizations,public.human_accounts to " + WRITER);
      statement.execute("grant update(account_id) on public.organization_memberships to " + WRITER);
      statement.execute("grant update(id) on public.provider_deployment_policy to " + WRITER);
      statement.execute("grant update on public.provider_connections to " + WRITER);
      statement.execute(
          "grant insert on public.provider_connection_events,public.provider_command_receipts,"
              + "public.provider_write_admission to "
              + WRITER);
      statement.execute("grant update on public.provider_write_admission to " + WRITER);
    } catch (Exception failure) {
      throw new IllegalStateException("Disposable Rename principal provisioning failed");
    }
  }

  private static Path privateFile(String prefix) throws Exception {
    Path file =
        Files.createTempFile(
            prefix,
            ".token",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    file.toFile().deleteOnExit();
    return file;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Disposable Rename fixture unavailable");
    return value;
  }
}

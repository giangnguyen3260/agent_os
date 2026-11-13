package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providerrename.ProviderRenameCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "agentos.provider-rename.http-enabled=true",
      "agentos.provider-enable.http-enabled=true",
      "agentos.provider-list.enabled=true"
    })
@ActiveProfiles("test")
class ProviderRenameHttpPostgresIT {
  private static final String PASSWORD = "Rename-http-fixture-93847";
  private static final String BODY = "{\"expectedRevision\":\"1\",\"name\":\" Fixture renamed \"}";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder passwords;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ProviderReceiptService receipts;
  @Autowired ProviderConnectionService connections;
  @MockBean ProviderRenameCoordinator coordinator;

  @MockBean(name = "providerEnableCoordinator")
  ProviderMetadataCoordinator enableCoordinator;

  private final Set<UUID> seededOrganizations = new HashSet<>();
  private final Set<UUID> seededActors = new HashSet<>();

  @AfterEach
  void removeOnlyThisTestsSeededRows() {
    for (UUID org : seededOrganizations) {
      for (String table :
          new String[] {
            "provider_command_receipts", "provider_connection_events",
                "provider_secret_preparations",
            "provider_connections", "provider_credential_versions", "provider_write_admission",
            "provider_read_admission", "organization_memberships"
          }) {
        jdbc.update("delete from " + table + " where organization_id=?", org);
      }
    }
    for (UUID actor : seededActors) {
      jdbc.update("delete from spring_session where principal_name=?", actor.toString());
      jdbc.update("delete from authentication_audit where account_id=?", actor);
      jdbc.update("delete from human_accounts where id=?", actor);
    }
    for (UUID org : seededOrganizations) jdbc.update("delete from organizations where id=?", org);
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> ProviderPolicyPostgresIT.environment("JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> ProviderPolicyPostgresIT.environment("DB_USER"));
    properties.add(
        "spring.datasource.password", () -> ProviderPolicyPostgresIT.environment("DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void enableIngressChecksSessionAndCsrfAndPassesOnlyAnEnableCommand() throws Exception {
    UUID actor = UUID.randomUUID(), org = UUID.randomUUID(), target = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    seedOrganization(org);
    seedAdmin(actor, org);
    var client = client();
    String path = path(org, target).replaceFirst("/rename$", "/enable");
    String body = "{\"expectedRevision\":\"2\"}";
    error(send(client, post(path, key, body)), 401, "UNAUTHORIZED");
    login(client, actor);
    error(send(client, post(path, key, body)), 403, "FORBIDDEN");
    var token = csrf(client);
    String header = token.path("headerName").asText(), value = token.path("token").asText();
    error(send(client, post(path, key, BODY).header(header, value)), 400, "BAD_REQUEST");
    verifyNoInteractions(enableCoordinator);
    var command = new RevisionCommand(Operation.ENABLE, target, 2);
    when(enableCoordinator.enable(any(HumanIdentity.class), eq(org), eq(key), eq(command)))
        .thenReturn(
            new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.CREDENTIAL_UNAVAILABLE)));
    error(send(client, post(path, key, body).header(header, value)), 409, "CREDENTIAL_UNAVAILABLE");
    verify(enableCoordinator).enable(any(HumanIdentity.class), eq(org), eq(key), eq(command));
  }

  @Test
  void sessionAuthorityCsrfAndStrictWirePrecedeRenameCoordinator() throws Exception {
    UUID actor = UUID.randomUUID(), org = UUID.randomUUID(), target = UUID.randomUUID();
    UUID key = UUID.randomUUID(), foreignOrg = UUID.randomUUID();
    seedOrganization(org);
    seedOrganization(foreignOrg);
    seedAdmin(actor, org);
    var client = client();
    String path = path(org, target);
    var anonymous = send(client, post(path, key, BODY));
    error(anonymous, 401, "UNAUTHORIZED");
    assertThat(anonymous.headers().allValues("set-cookie")).isEmpty();
    login(client, actor);
    int policyAdded =
        jdbc.update(
            "insert into provider_deployment_policy"
                + " (id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)"
                + " values (1,1,true,false,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) on conflict"
                + " (id) do nothing");
    try {
      var options =
          send(
              client,
              request("/api/v1/organizations/" + org + "/provider-connections/options").GET());
      assertThat(options.statusCode()).isEqualTo(200);
      assertThat(options.headers().firstValue("X-Provider-Rename-Enabled")).contains("true");
      assertThat(options.headers().firstValue("X-Provider-Enable-Enabled")).contains("true");
      assertThat(options.headers().firstValue("X-Provider-Create-Enabled")).contains("false");
    } finally {
      if (policyAdded == 1) jdbc.update("delete from provider_deployment_policy where id=1");
    }
    var token = csrf(client);
    String header = token.path("headerName").asText(), value = token.path("token").asText();
    String foreign = csrf(client()).path("token").asText();
    membership(actor, org, "MEMBER");
    // No CSRF and malformed body: current role denial takes precedence over both.
    error(send(client, post(path, key, "malformed")), 403, "FORBIDDEN");
    membership(actor, org, "ADMIN");
    error(
        send(client, post(path(foreignOrg, target), key, BODY).header(header, value)),
        403,
        "FORBIDDEN");
    error(send(client, post(path, key, BODY)), 403, "FORBIDDEN");
    error(send(client, post(path, key, BODY).header(header, foreign)), 403, "FORBIDDEN");
    error(
        send(client, post(path, key, BODY).headers(header, value, header, value)),
        403,
        "FORBIDDEN");
    error(
        send(client, post(path + "?extra=1", key, BODY).header(header, value)), 400, "BAD_REQUEST");
    error(send(client, post(path, key, "{malformed").header(header, value)), 400, "BAD_REQUEST");
    error(
        send(client, post(path, key, BODY.replace("\"1\"", "1")).header(header, value)),
        400,
        "BAD_REQUEST");
    error(
        send(client, post(path, key, "x".repeat(8193)).header(header, value)), 400, "BAD_REQUEST");
    error(
        send(
            client,
            post(path, key, BODY)
                .header(header, value)
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(new byte[8193])))),
        400,
        "BAD_REQUEST");
    error(
        send(
            client,
            post(path, key, BODY).header(header, value).header("Idempotency-Key", key.toString())),
        400,
        "BAD_REQUEST");
    error(
        send(
            client,
            post(path, key, BODY).header(header, value).header("Content-Type", "application/json")),
        400,
        "BAD_REQUEST");
    error(
        send(client, post(path, key, BODY).header(header, value).header("Accept", "text/html")),
        400,
        "BAD_REQUEST");
    error(
        send(
            client,
            post(path.replace(target.toString(), target.toString().replace("-", "")), key, BODY)
                .header(header, value)),
        400,
        "BAD_REQUEST");
    error(
        send(
            client,
            post(
                    path.replace(
                        target.toString(),
                        "%"
                            + Integer.toHexString(target.toString().charAt(0))
                            + target.toString().substring(1)),
                    key,
                    BODY)
                .header(header, value)),
        400,
        "BAD_REQUEST");
    verifyNoInteractions(coordinator);

    when(coordinator.rename(any(), eq(org), eq(key), any()))
        .thenAnswer(
            call -> {
              HumanIdentity human = call.getArgument(0);
              CommandBoundary.Rename command = call.getArgument(3);
              assertThat(human.id()).isEqualTo(actor);
              assertThat(command.target()).isEqualTo(target);
              assertThat(command.expectedRevision()).isEqualTo(1);
              assertThat(command.name()).isEqualTo("Fixture renamed");
              return new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.RATE_LIMITED, 7));
            });
    var limited = send(client, post(path, key, BODY).header(header, value));
    error(limited, 429, "RATE_LIMITED");
    assertThat(limited.headers().firstValue("Retry-After")).contains("7");
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", actor);
    error(send(client, post(path, key, BODY).header(header, value)), 401, "UNAUTHORIZED");
    assertThat(send(client, request("/api/v1/auth/session").GET()).statusCode()).isEqualTo(401);
    verify(coordinator).rename(any(), eq(org), eq(key), any());
  }

  @Test
  void projectsConfirmedPersistedRenameAndReplayWithoutCurrentStateLeakage() throws Exception {
    UUID org = UUID.randomUUID(), actor = UUID.randomUUID(), target = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    seedOrganization(org);
    seedAdmin(actor, org);
    var human = new HumanIdentity(actor, actor + "@example.test", "Rename HTTP", 0);
    var create =
        new CommandBoundary.Create(
            CommandBoundary.Provider.OPENAI,
            CommandBoundary.CredentialMode.OPENAI_API_KEY,
            "Original",
            null);
    String digest = Base64.getEncoder().encodeToString(new byte[32]);
    var prepared = new CommandBoundary.Metadata(CommandBoundary.DIGEST_VERSION, "key-v1", digest);
    var scope = new CredentialBoundary.Scope(org, target, CredentialBoundary.Provider.OPENAI, 1);
    var credential =
        new CredentialBoundary.Metadata(
            scope,
            "key-v1",
            new CredentialBoundary.Reference(scope, "rename-http-fixture-opaque", "1"),
            digest);
    var completion = new ProviderCompletionBoundary(transactions, jdbc, receipts);
    assertThat(
            completion
                .executePrepared(
                    human, org, UUID.randomUUID(), create, prepared, target, credential)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    var rename = new CommandBoundary.Rename(target, 1, "Fixture renamed");
    var committed = completion.executePrepared(human, org, key, rename, prepared, null, null);
    assertThat(committed.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(
            connections
                .rename(human, org, target, 2, "Changed after historical receipt")
                .revision())
        .isEqualTo(3);
    // The native managed fixture separately proves real coordinator invocation. This mocks only
    // its already-confirmed result to isolate security ingress and public historical projection.
    when(coordinator.rename(any(), eq(org), eq(key), eq(rename)))
        .thenReturn(
            committed,
            new WriteOutcome(
                WriteState.COMMITTED,
                new ProviderReceiptService.Result(committed.result().receipt(), true),
                null));
    var client = client();
    login(client, actor);
    var token = csrf(client);
    String header = token.path("headerName").asText(), value = token.path("token").asText();
    var first = send(client, post(path(org, target), key, BODY).header(header, value));
    var replay = send(client, post(path(org, target), key, BODY).header(header, value));
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(replay.statusCode()).isEqualTo(200);
    for (var response : java.util.List.of(first, replay)) {
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(response.headers().firstValue("Content-Type").orElse(""))
          .startsWith("application/json");
      assertThat(response.headers().firstValue("Retry-After")).isEmpty();
      var envelope = json.readTree(response.body());
      assertThat(envelope.size()).isEqualTo(2);
      assertThat(UUID.fromString(envelope.at("/meta/requestId").asText())).isNotNull();
      assertThat(envelope.at("/data/operation").asText()).isEqualTo("RENAME");
      assertThat(envelope.at("/data/before/name").asText()).isEqualTo("Original");
      assertThat(envelope.at("/data/after/name").asText()).isEqualTo("Fixture renamed");
      assertThat(envelope.at("/data/after/revision").asText()).isEqualTo("2");
      assertThat(response.body())
          .doesNotContain(
              "Changed after historical receipt",
              "rename-http-fixture-opaque",
              digest,
              key.toString());
    }
    assertThat(json.readTree(first.body()).path("data"))
        .isEqualTo(json.readTree(replay.body()).path("data"));
    verify(coordinator, times(2)).rename(any(), eq(org), eq(key), eq(rename));
  }

  private static String path(UUID org, UUID target) {
    return "/api/v1/organizations/" + org + "/provider-connections/" + target + "/rename";
  }

  private void seedOrganization(UUID org) {
    seededOrganizations.add(org);
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Rename HTTP',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
  }

  private void seedAdmin(UUID actor, UUID org) {
    seededActors.add(actor);
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'Rename HTTP',?)",
        actor,
        actor + "@example.test",
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
  }

  private void login(HttpClient client, UUID actor) throws Exception {
    var loginToken = csrf(client);
    var login =
        request("/api/v1/auth/login")
            .header(loginToken.get("headerName").asText(), loginToken.get("token").asText())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=" + actor + "%40example.test&password=" + PASSWORD));
    assertThat(send(client, login).statusCode()).isEqualTo(200);
  }

  private static HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  private JsonNode csrf(HttpClient client) throws Exception {
    var response = send(client, request("/api/v1/auth/csrf").GET());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body()).path("data");
  }

  private void membership(UUID actor, UUID org, String role) {
    jdbc.update(
        "update organization_memberships set role=? where account_id=? and organization_id=?",
        role,
        actor,
        org);
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(20));
  }

  private HttpRequest.Builder post(String path, UUID key, String body) {
    return request(path)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", key.toString())
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder)
      throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void error(HttpResponse<String> response, int status, String code) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    var body = json.readTree(response.body());
    assertThat(body.at("/error/code").asText()).isEqualTo(code);
    assertThat(body.path("error").size()).isEqualTo(3);
    assertThat(body.at("/error/details").isObject()).isTrue();
    assertThat(body.at("/error/details").isEmpty()).isTrue();
    assertThat(body.path("meta").size()).isEqualTo(1);
    if (status != 429) assertThat(response.headers().firstValue("Retry-After")).isEmpty();
    assertThat(body.has("data")).isFalse();
    assertThat(body.size()).isEqualTo(2);
    assertThat(UUID.fromString(body.at("/meta/requestId").asText())).isNotNull();
  }
}

package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providertransport.ProviderCreateBody;
import com.agentos.providertransport.ProviderCreateResponses;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"agentos.provider-create.enabled=true", "agentos.provider-list.enabled=true"})
@ActiveProfiles("test")
class ProviderCreateHttpPostgresIT {
  private static final String PASSWORD = "Create-http-fixture-93847";
  private static final String SECRET = "fixture-api-key";
  private static final String CREATE =
      """
      {"provider":"OPENAI","credentialMode":"OPENAI_API_KEY","name":"Fixture","options":{},"apiKey":"fixture-api-key"}
      """;

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder passwords;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ProviderReceiptService receipts;
  @Autowired ProviderConnectionService connections;
  @MockBean ProviderCreateCoordinator coordinator;

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
  void sessionAuthorityCsrfAndWireGatesPrecedeTheCommand() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID org = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    seedOrganization(org);
    seedAdmin(actor, org);

    var client = client();
    String path = "/api/v1/organizations/" + org + "/provider-connections";
    var anonymous = send(client, post(path, key, CREATE));
    error(anonymous, 401, "UNAUTHORIZED");
    assertThat(anonymous.headers().allValues("set-cookie")).isEmpty();

    login(client, actor);
    var token = csrf(client);
    String header = token.get("headerName").asText();
    String value = token.get("token").asText();
    var foreign = csrf(client()).get("token").asText();

    membership(actor, org, "MEMBER");
    error(send(client, post(path, key, CREATE).header(header, value)), 403, "FORBIDDEN");
    membership(actor, org, "ADMIN");
    error(send(client, post(path, key, CREATE)), 403, "FORBIDDEN");
    error(send(client, post(path, key, CREATE).header(header, foreign)), 403, "FORBIDDEN");
    error(
        send(client, post(path, key, CREATE).headers(header, value, header, value)),
        403,
        "FORBIDDEN");
    error(
        send(client, post(path + "?unexpected=1", key, CREATE).header(header, value)),
        400,
        "BAD_REQUEST");
    error(send(client, post(path, key, "{malformed").header(header, value)), 400, "BAD_REQUEST");
    error(
        send(client, post(path, key, "x".repeat(8193)).header(header, value)), 400, "BAD_REQUEST");
    error(
        send(
            client,
            post(path, key, CREATE)
                .header(header, value)
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(new byte[8193])))),
        400,
        "BAD_REQUEST");
    error(send(client, request(path + "?unexpected=1").GET()), 400, "BAD_REQUEST");
    error(send(client, request(path).DELETE()), 405, "METHOD_NOT_ALLOWED");
    verifyNoInteractions(coordinator);

    var borrowed = new AtomicReference<byte[]>();
    when(coordinator.create(any(), eq(org), eq(key), any()))
        .thenAnswer(
            call -> {
              HumanIdentity human = call.getArgument(0);
              ProviderCreateBody body = call.getArgument(3);
              assertThat(human.id()).isEqualTo(actor);
              assertThat(body.command().name()).isEqualTo("Fixture");
              borrowed.set(body.credential());
              return new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.RATE_LIMITED, 7));
            });
    var limited = send(client, post(path, key, CREATE).header(header, value));
    error(limited, 429, "RATE_LIMITED");
    assertThat(limited.headers().firstValue("Retry-After")).contains("7");
    assertThat(borrowed.get()).isEqualTo(new byte[SECRET.length()]);

    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", actor);
    error(send(client, post(path, key, CREATE).header(header, value)), 401, "UNAUTHORIZED");
    assertThat(send(client, request("/api/v1/auth/session").GET()).statusCode()).isEqualTo(401);
    verify(coordinator).create(any(), eq(org), eq(key), any());
  }

  @Test
  void receiptLookupUsesCurrentSessionAndOnlyReturnsOwnCommittedCreate() throws Exception {
    UUID org = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID anotherAdmin = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    UUID connection = UUID.randomUUID();
    seedOrganization(org);
    seedAdmin(actor, org);
    seedAdmin(anotherAdmin, org);

    var human = new HumanIdentity(actor, actor + "@example.test", "Create HTTP", 0);
    var command =
        new CommandBoundary.Create(
            CommandBoundary.Provider.OPENAI,
            CommandBoundary.CredentialMode.OPENAI_API_KEY,
            "Historical receipt fixture",
            null);
    String digest = Base64.getEncoder().encodeToString(new byte[32]);
    var prepared = new CommandBoundary.Metadata(CommandBoundary.DIGEST_VERSION, "key-v1", digest);
    var scope =
        new CredentialBoundary.Scope(org, connection, CredentialBoundary.Provider.OPENAI, 1);
    var reference = new CredentialBoundary.Reference(scope, "fixture-opaque-reference", "1");
    var credential = new CredentialBoundary.Metadata(scope, "key-v1", reference, digest);
    var committed =
        new ProviderCompletionBoundary(transactions, jdbc, receipts)
            .executePrepared(human, org, key, command, prepared, connection, credential);
    assertThat(committed.state()).isEqualTo(WriteState.COMMITTED);
    var createResponse = new MockHttpServletResponse();
    ProviderCreateResponses.writeOutcome(json, createResponse, committed);
    assertThat(createResponse.getStatus()).isEqualTo(201);
    var original = json.readTree(createResponse.getContentAsByteArray()).path("data");
    assertThat(connections.rename(human, org, connection, 1, "Renamed after receipt").revision())
        .isEqualTo(2);

    String base = "/api/v1/organizations/" + org + "/provider-connections/receipts/";
    String path = base + key;
    var anonymous = send(client(), request(path).GET());
    error(anonymous, 401, "UNAUTHORIZED");
    assertThat(anonymous.headers().allValues("set-cookie")).isEmpty();

    var owner = client();
    login(owner, actor);
    var found = receiptData(send(owner, request(path).GET()));
    assertThat(found).isEqualTo(original);
    assertThat(found.path("actorId").asText()).isEqualTo(actor.toString());
    assertThat(found.at("/after/name").asText()).isEqualTo("Historical receipt fixture");
    assertThat(found.at("/after/revision").asText()).isEqualTo("1");
    assertThat(found.toString()).doesNotContain("fixture-opaque-reference", digest, key.toString());

    assertThat(receiptData(send(owner, request(base + UUID.randomUUID()).GET())).isNull()).isTrue();
    var other = client();
    login(other, anotherAdmin);
    assertThat(receiptData(send(other, request(path).GET())).isNull()).isTrue();

    error(send(owner, request(base + "not-a-key").GET()), 400, "BAD_REQUEST");
    error(send(owner, request(path + "?extra=1").GET()), 400, "BAD_REQUEST");
    var alias = send(owner, request(path.replace("/receipts/", "/%72eceipts/")).GET());
    assertThat(alias.statusCode()).isIn(400, 403, 404);
    assertThat(alias.body()).doesNotContain("Historical receipt fixture");
    error(
        send(owner, request(path).method("GET", HttpRequest.BodyPublishers.ofString(SECRET))),
        400,
        "BAD_REQUEST");
    String updateDigest =
        "update provider_command_receipts set command_digest=?"
            + " where organization_id=? and actor_id=? and idempotency_key=?";
    assertThat(jdbc.update(updateDigest, "invalid-fixture-digest", org, actor, key)).isEqualTo(1);
    try {
      var unavailable = send(owner, request(path).GET());
      error(unavailable, 503, "PROVIDER_CONNECTION_UNAVAILABLE");
      assertThat(unavailable.body()).doesNotContain("invalid-fixture-digest");
    } finally {
      jdbc.update(updateDigest, digest, org, actor, key);
    }
    membership(actor, org, "MEMBER");
    error(send(owner, request(path).GET()), 403, "FORBIDDEN");
    verifyNoInteractions(coordinator);
  }

  private JsonNode receiptData(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    var body = json.readTree(response.body());
    assertThat(body.size()).isEqualTo(2);
    assertThat(UUID.fromString(body.at("/meta/requestId").asText())).isNotNull();
    assertThat(body.path("data").size()).isEqualTo(1);
    assertThat(body.path("data").has("receipt")).isTrue();
    return body.at("/data/receipt");
  }

  private void seedOrganization(UUID org) {
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Create HTTP',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
  }

  private void seedAdmin(UUID actor, UUID org) {
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'Create HTTP',?)",
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
    assertThat(body.at("/error/details").isEmpty()).isTrue();
    assertThat(body.has("data")).isFalse();
    assertThat(response.body()).doesNotContain(SECRET);
  }
}

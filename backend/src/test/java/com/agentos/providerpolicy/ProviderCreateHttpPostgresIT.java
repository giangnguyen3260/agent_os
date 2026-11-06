package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providertransport.ProviderCreateBody;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
  @MockitoBean ProviderCreateCoordinator coordinator;

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
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Create HTTP',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
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

    var client = client();
    String path = "/api/v1/organizations/" + org + "/provider-connections";
    var anonymous = send(client, post(path, key, CREATE));
    error(anonymous, 401, "UNAUTHORIZED");
    assertThat(anonymous.headers().allValues("set-cookie")).isEmpty();

    var loginToken = csrf(client);
    var login =
        request("/api/v1/auth/login")
            .header(loginToken.get("headerName").asText(), loginToken.get("token").asText())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=" + actor + "%40example.test&password=" + PASSWORD));
    assertThat(send(client, login).statusCode()).isEqualTo(200);
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

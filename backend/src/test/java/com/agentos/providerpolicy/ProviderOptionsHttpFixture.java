package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

abstract class ProviderOptionsHttpFixture {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired PolicyService policies;
  @Autowired PasswordEncoder passwords;
  UUID org, actor;
  HttpClient client;
  private static final String PASSWORD = "Options-test-password-93847";

  @BeforeEach
  void seed() throws Exception {
    OptionsCompletionFaults.FAIL_NEXT.set(null);
    jdbc.update("delete from provider_read_admission");
    jdbc.update("delete from provider_deployment_policy");
    org = UUID.randomUUID();
    actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Options',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Options',?)",
        actor,
        actor + "@example.test",
        passwords.encode(PASSWORD));
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    client = newClient();
  }

  HttpClient newClient() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  String path() {
    return "/api/v1/organizations/" + org + "/provider-connections/options";
  }

  HttpResponse<String> request(
      HttpClient agent, String method, String path, String body, String accept) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20));
    if (accept != null) builder.header("Accept", accept);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return agent.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  void login() throws Exception {
    var csrf = request(client, "GET", "/api/v1/auth/csrf", null, null);
    assertThat(csrf.statusCode()).isEqualTo(200);
    JsonNode token = json.readTree(csrf.body()).get("data");
    var login =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header(token.get("headerName").asText(), token.get("token").asText())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username="
                        + URLEncoder.encode(actor + "@example.test", StandardCharsets.UTF_8)
                        + "&password="
                        + PASSWORD))
            .build();
    var response = client.send(login, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("set-cookie"))
        .anyMatch(value -> value.startsWith("SESSION="));
    assertThat(request(client, "GET", "/api/v1/auth/session", null, null).statusCode())
        .isEqualTo(200);
  }

  void error(HttpResponse<String> response, int status, String code) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    var node = json.readTree(response.body());
    assertThat(node.size()).isEqualTo(2);
    assertThat(node.get("error").size()).isEqualTo(3);
    assertThat(node.at("/error/code").asText()).isEqualTo(code);
    assertThat(node.at("/error/details").size()).isZero();
    assertThat(node.at("/meta/requestId").asText()).isNotBlank();
    assertThat(node.at("/error/message").asText()).isEqualTo(OptionsResponses.message(status));
  }

  long slots() {
    var rows =
        jdbc.queryForList(
            "select recent_requests from provider_read_admission where organization_id=? and"
                + " actor_id=?",
            String.class,
            org,
            actor);
    return rows.isEmpty() ? 0 : AdmissionService.parse(rows.get(0), Long.MAX_VALUE).size();
  }

  List<Map<String, Object>> business() {
    var result = new java.util.ArrayList<Map<String, Object>>();
    for (String table :
        List.of(
            "provider_connections",
            "provider_credential_versions",
            "provider_connection_events",
            "provider_command_receipts"))
      result.addAll(jdbc.queryForList("select * from " + table));
    return result;
  }

  @Test
  void actualLoginExactProjectionAndNoBusinessMutation() throws Exception {
    login();
    policies.provision(Set.of(Provider.OPENAI, Provider.GOOGLE_GEMINI));
    var before = business();
    var policy = jdbc.queryForList("select * from provider_deployment_policy");
    var response = request(client, "GET", path(), null, null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var data = json.readTree(response.body()).get("data");
    assertThat(data.size()).isEqualTo(3);
    assertThat(data.get("organizationId").asText()).isEqualTo(org.toString());
    assertThat(data.get("policyRevision").isTextual()).isTrue();
    assertThat(data.get("policyRevision").asText()).isEqualTo("1");
    assertThat(data.get("providers"))
        .isEqualTo(
            json.readTree(
                "[{\"provider\":\"OPENAI\",\"credentialMode\":\"OPENAI_API_KEY\",\"enabled\":true,\"optionsSchema\":\"EMPTY\"},{\"provider\":\"ANTHROPIC\",\"credentialMode\":\"ANTHROPIC_API_KEY\",\"enabled\":false,\"optionsSchema\":\"ANTHROPIC_WORKSPACE\"},{\"provider\":\"GOOGLE_GEMINI\",\"credentialMode\":\"GEMINI_API_KEY\",\"enabled\":true,\"optionsSchema\":\"EMPTY\"}]"));
    assertThat(business()).isEqualTo(before);
    assertThat(jdbc.queryForList("select * from provider_deployment_policy")).isEqualTo(policy);
    assertThat(slots()).isEqualTo(1);
    policies.replace(1, Set.of(Provider.ANTHROPIC));
    assertThat(
            json.readTree(request(client, "GET", path(), null, null).body())
                .at("/data/policyRevision")
                .asText())
        .isEqualTo("2");
  }

  @Test
  void authorityPrecedesWireAndAdmission() throws Exception {
    error(request(client, "GET", path() + "?bad", null, "text/html"), 401, "UNAUTHORIZED");
    assertThat(slots()).isZero();
    login();
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    error(request(client, "GET", path() + "?bad", "body", "text/html"), 403, "FORBIDDEN");
    assertThat(slots()).isZero();
    error(
        request(
            client,
            "GET",
            path().replace(org.toString(), UUID.randomUUID().toString()),
            null,
            null),
        403,
        "FORBIDDEN");
  }

  @Test
  void staleAndDisabledSessionsAreInvalidated() throws Exception {
    login();
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", actor);
    error(request(client, "GET", path() + "?bad", null, null), 401, "UNAUTHORIZED");
    assertThat(request(client, "GET", "/api/v1/auth/session", null, null).statusCode())
        .isEqualTo(401);
    login();
    jdbc.update("update human_accounts set enabled=false where id=?", actor);
    error(request(client, "GET", path(), null, null), 401, "UNAUTHORIZED");
    assertThat(slots()).isZero();
  }

  @Test
  void unsupportedMethodsAndHeadNeverConsumeQuota() throws Exception {
    login();
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
      var response = request(client, method, path(), "body", null);
      error(response, 405, "METHOD_NOT_ALLOWED");
      assertThat(response.headers().firstValue("Allow")).contains("GET");
    }
    var head = request(client, "HEAD", path(), null, null);
    assertThat(head.statusCode()).isEqualTo(405);
    assertThat(head.body()).isEmpty();
    assertThat(head.headers().firstValue("Allow")).contains("GET");
    head = request(newClient(), "HEAD", path(), null, null);
    assertThat(head.statusCode()).isEqualTo(401);
    assertThat(head.body()).isEmpty();
    assertThat(head.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(slots()).isZero();
  }

  @Test
  void strictWireConsumesSlotsAndAcceptSpecificityIsRespected() throws Exception {
    login();
    policies.provision(Set.of(Provider.OPENAI));
    error(rawGet(path() + "?"), 400, "BAD_REQUEST");
    error(request(client, "GET", path() + "?x=1", null, null), 400, "BAD_REQUEST");
    error(request(client, "GET", path(), "{}", null), 400, "BAD_REQUEST");
    error(
        request(
            client,
            "GET",
            path().replace(org.toString(), org.toString().toUpperCase(java.util.Locale.ROOT)),
            null,
            null),
        400,
        "BAD_REQUEST");
    for (String accept :
        List.of(
            "text/html",
            "application/json;q=0, */*;q=1",
            "application/json;q=wat",
            "application/json;q=1.1",
            "application/json, application/json;q=0",
            "application/json,",
            "application/json;q=0;q=1",
            "application/json;q=1e0",
            "application/json;profile=a;PROFILE=b",
            "application/json;charset=UTF-8;CHARSET=UTF-8",
            "application/json;q=0.0001",
            "application/json;q=+1",
            "",
            " ")) error(request(client, "GET", path(), null, accept), 400, "BAD_REQUEST");
    assertThat(slots()).isEqualTo(18);
    for (String accept :
        List.of(
            "application/json",
            "application/*;q=0.5",
            "*/*",
            "application/json;q=1, */*;q=0",
            "application/json;profile=\"x;q=1\""))
      assertThat(request(client, "GET", path(), null, accept).statusCode()).isEqualTo(200);
  }

  @Test
  void quota121BeatsMalformedWireAndDoesNotChangeRow() throws Exception {
    login();
    policies.provision(Set.of(Provider.OPENAI));
    for (int i = 0; i < 120; i++)
      assertThat(request(client, "GET", path(), null, null).statusCode()).isEqualTo(200);
    var before = jdbc.queryForList("select * from provider_read_admission");
    var response = request(client, "GET", path() + "?invalid", null, "text/html");
    error(response, 429, "RATE_LIMITED");
    assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()))
        .isBetween(1, 60);
    assertThat(jdbc.queryForList("select * from provider_read_admission")).isEqualTo(before);
  }

  @Test
  void missingPolicyAndCorruptQuotaAreSafeUnavailable() throws Exception {
    login();
    error(request(client, "GET", path(), null, null), 503, "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(slots()).isEqualTo(1);
    jdbc.update(
        "update provider_read_admission set recent_requests='[null]' where organization_id=? and"
            + " actor_id=?",
        org,
        actor);
    var before = jdbc.queryForList("select * from provider_read_admission");
    error(request(client, "GET", path(), null, null), 503, "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(jdbc.queryForList("select * from provider_read_admission")).isEqualTo(before);
  }

  @Test
  void separateAdmissionAndReadCommitUncertaintyNeverReleasesData() throws Exception {
    login();
    policies.provision(Set.of(Provider.OPENAI));
    for (String operation : List.of("AdmissionService.acquire", "PolicyService.options")) {
      long before = slots();
      OptionsCompletionFaults.FAIL_NEXT.set("com.agentos.providerpolicy." + operation);
      var response = request(client, "GET", path(), null, null);
      error(response, 503, "PROVIDER_CONNECTION_UNAVAILABLE");
      assertThat(response.body())
          .doesNotContain(
              "commit-sensitive-SQL-sentinel", "organizationId", "policyRevision", "providers");
      assertThat(slots()).isEqualTo(before + 1);
      assertThat(OptionsCompletionFaults.FAIL_NEXT.get()).isNull();
    }
  }

  @Test
  void hugeStoredArrayReturnsSafeUnavailableWithoutChangingSecurityRow() throws Exception {
    login();
    policies.provision(Set.of(Provider.OPENAI));
    assertThat(request(client, "GET", path(), null, null).statusCode()).isEqualTo(200);
    String oversized = "[" + String.join(",", java.util.Collections.nCopies(1000, "0")) + "]";
    jdbc.update(
        "update provider_read_admission set recent_requests=? where organization_id=? and"
            + " actor_id=?",
        oversized,
        org,
        actor);
    var before = jdbc.queryForList("select * from provider_read_admission");
    error(request(client, "GET", path(), null, null), 503, "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(jdbc.queryForList("select * from provider_read_admission")).isEqualTo(before);
  }

  /** Preserve the exact request-target: Java HttpClient can drop a trailing empty query. */
  private HttpResponse<String> rawGet(String target) throws Exception {
    URI uri = URI.create("http://localhost:" + port + target);
    var cookies = (CookieManager) client.cookieHandler().orElseThrow();
    var wire =
        new StringBuilder("GET ")
            .append(target)
            .append(" HTTP/1.1\r\nHost: localhost:")
            .append(port)
            .append("\r\nConnection: close\r\n");
    for (var header : cookies.get(uri, Map.of()).entrySet())
      for (String value : header.getValue())
        wire.append(header.getKey()).append(": ").append(value).append("\r\n");
    wire.append("\r\n");
    try (var socket = new java.net.Socket("localhost", port)) {
      socket.setSoTimeout(20_000);
      socket.getOutputStream().write(wire.toString().getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var input = socket.getInputStream();
      String status = wireLine(input);
      int code = Integer.parseInt(status.split(" ")[1]);
      var headers = new java.util.LinkedHashMap<String, List<String>>();
      String line;
      while (!(line = wireLine(input)).isEmpty()) {
        int colon = line.indexOf(':');
        headers
            .computeIfAbsent(
                line.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                key -> new java.util.ArrayList<>())
            .add(line.substring(colon + 1).strip());
      }
      byte[] body;
      if (headers.getOrDefault("transfer-encoding", List.of()).stream()
          .anyMatch(value -> value.equalsIgnoreCase("chunked"))) {
        var bytes = new java.io.ByteArrayOutputStream();
        while (true) {
          int size = Integer.parseInt(wireLine(input).split(";", 2)[0], 16);
          if (size == 0) break;
          bytes.write(input.readNBytes(size));
          assertThat(wireLine(input)).isEmpty();
        }
        body = bytes.toByteArray();
      } else body = input.readAllBytes();
      return new RawResponse(
          code,
          java.net.http.HttpHeaders.of(headers, (key, value) -> true),
          new String(body, StandardCharsets.UTF_8),
          uri);
    }
  }

  private static String wireLine(java.io.InputStream input) throws java.io.IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).replace("\r", "");
      bytes.write(value);
    }
    throw new java.io.EOFException("Incomplete test HTTP response");
  }

  private record RawResponse(
      int statusCode, java.net.http.HttpHeaders headers, String body, URI uri)
      implements HttpResponse<String> {
    @Override
    public HttpRequest request() {
      return HttpRequest.newBuilder(uri).GET().build();
    }

    @Override
    public java.util.Optional<HttpResponse<String>> previousResponse() {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
      return java.util.Optional.empty();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  @Test
  void securityAndPolicyStorageOutagesAreSafeAndHeadSuppressesErrors() throws Exception {
    login();
    policies.provision(Set.of(Provider.OPENAI));
    var policy = jdbc.queryForList("select * from provider_deployment_policy");
    jdbc.execute(
        "alter table provider_deployment_policy rename to provider_policy_temporarily_unavailable");
    try {
      error(request(client, "GET", path(), null, null), 503, "PROVIDER_CONNECTION_UNAVAILABLE");
    } finally {
      jdbc.execute(
          "alter table provider_policy_temporarily_unavailable rename to"
              + " provider_deployment_policy");
    }
    assertThat(jdbc.queryForList("select * from provider_deployment_policy")).isEqualTo(policy);
    assertThat(slots()).isEqualTo(1);
    var quota = jdbc.queryForList("select * from provider_read_admission");
    jdbc.execute(
        "alter table provider_read_admission rename to provider_admission_temporarily_unavailable");
    try {
      error(request(client, "GET", path(), null, null), 503, "PROVIDER_CONNECTION_UNAVAILABLE");
    } finally {
      jdbc.execute(
          "alter table provider_admission_temporarily_unavailable rename to"
              + " provider_read_admission");
    }
    assertThat(jdbc.queryForList("select * from provider_read_admission")).isEqualTo(quota);
    jdbc.execute("alter table human_accounts rename to provider_accounts_temporarily_unavailable");
    try {
      var head = request(client, "HEAD", path(), null, null);
      assertThat(head.statusCode()).isEqualTo(503);
      assertThat(head.body()).isEmpty();
      assertThat(head.headers().firstValue("Cache-Control")).contains("no-store");
    } finally {
      jdbc.execute(
          "alter table provider_accounts_temporarily_unavailable rename to human_accounts");
    }
  }

  @Test
  void nonhumanSessionIsDeniedAndNeighborRoutesKeepExistingBehavior() throws Exception {
    login();
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    assertThat(request(client, "GET", "/api/v1/auth/session", null, null).statusCode())
        .isEqualTo(200);
    var head = request(client, "HEAD", path(), null, null);
    assertThat(head.statusCode()).isEqualTo(403);
    assertThat(head.body()).isEmpty();
    error(request(client, "GET", path(), null, null), 403, "FORBIDDEN");
    assertThat(request(client, "GET", path() + "/", null, null).statusCode()).isNotEqualTo(200);
    installNonhumanSession();
    error(request(client, "GET", path(), null, null), 401, "UNAUTHORIZED");
    assertThat(slots()).isZero();
  }

  @Autowired
  org.springframework.session.FindByIndexNameSessionRepository<
          ? extends org.springframework.session.Session>
      sessions;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void installNonhumanSession() {
    var repository = (org.springframework.session.FindByIndexNameSessionRepository) sessions;
    var session = (org.springframework.session.Session) repository.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "service-principal", "unused", List.of()));
    session.setAttribute("SPRING_SECURITY_CONTEXT", context);
    repository.save(session);
    var cookie =
        new java.net.HttpCookie(
            "SESSION",
            java.util.Base64.getEncoder()
                .encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    cookie.setPath("/");
    cookie.setVersion(0);
    client = newClient();
    ((CookieManager) client.cookieHandler().orElseThrow())
        .getCookieStore()
        .add(URI.create("http://localhost:" + port), cookie);
  }
}

package com.agentos.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** Uses a real servlet container: MockMvc does not redispatch sendError to /error. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        "spring.datasource.url=jdbc:h2:mem:mission_http_errors;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@ActiveProfiles("test")
class MissionHttpErrorIntegrationTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired ObjectMapper json;

  URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  @Test
  void malformedBodiesAndHeadersReturnSanitized400ThroughRealServletContainer() throws Exception {
    UUID org = UUID.randomUUID(), account = UUID.randomUUID(), team = UUID.randomUUID();
    String email = account + "@example.test";
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Tester',?)",
        account,
        email,
        passwords.encode("Test-password-123!"));
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        team,
        org);
    try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
      var csrf =
          json.readTree(
                  client
                      .send(
                          HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                          HttpResponse.BodyHandlers.ofString())
                      .body())
              .get("data");
      var login =
          client.send(
              HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                  .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "username=" + email + "&password=Test-password-123!"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(login.statusCode()).isEqualTo(200);
      csrf =
          json.readTree(
                  client
                      .send(
                          HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                          HttpResponse.BodyHandlers.ofString())
                      .body())
              .get("data");
      String root = "/api/v1/organizations/" + org + "/teams/" + team + "/missions";
      String body =
          "{\"title\":\"private title\",\"goal\":\"private goal\",\"requiredOutput\":\"output\",\"priority\":\"NORMAL\",\"approvalMode\":\"HUMAN_REQUIRED\",\"budgetLimit\":\"1e2\"}";
      for (String invalidBody : new String[] {body, "{\"goal\":\"private goal\","}) {
        var response =
            client.send(
                HttpRequest.newBuilder(uri(root))
                    .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(invalidBody))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertBadRequest(response);
      }
      for (String key : new String[] {"", "invalid-private-key"}) {
        var request =
            HttpRequest.newBuilder(uri(root))
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .header("Content-Type", "application/json");
        if (!key.isEmpty()) request.header("Idempotency-Key", key);
        assertBadRequest(
            client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()));
      }
      for (String suffix : new String[] {"", "/" + UUID.randomUUID() + "/plan"}) {
        var response =
            client.send(
                HttpRequest.newBuilder(uri("/api/v1/organizations/" + org + "/missions" + suffix))
                    .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(suffix.isEmpty() ? 410 : 501);
        assertThat(json.readTree(response.body()).at("/error/code").asText())
            .isEqualTo(suffix.isEmpty() ? "GONE" : "NOT_IMPLEMENTED");
      }
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from missions where organization_id=?", Long.class, org))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from mission_create_receipts where organization_id=?",
                  Long.class,
                  org))
          .isZero();
    }
  }

  private void assertBadRequest(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("BAD_REQUEST");
    assertThat(json.readTree(response.body()).at("/error/message").asText())
        .isEqualTo("Invalid request");
    assertThat(response.body()).doesNotContain("private", "1e2");
  }
}

package com.agentos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder encoder;
  @Autowired FindByIndexNameSessionRepository<?> sessions;
  private UUID account;
  private UUID organization;
  private static final String PASSWORD = "test-password-strong-17";

  @BeforeEach
  void setup() {
    jdbc.update("delete from authentication_audit");
    jdbc.update("delete from authentication_attempts");
    jdbc.update("delete from organization_memberships");
    jdbc.update("delete from human_accounts");
    account = UUID.randomUUID();
    organization = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,?,?)",
        account,
        "human@example.test",
        "Human",
        encoder.encode(PASSWORD));
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,?"
            + " ,?,'ACTIVE',current_timestamp,current_timestamp)",
        organization,
        "Workspace",
        organization.toString());
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        account,
        organization);
  }

  private Bootstrap csrf(Cookie cookie) throws Exception {
    var request = get("/api/v1/auth/csrf");
    if (cookie != null) request.cookie(cookie);
    var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    var data = json.readTree(response.getContentAsString()).get("data");
    Cookie next = response.getCookie("SESSION");
    return new Bootstrap(
        next == null ? cookie : next, data.get("token").asText(), data.get("headerName").asText());
  }

  private Cookie login() throws Exception {
    var initial = csrf(null);
    var response =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(initial.cookie())
                    .header(initial.header(), initial.token())
                    .param("username", " HUMAN@example.test ")
                    .param("password", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(
                content()
                    .string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))))
            .andExpect(
                content()
                    .string(
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("passwordHash"))))
            .andExpect(jsonPath("data.id").value(account.toString()))
            .andExpect(jsonPath("data.password_hash").doesNotExist())
            .andReturn()
            .getResponse();
    Cookie authenticated = response.getCookie("SESSION");
    assertThat(authenticated).isNotNull();
    assertThat(authenticated.getValue()).isNotEqualTo(initial.cookie().getValue());
    assertThat(authenticated.isHttpOnly()).isTrue();
    assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
    mvc.perform(get("/api/v1/auth/session").cookie(initial.cookie()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/logout")
                .cookie(authenticated)
                .header(initial.header(), initial.token()))
        .andExpect(status().isForbidden());
    assertThat(csrf(authenticated).token()).isNotEqualTo(initial.token());
    assertThat(json.readTree(response.getContentAsString()).get("data").size()).isEqualTo(3);
    return authenticated;
  }

  @Test
  void loginRotatesPersistsAndLogoutInvalidates() throws Exception {
    var cookie = login();
    mvc.perform(get("/api/v1/auth/session").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.email").value("human@example.test"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from spring_session_attributes where attribute_name ="
                    + " 'SPRING_SECURITY_CONTEXT'",
                Long.class))
        .isPositive();
    String id =
        new String(
            java.util.Base64.getDecoder().decode(cookie.getValue()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(sessions.findById(id)).isNotNull();
    mvc.perform(get("/api/v1/auth/workspaces").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data[0].id").value(organization.toString()))
        .andExpect(jsonPath("data[0].role").value("ADMIN"));
    mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
    var token = csrf(cookie);
    mvc.perform(post("/api/v1/auth/logout").cookie(cookie).header(token.header(), token.token()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/auth/session").cookie(cookie)).andExpect(status().isUnauthorized());
    assertThat(sessions.findById(id)).isNull();
  }

  @Test
  void workspaceOpenUsesUrlAndCurrentDatabaseMembership() throws Exception {
    var cookie = login();
    mvc.perform(get("/api/v1/auth/workspaces/" + organization).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.id").value(organization.toString()))
        .andExpect(jsonPath("data.name").value("Workspace"))
        .andExpect(jsonPath("data.role").value("ADMIN"))
        .andExpect(jsonPath("data", org.hamcrest.Matchers.aMapWithSize(3)));
    UUID second = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?, 'Second', ?, 'ACTIVE',current_timestamp,current_timestamp)",
        second,
        second.toString());
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'MEMBER')",
        account,
        second);
    mvc.perform(get("/api/v1/auth/workspaces/" + second).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.id").value(second.toString()))
        .andExpect(jsonPath("data.role").value("MEMBER"));
    jdbc.update(
        "delete from organization_memberships where account_id = ? and organization_id = ?",
        account,
        second);
    var revoked =
        mvc.perform(get("/api/v1/auth/workspaces/" + second).cookie(cookie))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("error.message").value("Workspace not found"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var missing =
        mvc.perform(get("/api/v1/auth/workspaces/" + UUID.randomUUID()).cookie(cookie))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(revoked).get("error")).isEqualTo(json.readTree(missing).get("error"));
    mvc.perform(get("/api/v1/auth/workspaces/" + organization).cookie(cookie))
        .andExpect(status().isOk());
  }

  @Test
  void workspaceOpenRejectsAnotherAccountsWorkspace() throws Exception {
    UUID otherAccount = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,?,?)",
        otherAccount,
        "other@example.test",
        "Other",
        encoder.encode(PASSWORD));
    jdbc.update("delete from organization_memberships where account_id = ?", account);
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        otherAccount,
        organization);
    mvc.perform(get("/api/v1/auth/workspaces/" + organization).cookie(login()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("error.message").value("Workspace not found"));
  }

  @Test
  void workspaceOpenRequiresAuthenticationAndValidUuid() throws Exception {
    mvc.perform(get("/api/v1/auth/workspaces/" + organization))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("error.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/v1/auth/workspaces/not-a-uuid").cookie(login()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void workspaceOpenRejectsDisabledAccountAndStaleCredentials() throws Exception {
    var cookie = login();
    jdbc.update(
        "update human_accounts set credential_version = credential_version + 1 where id = ?",
        account);
    mvc.perform(get("/api/v1/auth/workspaces/" + organization).cookie(cookie))
        .andExpect(status().isUnauthorized());
    var freshCookie = login();
    jdbc.update("update human_accounts set enabled = false where id = ?", account);
    mvc.perform(get("/api/v1/auth/workspaces/" + organization).cookie(freshCookie))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void credentialsAreGenericDisabledRejectedAndBcryptStored() throws Exception {
    String hash =
        jdbc.queryForObject(
            "select password_hash from human_accounts where id = ?", String.class, account);
    assertThat(hash).startsWith("$2a$12$");
    assertThat(encoder.matches(PASSWORD, hash)).isTrue();
    var token = csrf(null);
    String known =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(token.cookie())
                    .header(token.header(), token.token())
                    .param("username", "human@example.test")
                    .param("password", "wrong"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(token.cookie())
                    .header(token.header(), token.token())
                    .param("username", "missing@example.test")
                    .param("password", "wrong"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(known).get("error")).isEqualTo(json.readTree(unknown).get("error"));
    jdbc.update("update human_accounts set enabled = false where id = ?", account);
    mvc.perform(
            post("/api/v1/auth/login")
                .cookie(token.cookie())
                .header(token.header(), token.token())
                .param("username", "human@example.test")
                .param("password", PASSWORD))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .cookie(token.cookie())
                .header(token.header(), token.token())
                .param("username", "human@example.test")
                .param("password", "é".repeat(37)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void csrfRequiredAndUnauthenticatedDomainIsJson401() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .param("username", "human@example.test")
                .param("password", PASSWORD))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/organizations/" + organization + "/agents"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("error.code").value("UNAUTHORIZED"));
    var token = csrf(null);
    mvc.perform(
            post("/api/v1/auth/login")
                .cookie(token.cookie())
                .header(token.header(), "invalid")
                .param("username", "human@example.test")
                .param("password", PASSWORD))
        .andExpect(status().isForbidden());
  }

  @Test
  void membershipsScopeReadsWritesTraceAndPlanning() throws Exception {
    var cookie = login();
    mvc.perform(get("/api/v1/organizations").cookie(cookie)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/organizations/" + UUID.randomUUID() + "/agents").cookie(cookie))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/organizations/" + organization + "/agents").cookie(cookie))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/organizations/"
                    + organization
                    + "/missions/"
                    + UUID.randomUUID()
                    + "/trace")
                .cookie(cookie))
        .andExpect(status().isNotFound());
    var token = csrf(cookie);
    mvc.perform(
            post("/api/v1/organizations/"
                    + organization
                    + "/missions/"
                    + UUID.randomUUID()
                    + "/plan")
                .cookie(cookie)
                .header(token.header(), token.token()))
        .andExpect(status().isNotImplemented());
    jdbc.update(
        "update organization_memberships set role = 'MEMBER' where account_id = ?", account);
    mvc.perform(
            post("/api/v1/organizations/" + organization + "/teams")
                .cookie(cookie)
                .header(token.header(), token.token())
                .contentType("application/json")
                .content("{\"name\":\"Denied\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void foreignMissionTeamAndManagerAreRejected() throws Exception {
    UUID other = UUID.randomUUID();
    UUID team = UUID.randomUUID();
    UUID manager = UUID.randomUUID();
    UUID mission = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?, 'Foreign',"
            + " ?, 'ACTIVE',current_timestamp,current_timestamp)",
        other,
        other.toString());
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Foreign','ACTIVE',current_timestamp,current_timestamp)",
        team,
        other);
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values"
            + " (?,?,'Foreign','Manager','MID','ACTIVE','SUGGEST',current_timestamp,current_timestamp)",
        manager,
        other);
    jdbc.update(
        "insert into missions(id,organization_id,title,goal,status,priority,created_at,updated_at)"
            + " values"
            + " (?,?,'Foreign','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        mission,
        other);
    var cookie = login();
    var token = csrf(cookie);
    mvc.perform(
            get("/api/v1/organizations/" + organization + "/missions/" + mission + "/trace")
                .cookie(cookie))
        .andExpect(status().isNotFound());
    for (String reference :
        new String[] {"\"teamId\":\"" + team + "\"", "\"managerAgentId\":\"" + manager + "\""}) {
      mvc.perform(
              post("/api/v1/organizations/" + organization + "/missions")
                  .cookie(cookie)
                  .header(token.header(), token.token())
                  .contentType("application/json")
                  .content("{\"title\":\"Mission\",\"goal\":\"Goal\"," + reference + "}"))
          .andExpect(status().isGone());
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from missions where organization_id = ?",
                Long.class,
                organization))
        .isZero();
  }

  @Test
  void disablingAccountRevokesExistingSession() throws Exception {
    var cookie = login();
    jdbc.update("update human_accounts set enabled = false where id = ?", account);
    mvc.perform(get("/api/v1/auth/session").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/auth/workspaces").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/organizations/" + organization + "/agents").cookie(cookie))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void durableRateLimitRejectsCorrectPasswordAfterFiveFailures() throws Exception {
    var token = csrf(null);
    for (int i = 0; i < 5; i++)
      mvc.perform(
              post("/api/v1/auth/login")
                  .cookie(token.cookie())
                  .header(token.header(), token.token())
                  .param("username", "human@example.test")
                  .param("password", "wrong"))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .cookie(token.cookie())
                .header(token.header(), token.token())
                .param("username", "human@example.test")
                .param("password", PASSWORD))
        .andExpect(status().isUnauthorized());
    jdbc.update(
        "update authentication_attempts set window_start = ?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(901)));
    mvc.perform(
            post("/api/v1/auth/login")
                .cookie(token.cookie())
                .header(token.header(), token.token())
                .param("username", "human@example.test")
                .param("password", PASSWORD))
        .andExpect(status().isOk());
  }

  @Test
  void recoveryIsUnavailableByDefault() throws Exception {
    var token = csrf(null);
    for (String endpoint : new String[] {"request", "confirm"}) {
      mvc.perform(
              post("/api/v1/auth/recovery/" + endpoint)
                  .cookie(token.cookie())
                  .header(token.header(), token.token())
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("error.code").value("RECOVERY_UNAVAILABLE"));
    }
  }

  private record Bootstrap(Cookie cookie, String token, String header) {}
}

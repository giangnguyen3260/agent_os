package com.agentos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "agentos.recovery.enabled=true",
      "agentos.recovery.frontend-base-url=http://localhost:3000",
      "agentos.recovery.from=no-reply@agentos.local",
      "agentos.recovery.dispatch-delay-ms=3600000"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecoveryIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired RecoveryService service;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder encoder;
  @MockBean JavaMailSender mail;
  UUID account;
  AtomicReference<String> delivered = new AtomicReference<>();

  @BeforeEach
  void setup() {
    jdbc.update("delete from recovery_mail_jobs");
    jdbc.update("delete from password_reset_tokens");
    jdbc.update("delete from recovery_rate_buckets");
    account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,?,?)",
        account,
        account + "@example.test",
        "Recovery",
        encoder.encode("original-password"));
    doAnswer(
            call -> {
              SimpleMailMessage message = call.getArgument(0);
              if ("Your password was changed".equals(message.getSubject())) {
                assertThat(message.getText()).doesNotContain("#token=", "replacement-password");
                return null;
              }
              delivered.set(message.getText().split("#token=")[1]);
              assertThat(message.getText()).contains("http://localhost:3000/reset#token=");
              return null;
            })
        .when(mail)
        .send(any(SimpleMailMessage.class));
  }

  String token() throws Exception {
    request(account + "@example.test");
    service.dispatch();
    assertThat(delivered.get()).matches("[A-Za-z0-9_-]{43}");
    return delivered.get();
  }

  void request(String email) throws Exception {
    mvc.perform(
            post("/api/v1/auth/recovery/request")
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("data.accepted").value(true));
  }

  int confirm(String token, String password) throws Exception {
    return mvc.perform(
            post("/api/v1/auth/recovery/confirm")
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "password", password))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void uniformCooldownAndCsrf() throws Exception {
    request(account + "@example.test");
    request(account + "@example.test");
    request("unknown@example.test");
    request("unknown@example.test");
    assertThat(jdbc.queryForObject("select count(*) from recovery_mail_jobs", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from recovery_rate_buckets", Integer.class))
        .isEqualTo(2);
    jdbc.update("update human_accounts set enabled=false where id=?", account);
    request(account + "@example.test");
    for (String path : new String[] {"request", "confirm"})
      mvc.perform(
              post("/api/v1/auth/recovery/" + path).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
    org.mockito.Mockito.verifyNoInteractions(mail);
  }

  @Test
  void resetRevokesEverySessionClearsLockoutAndRejectsReplay() throws Exception {
    String token = token();
    for (int i = 0; i < 2; i++) {
      String id = UUID.randomUUID().toString();
      jdbc.update(
          "insert into"
              + " spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)"
              + " values (?,?,0,0,1800,9999999999999,?)",
          id,
          id,
          account.toString());
    }
    jdbc.update(
        "insert into authentication_attempts(account_key,failures) values (?,5)",
        RecoveryService.digest(account + "@example.test"));
    jdbc.update(
        "insert into recovery_mail_jobs(id,account_id) values (?,?)", UUID.randomUUID(), account);
    assertThat(confirm(token, "replacement-password")).isEqualTo(204);
    assertThat(confirm(token, "replacement-password")).isEqualTo(400);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from spring_session where principal_name=?",
                Integer.class,
                account.toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from authentication_attempts where account_key=?",
                Integer.class,
                RecoveryService.digest(account + "@example.test")))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from recovery_mail_jobs where state='PENDING' and kind='RESET'",
                Integer.class))
        .isZero();
    assertThat(
            encoder.matches(
                "replacement-password",
                jdbc.queryForObject(
                    "select password_hash from human_accounts where id=?", String.class, account)))
        .isTrue();
  }

  @Test
  void lateSavedOldCredentialSessionIsRejectedAndNewLoginWorks() throws Exception {
    String token = token();
    HumanIdentity old = new HumanIdentity(account, account + "@example.test", "Recovery", 0L);
    assertThat(confirm(token, "replacement-password")).isEqualTo(204);
    var authentication =
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated(old, null, List.of());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/auth/session")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(authentication)))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("username", account + "@example.test")
                .param("password", "original-password"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("username", account + "@example.test")
                .param("password", "replacement-password"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.credentialVersion").doesNotExist());
    service.dispatch();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from recovery_mail_jobs where kind='PASSWORD_CHANGED' and"
                    + " state='SENT'",
                Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Integer.class))
        .isZero();
  }

  @Test
  void expiryDisabledAndPasswordBoundaries() throws Exception {
    String token = token();
    assertThat(confirm(token, "a".repeat(13))).isEqualTo(400);
    assertThat(confirm(token, "é".repeat(37))).isEqualTo(400);
    jdbc.update("update human_accounts set enabled=false where id=?", account);
    assertThat(confirm(token, "a".repeat(14))).isEqualTo(400);
    jdbc.update("update human_accounts set enabled=true where id=?", account);
    jdbc.update(
        "update password_reset_tokens set expires_at=?",
        Timestamp.from(Instant.now().minusSeconds(1)));
    assertThat(confirm(token, "a".repeat(14))).isEqualTo(400);
    jdbc.update(
        "update password_reset_tokens set expires_at=?",
        Timestamp.from(Instant.now().plusSeconds(60)));
    assertThat(confirm(token, "é".repeat(36))).isEqualTo(204);
  }

  @Test
  void concurrentConsumeHasOneWinner() throws Exception {
    String token = token();
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> consume = () -> service.confirm(token, "replacement-password");
      var results = pool.invokeAll(List.of(consume, consume));
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void onlyLatestIssuedTokenWorks() throws Exception {
    String first = token();
    jdbc.update("update recovery_rate_buckets set requested_at=?", Timestamp.from(Instant.EPOCH));
    String second = token();
    assertThat(confirm(first, "replacement-password")).isEqualTo(400);
    assertThat(confirm(second, "a".repeat(14))).isEqualTo(204);
  }

  @Test
  void smtpFailureRetriesWithoutClaimingSent() throws Exception {
    doThrow(new MailSendException("test failure")).when(mail).send(any(SimpleMailMessage.class));
    request(account + "@example.test");
    for (int i = 0; i < 5; i++) {
      jdbc.update("update recovery_mail_jobs set available_at=?", Timestamp.from(Instant.EPOCH));
      service.dispatch();
    }
    assertThat(jdbc.queryForObject("select state from recovery_mail_jobs", String.class))
        .isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select attempts from recovery_mail_jobs", Integer.class))
        .isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Integer.class))
        .isZero();
  }
}

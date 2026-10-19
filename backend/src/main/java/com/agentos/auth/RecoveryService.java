package com.agentos.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableScheduling
public class RecoveryService {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final JavaMailSender mail;
  private final Environment env;
  private final TransactionTemplate transactions;
  private final SecureRandom random = new SecureRandom();
  private final String frontend;

  public RecoveryService(
      JdbcTemplate jdbc,
      PasswordEncoder encoder,
      JavaMailSender mail,
      Environment env,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.mail = mail;
    this.env = env;
    transactions = new TransactionTemplate(manager);
    frontend = env.getProperty("agentos.recovery.frontend-base-url", "");
    if (enabled()) {
      URI uri = URI.create(frontend);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !("https".equals(uri.getScheme())
              || ("http".equals(uri.getScheme())
                  && ("localhost".equals(uri.getHost())
                      || "127.0.0.1".equals(uri.getHost())
                      || "[::1]".equals(uri.getHost()))))) {
        throw new IllegalArgumentException("Recovery requires a trusted HTTPS frontend base URL");
      }
      if (env.getProperty("agentos.recovery.from", "").isBlank())
        throw new IllegalArgumentException("Recovery requires a sender address");
    }
  }

  public boolean enabled() {
    return env.getProperty("agentos.recovery.enabled", Boolean.class, false);
  }

  static String digest(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public void request(String input) {
    String email = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    String key = digest(email);
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "insert into recovery_rate_buckets(email_digest, requested_at) values (?, ?) on"
                  + " conflict do nothing",
              key,
              Timestamp.from(Instant.EPOCH));
          Instant previous =
              jdbc.queryForObject(
                  "select requested_at from recovery_rate_buckets where email_digest = ? for"
                      + " update",
                  (rs, row) -> rs.getTimestamp(1).toInstant(),
                  key);
          if (previous.isAfter(Instant.now().minusSeconds(60))) return;
          jdbc.update(
              "update recovery_rate_buckets set requested_at = ? where email_digest = ?",
              Timestamp.from(Instant.now()),
              key);
          jdbc.query(
                  "select id from human_accounts where email = ? and enabled = true for update",
                  (rs, row) -> rs.getObject(1, UUID.class),
                  email)
              .forEach(
                  id ->
                      jdbc.update(
                          "insert into recovery_mail_jobs(id, account_id) values (?, ?)",
                          UUID.randomUUID(),
                          id));
        });
  }

  public boolean confirm(String token, String password) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              var ids =
                  jdbc.query(
                      "select account_id from password_reset_tokens where token_digest = ?",
                      (rs, row) -> rs.getObject(1, UUID.class),
                      digest(token));
              if (ids.isEmpty()) return false;
              UUID id = ids.getFirst();
              var accounts =
                  jdbc.query(
                      "select email, enabled from human_accounts where id = ? for update",
                      (rs, row) -> new Account(rs.getString(1), rs.getBoolean(2)),
                      id);
              if (accounts.isEmpty() || !accounts.getFirst().enabled()) return false;
              var valid =
                  jdbc.query(
                      "select token_digest from password_reset_tokens where token_digest = ? and"
                          + " expires_at > ? for update",
                      (rs, row) -> rs.getString(1),
                      digest(token),
                      Timestamp.from(Instant.now()));
              if (valid.isEmpty()) return false;
              if (password == null
                  || password.codePointCount(0, password.length()) < 14
                  || password.getBytes(StandardCharsets.UTF_8).length > 72)
                throw new IllegalArgumentException(
                    "Password must have at least 14 characters and at most 72 UTF-8 bytes");
              jdbc.update(
                  "update human_accounts set password_hash = ?, credential_version ="
                      + " credential_version + 1 where id = ?",
                  encoder.encode(password),
                  id);
              jdbc.update(
                  "insert into authentication_audit(id, account_id, event_type) values (?, ?,"
                      + " 'PASSWORD_RESET')",
                  UUID.randomUUID(),
                  id);
              jdbc.update("delete from password_reset_tokens where account_id = ?", id);
              jdbc.update(
                  "delete from recovery_mail_jobs where account_id = ? and state = 'PENDING'", id);
              jdbc.update(
                  "insert into recovery_mail_jobs(id, account_id, kind) values (?, ?,"
                      + " 'PASSWORD_CHANGED')",
                  UUID.randomUUID(),
                  id);
              jdbc.update("delete from spring_session where principal_name = ?", id.toString());
              jdbc.update(
                  "delete from authentication_attempts where account_key = ?",
                  digest(accounts.getFirst().email()));
              return true;
            }));
  }

  @Scheduled(fixedDelayString = "${agentos.recovery.dispatch-delay-ms:1000}")
  public void dispatch() {
    if (!enabled()) return;
    transactions.executeWithoutResult(
        status -> {
          // Lock accounts first, consistently with confirmation. A job transaction owns one
          // account.
          var accounts =
              jdbc.query(
                  "select a.id from human_accounts a where exists (select 1 from recovery_mail_jobs"
                      + " j where j.account_id = a.id and j.state = 'PENDING' and j.available_at <="
                      + " current_timestamp) order by a.id limit 1 for update skip locked",
                  (rs, row) -> rs.getObject(1, UUID.class));
          if (accounts.isEmpty()) return;
          UUID id = accounts.getFirst();
          var jobs =
              jdbc.query(
                  "select id, attempts, kind from recovery_mail_jobs where account_id = ? and state"
                      + " = 'PENDING' and available_at <= current_timestamp order by available_at"
                      + " limit 1 for update skip locked",
                  (rs, row) -> new Job(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)),
                  id);
          if (jobs.isEmpty()) return;
          Job job = jobs.getFirst();
          var account =
              jdbc.queryForMap("select email, enabled from human_accounts where id = ?", id);
          if (!Boolean.TRUE.equals(account.get("enabled"))) {
            jdbc.update("update recovery_mail_jobs set state = 'FAILED' where id = ?", job.id());
            return;
          }
          boolean reset = "RESET".equals(job.kind());
          byte[] bytes = new byte[32];
          random.nextBytes(bytes);
          String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
          String hash = digest(token);
          if (reset)
            jdbc.update(
                "insert into password_reset_tokens(token_digest, account_id, expires_at) values (?,"
                    + " ?, ?)",
                hash,
                id,
                Timestamp.from(Instant.now().plusSeconds(900)));
          try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(env.getRequiredProperty("agentos.recovery.from"));
            message.setTo((String) account.get("email"));
            message.setSubject(reset ? "Reset your password" : "Your password was changed");
            message.setText(
                reset
                    ? "Reset your password within 15 minutes: "
                        + frontend.replaceAll("/+$", "")
                        + "/reset#token="
                        + token
                    : "Your account password was changed. If you did not make this change, contact"
                        + " your administrator.");
            mail.send(message);
          } catch (org.springframework.mail.MailException ex) {
            jdbc.update("delete from password_reset_tokens where token_digest = ?", hash);
            jdbc.update(
                "update recovery_mail_jobs set attempts = ?, state = ?, available_at = ? where id ="
                    + " ?",
                job.attempts() + 1,
                job.attempts() + 1 >= 5 ? "FAILED" : "PENDING",
                Timestamp.from(
                    Instant.now()
                        .plusSeconds(
                            env.getProperty(
                                "agentos.recovery.retry-delay-seconds", Long.class, 60L))),
                job.id());
            return;
          }
          // Preserve previously delivered links unless SMTP accepts the replacement.
          if (reset)
            jdbc.update(
                "delete from password_reset_tokens where account_id = ? and token_digest <> ?",
                id,
                hash);
          jdbc.update(
              "update recovery_mail_jobs set state = 'SENT', attempts = attempts + 1 where id = ?",
              job.id());
        });
  }

  private record Account(String email, boolean enabled) {}

  private record Job(UUID id, int attempts, String kind) {}
}

package com.agentos.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class HumanAuthenticationProvider implements AuthenticationProvider {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final TransactionTemplate transactions;
  private final String dummyHash;

  public HumanAuthenticationProvider(
      JdbcTemplate jdbc,
      PasswordEncoder encoder,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.transactions = new TransactionTemplate(manager);
    this.dummyHash = encoder.encode(UUID.randomUUID().toString());
  }

  @Override
  public Authentication authenticate(Authentication input) {
    String email = input.getName().trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254) throw new BadCredentialsException("Invalid credentials");
    HumanIdentity identity =
        transactions.execute(status -> verify(email, String.valueOf(input.getCredentials())));
    if (identity == null) throw new BadCredentialsException("Invalid credentials");
    return UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of());
  }

  private HumanIdentity verify(String email, String password) {
    String key;
    try {
      key =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(email.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
    // Match recovery lock order, including the account lock acquired by audit foreign keys.
    var accounts =
        jdbc.query(
            "select id, email, display_name, password_hash, enabled, credential_version from"
                + " human_accounts where email = ? for update",
            (rs, row) ->
                new Account(
                    new HumanIdentity(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getLong("credential_version")),
                    rs.getString("password_hash"),
                    rs.getBoolean("enabled")),
            email);
    Account account = accounts.isEmpty() ? null : accounts.get(0);
    jdbc.update(
        "insert into authentication_attempts(account_key) values (?) on conflict do nothing", key);
    var attempt =
        jdbc.queryForMap(
            "select failures, window_start from authentication_attempts where account_key = ? for"
                + " update",
            key);
    Instant start =
        jdbc.queryForObject(
            "select window_start from authentication_attempts where account_key = ?",
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            key);
    int failures = ((Number) attempt.get("failures")).intValue();
    if (start.isBefore(Instant.now().minus(15, ChronoUnit.MINUTES))) failures = 0;
    if (failures >= 5) {
      audit(null, "LOGIN_THROTTLED");
      return null;
    }
    boolean matches =
        password.getBytes(StandardCharsets.UTF_8).length <= 72
            && encoder.matches(password, account == null ? dummyHash : account.hash());
    boolean valid = account != null && account.enabled() && matches;
    jdbc.update(
        "update authentication_attempts set failures = ?, window_start = ? where account_key = ?",
        valid ? 0 : failures + 1,
        java.sql.Timestamp.from(failures == 0 ? Instant.now() : start),
        key);
    audit(
        account == null ? null : account.identity().id(),
        valid ? "LOGIN_SUCCESS" : "LOGIN_FAILURE");
    return valid ? account.identity() : null;
  }

  private void audit(UUID id, String event) {
    jdbc.update(
        "insert into authentication_audit(id, account_id, event_type) values (?, ?, ?)",
        UUID.randomUUID(),
        id,
        event);
  }

  @Override
  public boolean supports(Class<?> type) {
    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
  }

  private record Account(HumanIdentity identity, String hash, boolean enabled) {}
}

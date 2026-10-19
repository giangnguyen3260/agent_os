package com.agentos.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements ApplicationRunner {
  private final Environment env;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;

  public BootstrapAdmin(Environment env, JdbcTemplate jdbc, PasswordEncoder encoder) {
    this.env = env;
    this.jdbc = jdbc;
    this.encoder = encoder;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    if (!env.getProperty("AGENTOS_BOOTSTRAP_ENABLED", Boolean.class, false)) return;
    jdbc.execute("select pg_advisory_xact_lock(8642061801)");
    if (jdbc.queryForObject("select count(*) from human_accounts", Long.class) != 0) return;
    String email =
        env.getRequiredProperty("AGENTOS_BOOTSTRAP_EMAIL").trim().toLowerCase(Locale.ROOT);
    String name = env.getRequiredProperty("AGENTOS_BOOTSTRAP_DISPLAY_NAME").trim();
    String password = env.getRequiredProperty("AGENTOS_BOOTSTRAP_PASSWORD");
    if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || email.length() > 254
        || name.isBlank()
        || name.length() > 255
        || password.codePointCount(0, password.length()) < 14
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Invalid bootstrap configuration: require email, display name and password of at least 14"
              + " characters (maximum 72 UTF-8 bytes)");
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id, email, display_name, password_hash) values (?, ?, ?, ?)",
        id,
        email,
        name,
        encoder.encode(password));
    jdbc.update(
        "insert into authentication_audit(id, account_id, event_type) values (?, ?,"
            + " 'BOOTSTRAP_CREATED')",
        UUID.randomUUID(),
        id);
  }
}

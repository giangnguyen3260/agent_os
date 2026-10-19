package com.agentos.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

class BootstrapAdminTest {
  @Test
  void disabledDoesNotTouchDatabase() {
    var jdbc = mock(JdbcTemplate.class);
    new BootstrapAdmin(new MockEnvironment(), jdbc, mock(PasswordEncoder.class)).run(null);
    verifyNoInteractions(jdbc);
  }

  @Test
  void existingAccountPreventsProvisioningAndSecretRead() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("select count(*) from human_accounts", Long.class)).thenReturn(1L);
    new BootstrapAdmin(
            new MockEnvironment().withProperty("AGENTOS_BOOTSTRAP_ENABLED", "true"),
            jdbc,
            mock(PasswordEncoder.class))
        .run(null);
    var ordered = inOrder(jdbc);
    ordered.verify(jdbc).execute("select pg_advisory_xact_lock(8642061801)");
    ordered.verify(jdbc).queryForObject("select count(*) from human_accounts", Long.class);
    ordered.verifyNoMoreInteractions();
  }

  @Test
  void validConfigurationNormalizesIdentityAndWritesOnlyEncodedPassword() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("select count(*) from human_accounts", Long.class)).thenReturn(0L);
    var encoder = mock(PasswordEncoder.class);
    String password = "strong-test-password";
    when(encoder.encode(password)).thenReturn("encoded-hash");
    var env =
        new MockEnvironment()
            .withProperty("AGENTOS_BOOTSTRAP_ENABLED", "true")
            .withProperty("AGENTOS_BOOTSTRAP_EMAIL", " ADMIN@example.test ")
            .withProperty("AGENTOS_BOOTSTRAP_DISPLAY_NAME", " Admin ")
            .withProperty("AGENTOS_BOOTSTRAP_PASSWORD", password);
    new BootstrapAdmin(env, jdbc, encoder).run(null);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.eq(
                "insert into human_accounts(id, email, display_name, password_hash) values (?, ?,"
                    + " ?, ?)"),
            any(java.util.UUID.class),
            org.mockito.ArgumentMatchers.eq("admin@example.test"),
            org.mockito.ArgumentMatchers.eq("Admin"),
            org.mockito.ArgumentMatchers.eq("encoded-hash"));
  }

  @Test
  void shortAndOverlongUtf8PasswordsRejectedWithoutWrite() {
    for (String password : new String[] {"short", "é".repeat(37), "\uD83D\uDD12".repeat(7)}) {
      var jdbc = mock(JdbcTemplate.class);
      when(jdbc.queryForObject("select count(*) from human_accounts", Long.class)).thenReturn(0L);
      var env =
          new MockEnvironment()
              .withProperty("AGENTOS_BOOTSTRAP_ENABLED", "true")
              .withProperty("AGENTOS_BOOTSTRAP_EMAIL", "admin@example.test")
              .withProperty("AGENTOS_BOOTSTRAP_DISPLAY_NAME", "Admin")
              .withProperty("AGENTOS_BOOTSTRAP_PASSWORD", password);
      assertThatThrownBy(() -> new BootstrapAdmin(env, jdbc, mock(PasswordEncoder.class)).run(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(password);
      verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
  }
}

package com.agentos.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class InvitationWireTest {
  final ObjectMapper json = new ObjectMapper();
  final UUID org = UUID.fromString("90909090-9090-4090-8090-909090909090");
  final UUID actor = UUID.fromString("90900000-0000-4000-8000-000000000001");
  final UUID target = UUID.fromString("90910000-0000-4000-8000-000000000101");

  @Test
  void createFingerprintVector() {
    assertThat(
            InvitationFingerprint.hash(
                json, org, actor, null, "CREATE_INVITATION", "invitee@example.test", null))
        .isEqualTo("c651c65696a23ed4e1e2c62e195d3d34a9c42bcefb9cd19a6612d4184209d6bd");
  }

  @Test
  void resendFingerprintVector() {
    assertThat(InvitationFingerprint.hash(json, org, actor, target, "RESEND_INVITATION", null, 1))
        .isEqualTo("ff65bf6d78ea6d5a116bc50ac85e767281a78b60aa53af29697f48028b3e7279");
  }

  @Test
  void revokeFingerprintVector() {
    assertThat(InvitationFingerprint.hash(json, org, actor, target, "REVOKE_INVITATION", null, 2))
        .isEqualTo("41980eca49a300e04909063913b2757609beea5e555961501c0796f7b1ef42c8");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a,b@example.test",
        "a;b@example.test",
        "a:b@example.test",
        "Name <a@example.test>",
        "a>z@example.test",
        "a\\b@example.test",
        "a[b@example.test",
        "a]b@example.test",
        "\"a\"@example.test",
        ".a@example.test",
        "a.@example.test",
        "a..b@example.test",
        "a@-example.test",
        "a@example-.test",
        "a@example..test",
        "a@example",
        "a b@example.test",
        "a@exämple.test",
        "a@@example.test",
        "a\r\n@example.test"
      })
  void rejectsUnsafeMailboxSyntax(String email) {
    assertThatThrownBy(() -> InvitationWire.email(email))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAndCanonicalizesBoundedDotAtom() {
    assertThat(InvitationWire.email("  A.B+Tag@EXAMPLE.TEST  ")).isEqualTo("a.b+tag@example.test");
  }

  @Test
  void localPartBoundaries() {
    assertThat(InvitationWire.email("a".repeat(64) + "@example.test")).hasSize(77);
    assertThatThrownBy(() -> InvitationWire.email("a".repeat(65) + "@example.test"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void domainLabelBoundaries() {
    assertThat(InvitationWire.email("a@" + "b".repeat(63) + ".test")).isNotEmpty();
    assertThatThrownBy(() -> InvitationWire.email("a@" + "b".repeat(64) + ".test"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void senderUsesCanonicalValidatedAddress() {
    var env =
        new MockEnvironment()
            .withProperty("agentos.invitations.enabled", "true")
            .withProperty("agentos.invitations.frontend-base-url", "http://localhost:3000")
            .withProperty("agentos.invitations.from", "  INVITES@EXAMPLE.TEST  ");
    assertThat(new InvitationSettings(env).from).isEqualTo("invites@example.test");
  }

  @Test
  void passwordIsNotTrimmedAndRejectsIsolatedSurrogates() {
    assertThat(InvitationWire.password("  long-password  ")).isEqualTo("  long-password  ");
    assertThatThrownBy(() -> InvitationWire.password("long-password-\uD800"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jsonCapIncludesUtf8BytesAndAllowsExactBoundary() {
    String prefix = "{\"token\":\"";
    String suffix = "\"}";
    String exact = prefix + "a".repeat(8192 - prefix.length() - suffix.length()) + suffix;
    assertThat(InvitationWire.body(json, exact, "token").get("token").isTextual()).isTrue();
    assertThatThrownBy(() -> InvitationWire.body(json, exact + " ", "token"))
        .isInstanceOf(IllegalArgumentException.class);
    String multibyte = prefix + "é".repeat(4096) + suffix;
    assertThat(multibyte.length()).isLessThan(8192);
    assertThatThrownBy(() -> InvitationWire.body(json, multibyte, "token"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateUnknownAndTrailingJson() {
    for (String raw :
        new String[] {
          "{\"token\":\"a\",\"token\":\"b\"}",
          "{\"other\":\"a\"}",
          "{\"token\":\"a\"} {}",
          "null",
          "[]"
        }) {
      assertThatThrownBy(() -> InvitationWire.body(json, raw, "token"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void unicodeNameAndPasswordBoundaries() {
    assertThat(InvitationWire.name("😀".repeat(255))).isEqualTo("😀".repeat(255));
    assertThatThrownBy(() -> InvitationWire.name("😀".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InvitationWire.name("bad\uD800"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(InvitationWire.password("é".repeat(36))).isEqualTo("é".repeat(36));
    assertThatThrownBy(() -> InvitationWire.password("é".repeat(37)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InvitationWire.password("a".repeat(13)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private MockEnvironment enabledSettingsEnvironment() {
    return new MockEnvironment()
        .withProperty("agentos.invitations.enabled", "true")
        .withProperty("agentos.invitations.frontend-base-url", "http://localhost:3000")
        .withProperty("agentos.invitations.from", "invites@example.test");
  }

  @Test
  void smtpTimeoutsDefaultToBounded5000Millis() {
    assertThatCode(() -> new InvitationSettings(enabledSettingsEnvironment()))
        .doesNotThrowAnyException();
  }

  @Test
  void smtpTimeoutsAcceptInclusiveBoundsAndNormalValues() {
    for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      for (String value : new String[] {"100", "1000", "5000"}) {
        var env =
            enabledSettingsEnvironment()
                .withProperty("spring.mail.properties.mail.smtp." + timeout, value);
        assertThatCode(() -> new InvitationSettings(env)).doesNotThrowAnyException();
      }
    }
  }

  @Test
  void smtpTimeoutsRejectUnlimitedOutOfRangeAndNonIntegerOverrides() {
    for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      for (String value :
          new String[] {
            "0", "-1", "99", "5001", "2147483647", "2147483648", "unlimited", "100.5", ""
          }) {
        var env =
            enabledSettingsEnvironment()
                .withProperty("spring.mail.properties.mail.smtp." + timeout, value);
        assertThatThrownBy(() -> new InvitationSettings(env)).isInstanceOf(RuntimeException.class);
      }
    }
  }

  @Test
  void disabledInvitationsDoNotConstrainSharedSmtpTimeouts() {
    var env =
        new MockEnvironment()
            .withProperty("agentos.invitations.enabled", "false")
            .withProperty("spring.mail.properties.mail.smtp.connectiontimeout", "0")
            .withProperty("spring.mail.properties.mail.smtp.timeout", "-1")
            .withProperty("spring.mail.properties.mail.smtp.writetimeout", "unlimited");
    assertThatCode(() -> new InvitationSettings(env)).doesNotThrowAnyException();
  }
}

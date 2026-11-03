package com.agentos.providerlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CredentialAvailabilityTest {
  private static final Instant OBSERVED = Instant.parse("2026-11-03T00:00:00Z");
  private static final Instant PUBLISHED = OBSERVED.plusSeconds(10);
  private static final Instant EXPIRES = OBSERVED.plusSeconds(60);

  @Test
  void lastMicrosecondIsAvailableButExpiryEqualityIsUnavailable() {
    assertThat(present(EXPIRES, EXPIRES.minusNanos(1000))).isTrue();
    assertThat(present(EXPIRES, EXPIRES)).isFalse();
  }

  @Test
  void cannotExtendValidityFromPublicationTimeOrUseFuturePublication() {
    assertThat(present(PUBLISHED.plusSeconds(60), PUBLISHED)).isFalse();
    assertThat(present(EXPIRES, PUBLISHED.minusNanos(1000))).isFalse();
    assertThat(present(null, PUBLISHED)).isFalse();
  }

  @Test
  void freshHealthCannotOverrideFailedIntegrityOrInvalidFence() {
    assertThat(
            CredentialAvailability.isPresent(
                true, true, true, true, true, OBSERVED, PUBLISHED, EXPIRES, PUBLISHED))
        .isFalse();
    assertThat(
            CredentialAvailability.isPresent(
                true, true, false, true, false, OBSERVED, PUBLISHED, EXPIRES, PUBLISHED))
        .isFalse();
  }

  private static boolean present(Instant expires, Instant clock) {
    return CredentialAvailability.isPresent(
        true, true, false, true, true, OBSERVED, PUBLISHED, expires, clock);
  }
}

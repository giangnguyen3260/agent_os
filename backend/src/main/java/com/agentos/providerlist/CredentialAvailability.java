package com.agentos.providerlist;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

final class CredentialAvailability {
  private static final Instant MIN = LocalDateTime.of(1, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
  private static final Instant MAX =
      LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999999000).toInstant(ZoneOffset.UTC);

  private CredentialAvailability() {}

  // The caller must establish exact binding, provenance and fence in the same read snapshot.
  static boolean isPresent(
      boolean validMetadata,
      boolean verifiedIntegrity,
      boolean integrityFailed,
      boolean usableExactHealth,
      boolean validFence,
      Instant observed,
      Instant published,
      Instant expires,
      Instant pageClock) {
    return validMetadata
        && verifiedIntegrity
        && !integrityFailed
        && usableExactHealth
        && validFence
        && validTime(observed)
        && validTime(published)
        && validTime(expires)
        && validTime(pageClock)
        && !observed.isAfter(published)
        && !published.isAfter(pageClock)
        && pageClock.isBefore(expires)
        && !expires.isAfter(observed.plusSeconds(60));
  }

  private static boolean validTime(Instant time) {
    return time != null && !time.isBefore(MIN) && !time.isAfter(MAX) && time.getNano() % 1000 == 0;
  }
}

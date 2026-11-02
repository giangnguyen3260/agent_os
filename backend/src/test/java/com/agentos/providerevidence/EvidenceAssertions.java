package com.agentos.providerevidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Storage-only fixtures. These rows are not observations by a production producer. */
final class EvidenceAssertions {
  static final String BINDING =
      "organization_id,connection_id,provider,credential_revision,external_locator,"
          + "external_key_version,hmac_key_version,hmac_domain_version";
  static final String ORG = "11111111-1111-4111-8111-111111111111";
  static final String CONNECTION = "22222222-2222-4222-8222-222222222222";
  static final String PUBLISHER = "33333333-3333-4333-8333-333333333333";
  static final String FENCE = "44444444-4444-4444-8444-444444444444";
  static final String VALUES =
      "'" + ORG + "','" + CONNECTION + "','OPENAI',1,'ref','external-v1','hmac-v1','v2'";
  static final String OBSERVED =
      "cast('2026-11-02 00:00:00.123456+00' as timestamp with time zone)";

  private EvidenceAssertions() {}

  static void seed(JdbcTemplate jdbc) {
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Evidence',?,'ACTIVE',current_timestamp,current_timestamp)",
        UUID.fromString(ORG),
        UUID.randomUUID().toString());
    jdbc.update(
        "insert into provider_credential_versions("
            + BINDING
            + ",verifier,created_at) values ("
            + VALUES
            + ",'"
            + "A".repeat(43)
            + "=',current_timestamp)");
  }

  static String integrity(String values, String state, String time, String revision) {
    return "insert into provider_credential_integrity("
        + BINDING
        + ",state,recorded_at,publication_revision) values ("
        + values
        + ","
        + state
        + ","
        + time
        + ","
        + revision
        + ")";
  }

  static String health(
      String values,
      String state,
      String observed,
      String published,
      String until,
      String revision) {
    return "insert into provider_credential_health("
        + BINDING
        + ",state,observed_at,published_at,valid_until,publication_revision,"
        + "publisher_id,publisher_epoch,publisher_fence) values ("
        + values
        + ","
        + state
        + ","
        + observed
        + ","
        + published
        + ","
        + until
        + ","
        + revision
        + ",'"
        + PUBLISHER
        + "',1,'"
        + FENCE
        + "')";
  }

  static String health() {
    return health(
        VALUES, "'USABLE'", OBSERVED, OBSERVED, OBSERVED + " + interval '60' second", "1");
  }

  static String integrity() {
    return integrity(VALUES, "'VERIFIED_AT_WRITE'", OBSERVED, "1");
  }

  static void rejected(JdbcTemplate jdbc, String sql) {
    assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class);
  }

  static void empty(JdbcTemplate jdbc) {
    for (String table :
        List.of(
            "provider_credential_integrity",
            "provider_credential_health",
            "provider_evidence_publishers")) {
      assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
    }
  }

  static void bindings(JdbcTemplate jdbc) {
    for (String values :
        List.of(
            VALUES.replace(ORG, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            VALUES.replace(CONNECTION, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            VALUES.replace("'OPENAI'", "'ANTHROPIC'"),
            VALUES.replace(",1,", ",2,"),
            VALUES.replace("'ref'", "'other-ref'"),
            VALUES.replace("'external-v1'", "'external-v2'"),
            VALUES.replace("'hmac-v1'", "'hmac-v2'"),
            VALUES.replace("'v2'", "'v1'"))) {
      rejected(jdbc, integrity(values, "'VERIFIED_AT_WRITE'", OBSERVED, "1"));
      rejected(
          jdbc,
          health(
              values, "'USABLE'", OBSERVED, OBSERVED, OBSERVED + " + interval '60' second", "1"));
      empty(jdbc);
    }
  }

  static void constraints(JdbcTemplate jdbc) {
    for (String revision : List.of("0", "-1", "9223372036854775808", "null")) {
      rejected(jdbc, integrity(VALUES, "'VERIFIED_AT_WRITE'", OBSERVED, revision));
      rejected(
          jdbc,
          health(
              VALUES,
              "'USABLE'",
              OBSERVED,
              OBSERVED,
              OBSERVED + " + interval '60' second",
              revision));
    }
    for (String state : List.of("'PRESENT'", "''", "null")) {
      rejected(jdbc, integrity(VALUES, state, OBSERVED, "1"));
      rejected(
          jdbc,
          health(VALUES, state, OBSERVED, OBSERVED, OBSERVED + " + interval '60' second", "1"));
    }
    rejected(jdbc, integrity(VALUES, "'FAILED'", "null", "1"));
    for (String[] times :
        List.of(
            new String[] {"null", OBSERVED, OBSERVED + " + interval '1' second"},
            new String[] {OBSERVED, "null", OBSERVED + " + interval '1' second"},
            new String[] {OBSERVED, OBSERVED, "null"},
            new String[] {
              OBSERVED,
              OBSERVED + " - interval '0.000001' second",
              OBSERVED + " + interval '1' second"
            },
            new String[] {OBSERVED, OBSERVED, OBSERVED},
            new String[] {OBSERVED, OBSERVED, OBSERVED + " - interval '0.000001' second"},
            new String[] {OBSERVED, OBSERVED, OBSERVED + " + interval '60.000001' second"})) {
      rejected(jdbc, health(VALUES, "'USABLE'", times[0], times[1], times[2], "1"));
    }
    rejected(jdbc, health().replace(",1,'" + FENCE, ",0,'" + FENCE));
    rejected(jdbc, health().replace("'" + PUBLISHER + "'", "null"));
    rejected(jdbc, health().replace("'" + FENCE + "'", "null"));
    empty(jdbc);
    jdbc.update(integrity());
    jdbc.update(health());
    rejected(jdbc, integrity());
    rejected(jdbc, health());
    assertThat(
            jdbc.queryForObject(
                "select publication_revision from provider_credential_health", Long.class))
        .isEqualTo(1);
    // Precision is a storage contract: both engines store exactly microsecond precision.
    assertThat(
            jdbc.queryForObject(
                    "select observed_at from provider_credential_health",
                    java.time.OffsetDateTime.class)
                .getNano())
        .isEqualTo(123456000);
  }
}

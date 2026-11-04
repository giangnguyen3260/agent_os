package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.test.context.TestExecutionListeners(
    listeners =
        org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class)
abstract class ProviderPolicyFixture {
  @Autowired JdbcTemplate jdbc;
  @Autowired PolicyService policies;
  @Autowired AdmissionService admission;
  @Autowired ProviderListSnapshot providerList;
  @Autowired PlatformTransactionManager manager;
  UUID org, actor;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    jdbc.update("delete from provider_read_admission");
    jdbc.update("delete from provider_deployment_policy");
    org = UUID.randomUUID();
    actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Policy',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Policy','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    human = new HumanIdentity(actor, "unused@example.test", "Policy", 0);
  }

  List<Map<String, Object>> policyRows() {
    return jdbc.queryForList("select * from provider_deployment_policy order by id");
  }

  List<Map<String, Object>> quotaRows() {
    return jdbc.queryForList(
        "select * from provider_read_admission order by organization_id,actor_id");
  }

  static void denied(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            PolicyFailure.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(code);
              assertThat(failure.getCause()).isNull();
            });
  }

  @Test
  void providerListCountsDisabledRowsAndPagesWithinAuthorizedOrganization() {
    for (int index = 1; index <= 21; index++) {
      UUID connection = new UUID(org.getMostSignificantBits(), index);
      jdbc.update(
          """
          insert into provider_credential_versions
          values (?,?,'OPENAI',1,'reference','version','key-version','v2','verifier',
                  timestamp with time zone '2026-11-03 00:00:00+00')
          """,
          org,
          connection);
      jdbc.update(
          """
          insert into provider_connections
          values (?,?,'OPENAI','OPENAI_API_KEY','Connection',null,'DISABLED',1,1,
                  timestamp with time zone '2026-11-03 00:00:00+00',
                  timestamp with time zone '2026-11-03 00:00:00+00')
          """,
          connection,
          org);
    }

    var first = providerList.read(human, org, 0);
    assertThat(first.total()).isEqualTo(21);
    assertThat(first.rows()).hasSize(20);
    assertThat(first.rows().getFirst().id()).isEqualTo(new UUID(org.getMostSignificantBits(), 21));
    assertThat(first.rows())
        .allMatch(row -> row.organization().equals(org) && row.state().equals("DISABLED"));
    var second = providerList.read(human, org, 1);
    assertThat(second.total()).isEqualTo(21);
    assertThat(second.rows())
        .extracting(ProviderListSnapshot.Row::id)
        .containsExactly(new UUID(org.getMostSignificantBits(), 1));
    assertThat(providerList.read(human, org, 2).rows()).isEmpty();
    denied(() -> providerList.read(human, UUID.randomUUID(), 0), "FORBIDDEN");
  }

  @Test
  void emptyStorageAndEveryExplicitSubset() {
    assertThat(policyRows()).isEmpty();
    assertThat(quotaRows()).isEmpty();
    denied(policies::readTrusted, "PROVIDER_CONNECTION_UNAVAILABLE");
    denied(() -> policies.options(human, org), "PROVIDER_CONNECTION_UNAVAILABLE");
    for (int mask = 1; mask < 8; mask++) {
      jdbc.update("delete from provider_deployment_policy");
      var providers = EnumSet.noneOf(Provider.class);
      for (Provider provider : Provider.values())
        if ((mask & (1 << provider.ordinal())) != 0) providers.add(provider);
      var policy = policies.provision(providers);
      assertThat(policy.revision()).isEqualTo(1);
      assertThat(policies.readTrusted()).isEqualTo(policy);
      var options = policies.options(human, org);
      assertThat(options.policyRevision()).isEqualTo("1");
      assertThat(options.providers())
          .extracting(PolicyService.ProviderOption::provider)
          .containsExactly(Provider.values());
      for (var option : options.providers())
        assertThat(option.enabled()).isEqualTo(providers.contains(option.provider()));
      var before = policyRows();
      denied(() -> policies.provision(Set.of(Provider.OPENAI)), "POLICY_ALREADY_INITIALIZED");
      assertThat(policyRows()).isEqualTo(before);
    }
  }

  @Test
  void casNoopAbaMaxAndRollbackPreserveAllValues() {
    var first = policies.provision(Set.of(Provider.OPENAI));
    var before = policyRows();
    denied(() -> policies.replace(2, Set.of(Provider.OPENAI)), "REVISION_CONFLICT");
    denied(() -> policies.replace(1, Set.of(Provider.OPENAI)), "NO_CHANGE");
    assertThat(policyRows()).isEqualTo(before);
    policies.replace(1, Set.of(Provider.ANTHROPIC));
    var third = policies.replace(2, Set.of(Provider.OPENAI));
    assertThat(third.revision()).isEqualTo(3);
    assertThat(third.createdAt()).isEqualTo(first.createdAt());
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    before = policyRows();
    transaction.executeWithoutResult(
        status -> {
          policies.replace(3, Set.of(Provider.GOOGLE_GEMINI));
          status.setRollbackOnly();
        });
    assertThat(policyRows()).isEqualTo(before);
    jdbc.update("update provider_deployment_policy set revision=?", Long.MAX_VALUE);
    before = policyRows();
    denied(() -> policies.replace(Long.MAX_VALUE, Set.of(Provider.OPENAI)), "NO_CHANGE");
    denied(
        () -> policies.replace(Long.MAX_VALUE, Set.of(Provider.ANTHROPIC)), "REVISION_EXHAUSTED");
    assertThat(policyRows()).isEqualTo(before);
  }

  @Test
  void directAuthorityAndUnsupportedJoinedContextsFailClosed() {
    policies.provision(Set.of(Provider.OPENAI));
    denied(() -> policies.options(null, org), "UNAUTHORIZED");
    denied(() -> policies.options(human, UUID.randomUUID()), "FORBIDDEN");
    denied(() -> policies.options(new HumanIdentity(actor, "x", "x", 1), org), "FORBIDDEN");
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", actor);
    denied(() -> policies.options(human, org), "FORBIDDEN");
    denied(() -> admission.acquire(human, org), "FORBIDDEN");
    assertThat(quotaRows()).isEmpty();
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        status -> {
          denied(policies::readTrusted, "PROVIDER_CONNECTION_UNAVAILABLE");
          status.setRollbackOnly();
        });
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setReadOnly(true);
    transaction.executeWithoutResult(
        status -> {
          denied(
              () -> policies.replace(1, Set.of(Provider.ANTHROPIC)),
              "PROVIDER_CONNECTION_UNAVAILABLE");
          status.setRollbackOnly();
        });
  }

  @Test
  void quota120Then121PreservesEntireRowAndCorruptionCannotReset() {
    for (int i = 0; i < 120; i++) admission.acquire(human, org);
    var before = quotaRows();
    assertThat(
            AdmissionService.parse((String) before.get(0).get("recent_requests"), Long.MAX_VALUE))
        .hasSize(120);
    assertThatThrownBy(() -> admission.acquire(human, org))
        .isInstanceOfSatisfying(
            PolicyFailure.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(429);
              assertThat(failure.retryAfter()).isBetween(1, 60);
            });
    assertThat(quotaRows()).isEqualTo(before);
    for (String invalid :
        List.of(
            "[1,0]",
            "[-1]",
            "[1.0]",
            "[null]",
            "[01]",
            "[9223372036854775808]",
            " [1]",
            "[" + Long.MAX_VALUE + "]")) {
      jdbc.update("update provider_read_admission set recent_requests=?", invalid);
      before = quotaRows();
      denied(() -> admission.acquire(human, org), "PROVIDER_CONNECTION_UNAVAILABLE");
      assertThat(quotaRows()).isEqualTo(before);
    }
    jdbc.update(
        "update provider_read_admission set recent_requests='[]',updated_at=?",
        PolicyService.stamp(Instant.now().plusSeconds(3600)));
    before = quotaRows();
    denied(() -> admission.acquire(human, org), "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(quotaRows()).isEqualTo(before);
  }

  @Test
  void provisionRaceHasExactlyOneWinner() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Callable<String> openai =
          () -> {
            start.await();
            try {
              policies.provision(Set.of(Provider.OPENAI));
              return "WIN";
            } catch (PolicyFailure failure) {
              return failure.code();
            }
          };
      var first = executor.submit(openai);
      var second = executor.submit(openai);
      start.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("WIN", "POLICY_ALREADY_INITIALIZED");
      assertThat(policyRows()).hasSize(1);
      assertThat(policies.readTrusted().revision()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tableColumnAndNullabilityAllowlistsAndNoDefaults() {
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns where"
                    + " table_name='provider_deployment_policy' order by ordinal_position",
                String.class))
        .containsExactly(
            "id",
            "revision",
            "openai_enabled",
            "anthropic_enabled",
            "gemini_enabled",
            "created_at",
            "updated_at");
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns where"
                    + " table_name='provider_read_admission' order by ordinal_position",
                String.class))
        .containsExactly("organization_id", "actor_id", "recent_requests", "updated_at");
    for (String table : List.of("provider_deployment_policy", "provider_read_admission")) {
      assertThat(
              jdbc.queryForList(
                  "select is_nullable from information_schema.columns where table_name=?",
                  String.class,
                  table))
          .containsOnly("NO");
      assertThat(
              jdbc.queryForList(
                  "select column_default from information_schema.columns where table_name=?",
                  String.class,
                  table))
          .allMatch(value -> value == null);
    }
  }

  @Test
  void exactWindowCutoffAndCeilBoundaries() {
    long now = 120_000_000L;
    assertThat(AdmissionService.append(List.of(now - 60_000_000L), now)).containsExactly(now);
    for (long remaining : List.of(1L, 999_999L, 1_000_000L, 1_000_001L, 60_000_000L)) {
      var full = java.util.Collections.nCopies(120, now - 60_000_000L + remaining);
      assertThatThrownBy(() -> AdmissionService.append(full, now))
          .isInstanceOfSatisfying(
              PolicyFailure.class,
              failure -> {
                assertThat(failure.code()).isEqualTo("RATE_LIMITED");
                assertThat(failure.retryAfter())
                    .isEqualTo((int) ((remaining + 999_999L) / 1_000_000L));
              });
    }
    assertThatThrownBy(() -> AdmissionService.micros(Instant.MAX))
        .isInstanceOf(PolicyFailure.class);
    assertThatThrownBy(() -> AdmissionService.micros(Instant.MIN))
        .isInstanceOf(PolicyFailure.class);
    for (String invalid :
        List.of(
            "[+1]",
            "[1e0]",
            "[1, 2]",
            "[] ",
            "[1]".repeat(1400),
            "[" + String.join(",", java.util.Collections.nCopies(121, "0")) + "]"))
      denied(
          () -> AdmissionService.parse(invalid, Long.MAX_VALUE), "PROVIDER_CONNECTION_UNAVAILABLE");
  }

  @Test
  void acceptedSecurityRowUsesExactlyOneMicrosecondObservation() {
    admission.acquire(human, org);
    var time =
        jdbc.queryForObject(
            "select updated_at from provider_read_admission where organization_id=? and actor_id=?",
            java.time.OffsetDateTime.class,
            org,
            actor);
    var requests =
        AdmissionService.parse((String) quotaRows().get(0).get("recent_requests"), Long.MAX_VALUE);
    assertThat(requests).containsExactly(AdmissionService.micros(time.toInstant()));
    assertThat(time.getNano() % 1000).isZero();
  }

  @Test
  void admissionOuterRollbackLeavesNoCandidateAndBucketsAreIndependent() {
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        status -> {
          admission.acquire(human, org);
          status.setRollbackOnly();
        });
    assertThat(quotaRows()).isEmpty();
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Other','unused')",
        other,
        other + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        other,
        org);
    admission.acquire(human, org);
    admission.acquire(new HumanIdentity(other, "x", "x", 0), org);
    assertThat(quotaRows()).hasSize(2);
  }

  @Test
  void failedInitialAdmissionRollsBackCandidateInsert() {
    var faultJdbc =
        new JdbcTemplate(jdbc.getDataSource()) {
          @Override
          public <T> T queryForObject(String sql, Class<T> type) {
            if (sql.equals("select clock_timestamp()") || sql.equals("select current_timestamp"))
              return type.cast(java.time.OffsetDateTime.MAX);
            return super.queryForObject(sql, type);
          }
        };
    var failing = new AdmissionService(new PolicyJdbc(faultJdbc), new PolicyAuthority(faultJdbc));
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    denied(
        () -> transaction.executeWithoutResult(status -> failing.acquire(human, org)),
        "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(quotaRows()).isEmpty();
  }

  @Test
  void oversizedStoredArrayIsBoundedlyRejectedWithoutAnyRowChange() {
    admission.acquire(human, org);
    String oversized = "[" + String.join(",", java.util.Collections.nCopies(1000, "0")) + "]";
    assertThat(oversized.length()).isLessThan(4096);
    jdbc.update(
        "update provider_read_admission set recent_requests=? where organization_id=? and"
            + " actor_id=?",
        oversized,
        org,
        actor);
    var before = quotaRows();
    denied(() -> admission.acquire(human, org), "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(quotaRows()).isEqualTo(before);
  }

  @Test
  void acceptLexicalGrammarAndSpecificityAreStrict() throws Exception {
    for (String accept :
        List.of(
            "",
            " ",
            "application/json;q=+1",
            "application/json;q=1e0",
            "application/json;q=0.1234",
            "application/json;q=0;q=1",
            "application/json;q=0, */*;q=1",
            "application/json,application/json;q=0",
            "application/json,",
            "application/json, , */*",
            "application/json;profile=a;PROFILE=b",
            "application/json;charset=UTF-8;CHARSET=UTF-8",
            "application/json;profile=\"unclosed")) {
      var request = new org.springframework.mock.web.MockHttpServletRequest();
      request.addHeader("Accept", accept);
      assertThatThrownBy(() -> OptionsWire.validate(request, org.toString()))
          .isInstanceOfSatisfying(
              PolicyFailure.class, failure -> assertThat(failure.code()).isEqualTo("BAD_REQUEST"));
    }
    for (String accept :
        List.of(
            "application/json",
            "application/json;q=0.5",
            "application/*;q=0.1",
            "*/*",
            "application/json;q=1,*/*;q=0",
            "application/json;profile=\"x;q=1\"",
            "application/json;profile=\"x,,y;q=1\";q=1")) {
      var request = new org.springframework.mock.web.MockHttpServletRequest();
      request.addHeader("Accept", accept);
      OptionsWire.validate(request, org.toString());
    }
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setQueryString("");
    assertThatThrownBy(() -> OptionsWire.validate(request, org.toString()))
        .isInstanceOf(PolicyFailure.class);
  }

  @Test
  void completeColumnTypesAndBoundedSecurityStorage() {
    var expected =
        Map.ofEntries(
            Map.entry("provider_deployment_policy.id", "smallint"),
            Map.entry("provider_deployment_policy.revision", "bigint"),
            Map.entry("provider_deployment_policy.openai_enabled", "boolean"),
            Map.entry("provider_deployment_policy.anthropic_enabled", "boolean"),
            Map.entry("provider_deployment_policy.gemini_enabled", "boolean"),
            Map.entry("provider_deployment_policy.created_at", "timestamp with time zone"),
            Map.entry("provider_deployment_policy.updated_at", "timestamp with time zone"),
            Map.entry("provider_read_admission.organization_id", "uuid"),
            Map.entry("provider_read_admission.actor_id", "uuid"),
            Map.entry("provider_read_admission.recent_requests", "character varying"),
            Map.entry("provider_read_admission.updated_at", "timestamp with time zone"));
    var actual = new java.util.LinkedHashMap<String, String>();
    jdbc.query(
        "select table_name,column_name,data_type from information_schema.columns where table_name"
            + " in ('provider_deployment_policy','provider_read_admission')",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                actual.put(
                    rs.getString(1) + "." + rs.getString(2),
                    rs.getString(3).toLowerCase(java.util.Locale.ROOT)));
    assertThat(actual).isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                "select character_maximum_length from information_schema.columns where"
                    + " table_name='provider_read_admission' and column_name='recent_requests'",
                Long.class))
        .isEqualTo(4096L);
  }
}

package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PolicyCoreConfiguration.class)
public class ProviderPolicyPostgresIT extends ProviderPolicyFixture {
  static String environment(String suffix) {
    String value = System.getenv("TASK_EDIT_TEST_" + suffix);
    if (value == null
        || value.isBlank()
        || (suffix.equals("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException("Explicit native PostgreSQL configuration required");
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> environment("JDBC_URL"));
    properties.add("spring.datasource.username", () -> environment("DB_USER"));
    properties.add("spring.datasource.password", () -> environment("DB_PASSWORD"));
  }

  @Test
  void selectedInfiniteTimestampsFailClosedWithoutRepair() {
    policies.provision(Set.of(Provider.OPENAI));
    jdbc.update(
        "update provider_deployment_policy set created_at='-infinity',updated_at='infinity'");
    var before = policyRows();
    denied(policies::readTrusted, "PROVIDER_CONNECTION_UNAVAILABLE");
    denied(() -> policies.options(human, org), "PROVIDER_CONNECTION_UNAVAILABLE");
    denied(
        () -> policies.replace(1, Set.of(Provider.ANTHROPIC)), "PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(policyRows()).isEqualTo(before);
    admission.acquire(human, org);
    for (String infinity : List.of("infinity", "-infinity")) {
      jdbc.update("update provider_read_admission set updated_at=cast(? as timestamptz)", infinity);
      var quota = quotaRows();
      denied(() -> admission.acquire(human, org), "PROVIDER_CONNECTION_UNAVAILABLE");
      assertThat(quotaRows()).isEqualTo(quota);
    }
  }

  @Test
  void nativeCasRaceSerializesAtSingleton() throws Exception {
    policies.provision(Set.of(Provider.OPENAI));
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Callable<String> first =
          () -> {
            start.await();
            try {
              policies.replace(1, Set.of(Provider.ANTHROPIC));
              return "WIN";
            } catch (PolicyFailure failure) {
              return failure.code();
            }
          };
      Callable<String> second =
          () -> {
            start.await();
            try {
              policies.replace(1, Set.of(Provider.GOOGLE_GEMINI));
              return "WIN";
            } catch (PolicyFailure failure) {
              return failure.code();
            }
          };
      var a = executor.submit(first);
      var b = executor.submit(second);
      start.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("WIN", "REVISION_CONFLICT");
      assertThat(policies.readTrusted().revision()).isEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void repeatableReadKeepsAuthorityAndPolicyInOneSnapshot() throws Exception {
    policies.provision(Set.of(Provider.OPENAI));
    var executor = Executors.newSingleThreadExecutor();
    var read = new TransactionTemplate(manager);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
    try {
      read.executeWithoutResult(
          status -> {
            var first = policies.options(human, org);
            try {
              executor
                  .submit(
                      () -> {
                        policies.replace(1, Set.of(Provider.ANTHROPIC));
                        jdbc.update(
                            "update organization_memberships set role='MEMBER' where account_id=?"
                                + " and organization_id=?",
                            actor,
                            org);
                      })
                  .get(15, TimeUnit.SECONDS);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            assertThat(policies.options(human, org)).isEqualTo(first);
          });
      denied(() -> policies.options(human, org), "FORBIDDEN");
      assertThat(policies.readTrusted().revision()).isEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
  }

  private Connection lockQuota() throws Exception {
    Connection connection = jdbc.getDataSource().getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            "select * from provider_read_admission where organization_id=? and actor_id=? for"
                + " update")) {
      statement.setObject(1, org);
      statement.setObject(2, actor);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
    }
    return connection;
  }

  private void observeQuotaWait() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select count(*)>0 from pg_stat_activity where datname=current_database() and"
                  + " pid<>pg_backend_pid() and wait_event_type='Lock' and query like"
                  + " '%provider_read_admission%'",
              Boolean.class))) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Expected native quota lock wait was not observed");
  }

  @Test
  void observedQuotaWaitReauthorizesAfterMembershipRevocation() throws Exception {
    admission.acquire(human, org);
    var before = quotaRows();
    var executor = Executors.newSingleThreadExecutor();
    try (Connection blocker = lockQuota()) {
      var waiting =
          executor.submit(
              () -> {
                try {
                  admission.acquire(human, org);
                  return "ACCEPTED";
                } catch (PolicyFailure failure) {
                  return failure.code();
                }
              });
      observeQuotaWait();
      jdbc.update(
          "update organization_memberships set role='MEMBER' where account_id=? and"
              + " organization_id=?",
          actor,
          org);
      blocker.commit();
      assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
      assertThat(quotaRows()).isEqualTo(before);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void observedQuotaWaitSamplesPostLockDatabaseClockOnce() throws Exception {
    admission.acquire(human, org);
    var executor = Executors.newSingleThreadExecutor();
    try (Connection blocker = lockQuota()) {
      var waiting = executor.submit(() -> admission.acquire(human, org));
      observeQuotaWait();
      OffsetDateTime release =
          jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
      blocker.commit();
      waiting.get(15, TimeUnit.SECONDS);
      var row = quotaRows().get(0);
      OffsetDateTime updated =
          jdbc.queryForObject(
              "select updated_at from provider_read_admission where organization_id=? and"
                  + " actor_id=?",
              OffsetDateTime.class,
              org,
              actor);
      assertThat(updated).isAfterOrEqualTo(release);
      var requests = AdmissionService.parse((String) row.get("recent_requests"), Long.MAX_VALUE);
      assertThat(requests).hasSize(2);
      assertThat(requests.get(1)).isEqualTo(AdmissionService.micros(updated.toInstant()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void observedPolicyWaitUsesPostLockClockAndPreservesCreatedAt() throws Exception {
    var before = policies.provision(Set.of(Provider.OPENAI));
    var executor = Executors.newSingleThreadExecutor();
    try (Connection blocker = jdbc.getDataSource().getConnection()) {
      blocker.setAutoCommit(false);
      try (var lock =
              blocker.prepareStatement(
                  "select * from provider_deployment_policy where id=1 for update");
          var rows = lock.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
      var waiting = executor.submit(() -> policies.replace(1, Set.of(Provider.ANTHROPIC)));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean observed = false;
      while (System.nanoTime() < deadline) {
        if (Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select count(*)>0 from pg_stat_activity where datname=current_database() and"
                    + " pid<>pg_backend_pid() and wait_event_type='Lock' and query like"
                    + " '%provider_deployment_policy%'",
                Boolean.class))) {
          observed = true;
          break;
        }
        Thread.sleep(20);
      }
      assertThat(observed).isTrue();
      OffsetDateTime release =
          jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
      blocker.commit();
      var changed = waiting.get(15, TimeUnit.SECONDS);
      assertThat(changed.revision()).isEqualTo(2);
      assertThat(changed.createdAt()).isEqualTo(before.createdAt());
      assertThat(changed.updatedAt()).isAfterOrEqualTo(release.toInstant());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void nativeConstraintAndCascadeAllowlistsAreExact() {
    var policy =
        jdbc
            .queryForList(
                "select pg_get_constraintdef(oid) from pg_constraint where"
                    + " conrelid='provider_deployment_policy'::regclass",
                String.class)
            .stream()
            .map(value -> value.replaceAll("[\\s()]", "").toLowerCase(java.util.Locale.ROOT))
            .toList();
    assertThat(policy)
        .containsExactlyInAnyOrder(
            "primarykeyid",
            "checkid=1",
            "checkrevision>0",
            "checkopenai_enabledoranthropic_enabledorgemini_enabled",
            "checkupdated_at>=created_at");
    assertThat(
            jdbc.queryForList(
                "select pg_get_constraintdef(oid) from pg_constraint where"
                    + " conrelid='provider_read_admission'::regclass",
                String.class))
        .containsExactlyInAnyOrder(
            "PRIMARY KEY (organization_id, actor_id)",
            "FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE",
            "FOREIGN KEY (actor_id) REFERENCES human_accounts(id) ON DELETE CASCADE");
    assertThat(
            jdbc.queryForList(
                "select confdeltype::text from pg_constraint where"
                    + " conrelid='provider_read_admission'::regclass and contype='f'",
                String.class))
        .containsExactlyInAnyOrder("c", "c");
  }
}

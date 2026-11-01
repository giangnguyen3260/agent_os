package com.agentos.providerreceipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercommands.CommandBoundary.Command;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Requires an explicitly configured native PostgreSQL database; never silently skips. */
@SpringBootTest
@ActiveProfiles("test")
public class ProviderReceiptPostgresIT extends ProviderReceiptFixture {
  static String environment(String suffix) {
    String value = System.getenv("TASK_EDIT_TEST_" + suffix);
    if (value == null
        || value.isBlank()
        || (suffix.equals("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException(
          "Explicit native PostgreSQL configuration required: TASK_EDIT_TEST_" + suffix);
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> environment("JDBC_URL"));
    properties.add("spring.datasource.username", () -> environment("DB_USER"));
    properties.add("spring.datasource.password", () -> environment("DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
  }

  int lockOrganization(Connection holder) throws Exception {
    holder.setAutoCommit(false);
    var sql = new JdbcTemplate(new SingleConnectionDataSource(holder, true));
    int pid = sql.queryForObject("select pg_backend_pid()", Integer.class);
    sql.queryForList("select id from organizations where id=? for update", org);
    return pid;
  }

  void observedWait(int holder, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < deadline) {
      Long waiting =
          jdbc.queryForObject(
              "with recursive blocked(pid) as (select pid from pg_stat_activity where"
                  + " datname=current_database() and wait_event_type='Lock' and"
                  + " ?=any(pg_blocking_pids(pid)) union select a.pid from pg_stat_activity a join"
                  + " blocked b on b.pid=any(pg_blocking_pids(a.pid)) where"
                  + " a.datname=current_database() and a.wait_event_type='Lock') select"
                  + " count(distinct pid) from blocked",
              Long.class,
              holder);
      if (waiting >= expected) return;
      Thread.sleep(15);
    }
    throw new AssertionError("Expected observed PostgreSQL lock waiters: " + expected);
  }

  String outcome(Callable<?> operation) throws Exception {
    try {
      operation.call();
      return "SUCCESS";
    } catch (ProviderReceiptFailure ex) {
      return ex.code();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameKeyConcurrentCallsSerializeWithExactlyOneEventAndReceipt(boolean mismatch)
      throws Exception {
    Create c = createCommand(Provider.OPENAI);
    Metadata m = prepare(c);
    var cred = credential(Provider.OPENAI, 1);
    Create other = new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Other", null);
    Metadata otherProof = mismatch ? prepare(other) : m;
    var executor = Executors.newFixedThreadPool(2);
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      var first = executor.submit(() -> service.execute(human, org, key, c, m, id, cred));
      observedWait(pid, 1);
      var second =
          executor.submit(
              (Callable<Object>)
                  () -> {
                    try {
                      return service.execute(
                          human, org, key, mismatch ? other : c, otherProof, null, null);
                    } catch (ProviderReceiptFailure failure) {
                      return failure;
                    }
                  });
      observedWait(pid, 2);
      var releaseBound =
          new JdbcTemplate(new SingleConnectionDataSource(holder, true))
              .queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class);
      holder.commit();
      var winner = first.get(15, TimeUnit.SECONDS);
      assertThat(winner.receipt().recordedAt()).isAfterOrEqualTo(releaseBound);
      assertThat(winner.receipt().after().updatedAt()).isAfterOrEqualTo(releaseBound);
      assertThat(
              jdbc.queryForObject(
                  "select created_at from provider_connection_events where organization_id=? and"
                      + " connection_id=? and configuration_revision=?",
                  java.time.OffsetDateTime.class,
                  org,
                  id,
                  winner.receipt().after().revision()))
          .isAfterOrEqualTo(releaseBound);
      Object secondOutcome = second.get(15, TimeUnit.SECONDS);
      assertThat(winner.replayed()).isFalse();
      if (mismatch) {
        assertThat(secondOutcome)
            .isInstanceOfSatisfying(
                ProviderReceiptFailure.class,
                failure -> {
                  assertThat(failure.status()).isEqualTo(409);
                  assertThat(failure.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                  assertThat(failure.getCause()).isNull();
                });
      } else {
        assertThat(secondOutcome)
            .isInstanceOfSatisfying(
                ProviderReceiptService.Result.class,
                replay -> {
                  assertThat(replay.replayed()).isTrue();
                  assertThat(replay.receipt()).isEqualTo(winner.receipt());
                });
      }
      assertThat(service.findOwn(human, org, key).receipt()).isEqualTo(winner.receipt());
      assertThat(count("provider_command_receipts")).isEqualTo(1);
      assertThat(count("provider_connections")).isEqualTo(1);
      assertThat(count("provider_credential_versions")).isEqualTo(1);
      assertThat(count("provider_connection_events")).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE", "REMOVED", "DISABLED", "VERSION"})
  void authorizationRecheckedAfterObservedOrganizationWait(String revocation) throws Exception {
    create();
    var before = snapshot();
    var c = new Rename(id, 1, "Forbidden after wait");
    var m = prepare(c);
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      var pending =
          executor.submit(
              () ->
                  outcome(() -> service.execute(human, org, UUID.randomUUID(), c, m, null, null)));
      observedWait(pid, 1);
      var sql = new JdbcTemplate(new SingleConnectionDataSource(holder, true));
      switch (revocation) {
        case "ROLE" ->
            sql.update(
                "update organization_memberships set role='MEMBER' where organization_id=? and"
                    + " account_id=?",
                org,
                actor);
        case "REMOVED" ->
            sql.update(
                "delete from organization_memberships where organization_id=? and account_id=?",
                org,
                actor);
        case "DISABLED" -> sql.update("update human_accounts set enabled=false where id=?", actor);
        default -> sql.update("update human_accounts set credential_version=1 where id=?", actor);
      }
      holder.commit();
      assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
      var after = snapshot();
      for (String table :
          List.of(
              "provider_command_receipts",
              "provider_connections",
              "provider_credential_versions",
              "provider_connection_events"))
        assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void absentReceiptIsOnlyAnObservationWithinReadSnapshot() throws Exception {
    var c = createCommand(Provider.OPENAI);
    var m = prepare(c);
    var cred = credential(Provider.OPENAI, 1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      read()
          .executeWithoutResult(
              status -> {
                assertThat(service.findOwn(human, org, key)).isNull();
                try {
                  executor
                      .submit(() -> service.execute(human, org, key, c, m, id, cred))
                      .get(15, TimeUnit.SECONDS);
                } catch (Exception ex) {
                  throw new AssertionError(ex);
                }
                assertThat(service.probeOwn(human, org, key)).isNull();
              });
      assertThat(service.findOwn(human, org, key)).isNotNull();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void legacyCreationEventIsRetainedByNamedCreationForeignKey() {
    var cred = credential(Provider.OPENAI, 1);
    connections.create(human, org, id, "Legacy", null, cred);
    connections.rename(human, org, id, 1, "Legacy next");
    Command c = new RevisionCommand(Operation.DISABLE, id, 2);
    service.execute(human, org, key, c, prepare(c), null, null);
    var row =
        jdbc.queryForMap("select * from provider_command_receipts where organization_id=?", org);
    assertThat(row.get("creation_event_id"))
        .isNotEqualTo(row.get("before_event_id"))
        .isNotEqualTo(row.get("after_event_id"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from provider_connection_events where id=?",
                    row.get("creation_event_id")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("provider_command_receipts_creation_fk");
    assertThat(service.findOwn(human, org, key).receipt().after().revision()).isEqualTo(3);
  }

  @Test
  void commandRecordedAtIsWallClockAfterObservedLockWait() throws Exception {
    create();
    Command c = new Rename(id, 1, "After wait");
    Metadata m = prepare(c);
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      var pending =
          executor.submit(() -> service.execute(human, org, UUID.randomUUID(), c, m, null, null));
      observedWait(pid, 1);
      var release =
          new JdbcTemplate(new SingleConnectionDataSource(holder, true))
              .queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class);
      holder.commit();
      var result = pending.get(15, TimeUnit.SECONDS);
      assertThat(result.receipt().recordedAt()).isAfterOrEqualTo(release);
      assertThat(result.receipt().after().updatedAt()).isAfterOrEqualTo(release);
    } finally {
      executor.shutdownNow();
    }
  }
}

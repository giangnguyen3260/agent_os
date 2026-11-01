package com.agentos.providerconnections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.auth.HumanIdentity;
import com.agentos.auth.RecoveryService;
import com.agentos.governance.api.MemberRoleManagementResponse.Command;
import com.agentos.governance.application.MemberRoleManagementService;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Native PostgreSQL suite: inherited relational cases plus observed serialization tests. */
@SpringBootTest(properties = "agentos.recovery.enabled=false")
@ActiveProfiles("test")
public class ProviderConnectionPostgresIT extends ProviderConnectionFixture {
  @Autowired MemberRoleManagementService roles;
  @Autowired RecoveryService recovery;
  UUID secondAdmin;

  @AfterEach
  void cleanupAuthorityWriters() {
    jdbc.update("delete from member_role_receipts where organization_id=?", org);
    jdbc.update("delete from member_role_events where organization_id=?", org);
    jdbc.update("delete from member_role_ledgers where organization_id=?", org);
    jdbc.update(
        "delete from audit_events where organization_id=? and event_type='MEMBER_ROLE_CHANGED'",
        org);
    jdbc.update("delete from password_reset_tokens where account_id=?", actor);
    jdbc.update("delete from recovery_mail_jobs where account_id=?", actor);
    jdbc.update("delete from authentication_audit where account_id=?", actor);
    if (secondAdmin != null) {
      jdbc.update(
          "delete from organization_memberships where organization_id=? and account_id=?",
          org,
          secondAdmin);
      jdbc.update("delete from human_accounts where id=?", secondAdmin);
    }
  }

  HumanIdentity secondAdmin() {
    secondAdmin = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Second"
            + " admin','unused')",
        secondAdmin,
        secondAdmin + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        secondAdmin,
        org);
    return new HumanIdentity(secondAdmin, secondAdmin + "@example.test", "Second admin", 0);
  }

  MemberRoleManagementService.Mutation demote(HumanIdentity administrator) {
    return roles.mutate(
        org,
        actor,
        administrator,
        UUID.randomUUID(),
        new Command(0, "ADMIN", "MEMBER", "Provider persistence compatibility test", true));
  }

  void unaffectedExistingTables(
      Map<String, List<Map<String, Object>>> before, String... permitted) {
    var after = existingTables();
    var allowed = java.util.Set.of(permitted);
    before.forEach(
        (table, rows) -> {
          if (!allowed.contains(table)) assertThat(after.get(table)).as(table).isEqualTo(rows);
        });
  }

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
    int pid;
    try (var statement = holder.createStatement();
        var result = statement.executeQuery("select pg_backend_pid()")) {
      result.next();
      pid = result.getInt(1);
    }
    try (var statement =
        holder.prepareStatement("select id from organizations where id=? for update")) {
      statement.setObject(1, org);
      statement.executeQuery().close();
    }
    return pid;
  }

  void observedWait(int holderPid, int writers) throws Exception {
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
              holderPid);
      if (waiting >= writers) return;
      Thread.sleep(15);
    }
    throw new AssertionError("Expected " + writers + " observed PostgreSQL lock waiters");
  }

  String outcome(Callable<?> command) throws Exception {
    try {
      command.call();
      return "SUCCESS";
    } catch (ProviderConnectionFailure ex) {
      return ex.code();
    }
  }

  @Test
  void competingCasHasExactlyOneWinnerAfterObservedOrganizationWait() throws Exception {
    var metadata = prepared(org, id, Provider.OPENAI, 1);
    var expected = new ProviderConnectionExpectedRows(jdbc, org, id, actor);
    expected.accept(
        service.create(human, org, id, "Connection", null, metadata),
        metadata,
        Provider.OPENAI,
        "Connection",
        null,
        "ENABLED",
        1,
        1,
        "CREATE");
    var collateral = existingTables();
    var executor = Executors.newFixedThreadPool(2);
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      List<Future<String>> pending = new ArrayList<>();
      pending.add(
          executor.submit(() -> outcome(() -> service.rename(human, org, id, 1, "Winner A"))));
      pending.add(
          executor.submit(() -> outcome(() -> service.setEnabled(human, org, id, 1, false))));
      observedWait(pid, 2);
      holder.commit();
      String rename = pending.get(0).get(15, TimeUnit.SECONDS);
      String disable = pending.get(1).get(15, TimeUnit.SECONDS);
      assertThat(List.of(rename, disable))
          .containsExactlyInAnyOrder("SUCCESS", "REVISION_CONFLICT");
      boolean renamed = rename.equals("SUCCESS");
      expected.accept(
          service.get(human, org, id),
          metadata,
          Provider.OPENAI,
          renamed ? "Winner A" : "Connection",
          null,
          renamed ? "ENABLED" : "DISABLED",
          2,
          1,
          renamed ? "RENAME" : "DISABLE");
      assertThat(existingTables()).isEqualTo(collateral);
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE", "MEMBERSHIP", "DISABLED", "CREDENTIAL"})
  void simulatedAuthoritySqlIsRecheckedAfterObservedOrganizationLockWait(String revocation)
      throws Exception {
    create();
    var before = snapshot();
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      var pending =
          executor.submit(
              () -> outcome(() -> service.rename(human, org, id, 1, "Must not commit")));
      observedWait(pid, 1);
      // Fixture simulation only: no production disable/removal writer exists in reviewed source.
      revoke(new JdbcTemplate(new SingleConnectionDataSource(holder, true)), revocation);
      holder.commit();
      var collateral = existingTables();
      assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
      assertThat(existingTables()).isEqualTo(collateral);
      assertThat(snapshot()).isEqualTo(before);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void committedTimestampIsSampledAfterObservedLockWait() throws Exception {
    create();
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      var pending = executor.submit(() -> service.rename(human, org, id, 1, "After lock"));
      observedWait(pid, 1);
      var release =
          new JdbcTemplate(new SingleConnectionDataSource(holder, true))
              .queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class);
      holder.commit();
      assertThat(pending.get(15, TimeUnit.SECONDS).updatedAt()).isAfterOrEqualTo(release);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void authorityCountAndSelectedRowsJoinOneRepeatableReadSnapshot() throws Exception {
    create();
    var nextId = java.util.UUID.randomUUID();
    var metadata =
        prepared(
            org, nextId, com.agentos.providercredentials.CredentialBoundary.Provider.OPENAI, 1);
    var executor = Executors.newSingleThreadExecutor();
    var snapshot = new org.springframework.transaction.support.TransactionTemplate(transactions);
    snapshot.setReadOnly(true);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try {
      snapshot.executeWithoutResult(
          status -> {
            assertThat(service.get(human, org, id).revision()).isEqualTo(1);
            var writer =
                executor.submit(
                    () ->
                        service.create(
                            human, org, nextId, "Committed after snapshot", null, metadata));
            try {
              writer.get(15, TimeUnit.SECONDS);
            } catch (Exception ex) {
              throw new AssertionError(ex);
            }
            var page = service.list(human, org, 0);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items())
                .extracting(ProviderConnectionService.Connection::id)
                .containsExactly(id);
          });
      assertThat(service.list(human, org, 0).total()).isEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void actualRoleWriterSerializesWithProviderOnObservedOrganizationLock(boolean providerFirst)
      throws Exception {
    var administrator = secondAdmin();
    var metadata = prepared(org, id, Provider.OPENAI, 1);
    var expected = new ProviderConnectionExpectedRows(jdbc, org, id, actor);
    expected.accept(
        service.create(human, org, id, "Connection", null, metadata),
        metadata,
        Provider.OPENAI,
        "Connection",
        null,
        "ENABLED",
        1,
        1,
        "CREATE");
    var collateral = existingTables();
    var executor = Executors.newFixedThreadPool(2);
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lockOrganization(holder);
      Future<String> provider;
      Future<MemberRoleManagementService.Mutation> role;
      if (providerFirst) {
        provider =
            executor.submit(
                () -> {
                  var result = service.rename(human, org, id, 1, "Permitted before demotion");
                  expected.accept(
                      result,
                      metadata,
                      Provider.OPENAI,
                      "Permitted before demotion",
                      null,
                      "ENABLED",
                      2,
                      1,
                      "RENAME");
                  return "SUCCESS";
                });
        observedWait(pid, 1);
        role = executor.submit(() -> demote(administrator));
      } else {
        role = executor.submit(() -> demote(administrator));
        observedWait(pid, 1);
        provider =
            executor.submit(
                () -> outcome(() -> service.rename(human, org, id, 1, "Forbidden after demotion")));
      }
      observedWait(pid, 2);
      holder.commit();
      assertThat(provider.get(15, TimeUnit.SECONDS))
          .isEqualTo(providerFirst ? "SUCCESS" : "FORBIDDEN");
      var changed = role.get(15, TimeUnit.SECONDS);
      assertThat(changed.replay()).isFalse();
      assertThat(changed.result().accountId()).isEqualTo(actor);
      assertThat(changed.result().actorId()).isEqualTo(secondAdmin);
      assertThat(changed.result().beforeRole()).isEqualTo("ADMIN");
      assertThat(changed.result().afterRole()).isEqualTo("MEMBER");
      expected.assertMatches();
      unaffectedExistingTables(
          collateral,
          "organization_memberships",
          "member_role_ledgers",
          "member_role_events",
          "member_role_receipts",
          "audit_events");
      assertThat(count("member_role_events")).isEqualTo(1);
      assertThat(count("member_role_receipts")).isEqualTo(1);
      assertThat(count("member_role_ledgers")).isEqualTo(1);
      var afterAuthority = existingTables();
      error(
          () -> service.rename(human, org, id, providerFirst ? 2 : 1, "Fresh operation"),
          403,
          "FORBIDDEN");
      expected.assertMatches();
      assertThat(existingTables()).isEqualTo(afterAuthority);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void actualRecoveryWriterReleasesAccountWhileProviderAlreadyHoldsOrganization() throws Exception {
    create();
    var before = snapshot();
    var collateral = existingTables();
    String token = "R".repeat(43);
    String digest =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    jdbc.update(
        "insert into password_reset_tokens(token_digest,account_id,expires_at) values (?,?,?)",
        digest,
        actor,
        java.time.OffsetDateTime.now().plusMinutes(5));
    // Include intentionally seeded recovery state in the authority-writer baseline.
    collateral = existingTables();
    var locked = new java.util.concurrent.CompletableFuture<Integer>();
    var providerPid = new java.util.concurrent.CompletableFuture<Integer>();
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var reset =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            jdbc.queryForList(
                                "select id from human_accounts where id=? for update", actor);
                            boolean confirmed =
                                recovery.confirm(token, "Test-only-new-password-12345");
                            locked.complete(
                                jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            try {
                              if (!release.await(15, TimeUnit.SECONDS))
                                throw new AssertionError("Recovery release timed out");
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(ex);
                            }
                            return confirmed;
                          }));
      int recoveryPid = locked.get(15, TimeUnit.SECONDS);
      var provider =
          executor.submit(
              () ->
                  outcome(
                      () ->
                          new TransactionTemplate(transactions)
                              .execute(
                                  status -> {
                                    providerPid.complete(
                                        jdbc.queryForObject(
                                            "select pg_backend_pid()", Integer.class));
                                    return service.rename(
                                        human, org, id, 1, "Must not survive credential reset");
                                  })));
      int writerPid = providerPid.get(15, TimeUnit.SECONDS);
      observedWait(recoveryPid, 1);
      assertThat(
              jdbc.queryForObject(
                  "select ?=any(pg_blocking_pids(?))", Boolean.class, recoveryPid, writerPid))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select query from pg_stat_activity where pid=?", String.class, writerPid))
          .contains("human_accounts");
      // NOWAIT on a third connection proves the waiting provider already owns the organization.
      try (Connection probe = jdbc.getDataSource().getConnection()) {
        probe.setAutoCommit(false);
        try {
          assertThatThrownBy(
                  () -> {
                    try (var statement =
                        probe.prepareStatement(
                            "select id from organizations where id=? for update nowait")) {
                      statement.setObject(1, org);
                      statement.executeQuery().close();
                    }
                  })
              .isInstanceOfSatisfying(
                  java.sql.SQLException.class,
                  ex -> assertThat(ex.getSQLState()).isEqualTo("55P03"));
        } finally {
          probe.rollback();
        }
      }
      release.countDown();
      assertThat(reset.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(provider.get(15, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
      assertThat(snapshot()).isEqualTo(before);
      unaffectedExistingTables(
          collateral,
          "human_accounts",
          "password_reset_tokens",
          "authentication_audit",
          "recovery_mail_jobs",
          "spring_session",
          "spring_session_attributes",
          "authentication_attempts");
      assertThat(
              jdbc.queryForObject(
                  "select credential_version from human_accounts where id=?", Long.class, actor))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from authentication_audit where account_id=? and"
                      + " event_type='PASSWORD_RESET'",
                  Long.class,
                  actor))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from password_reset_tokens where account_id=?",
                  Long.class,
                  actor))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from recovery_mail_jobs where account_id=? and"
                      + " kind='PASSWORD_CHANGED'",
                  Long.class,
                  actor))
          .isEqualTo(1);
      var afterAuthority = existingTables();
      error(() -> service.get(human, org, id), 403, "FORBIDDEN");
      error(() -> service.rename(human, org, id, 1, "Fresh operation"), 403, "FORBIDDEN");
      assertThat(snapshot()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(afterAuthority);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void actualRoleRevocationPreservesEstablishedSnapshotButNextFreshReadIsDenied() throws Exception {
    var administrator = secondAdmin();
    create();
    var before = snapshot();
    var collateral = existingTables();
    var executor = Executors.newSingleThreadExecutor();
    var read = new TransactionTemplate(transactions);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try {
      read.executeWithoutResult(
          status -> {
            assertThat(service.get(human, org, id).revision()).isEqualTo(1);
            var revocation = executor.submit(() -> demote(administrator));
            try {
              assertThat(revocation.get(15, TimeUnit.SECONDS).result().afterRole())
                  .isEqualTo("MEMBER");
            } catch (Exception ex) {
              throw new AssertionError(ex);
            }
            assertThat(service.get(human, org, id).revision()).isEqualTo(1);
            assertThat(service.list(human, org, 0).total()).isEqualTo(1);
          });
      assertThat(snapshot()).isEqualTo(before);
      unaffectedExistingTables(
          collateral,
          "organization_memberships",
          "member_role_ledgers",
          "member_role_events",
          "member_role_receipts",
          "audit_events");
      var afterAuthority = existingTables();
      error(() -> service.get(human, org, id), 403, "FORBIDDEN");
      error(() -> service.list(human, org, 0), 403, "FORBIDDEN");
      assertThat(snapshot()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(afterAuthority);
    } finally {
      executor.shutdownNow();
    }
  }
}

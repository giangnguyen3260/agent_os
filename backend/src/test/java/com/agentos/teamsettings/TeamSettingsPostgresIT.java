package com.agentos.teamsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.teamsettings.TeamSettingsDtos.Command;
import com.agentos.teamsettings.TeamSettingsDtos.Result;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Native PostgreSQL suite, including inherited persistence/rollback/HTTP tests. The Quality
 * PostgreSQL gate explicitly selects {@code *Test,TeamSettingsPostgresIT} and supplies
 * TEAM_SETTINGS_TEST_JDBC_URL for its disposable database; Flyway runs V001–V016. Default Surefire
 * discovery excludes this IT class. These tests require actual PostgreSQL; their presence does not
 * establish that the native gate has run or passed.
 */
@EnabledIfEnvironmentVariable(named = "TEAM_SETTINGS_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class TeamSettingsPostgresIT extends TeamSettingsIntegrationTest {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("TEAM_SETTINGS_TEST_JDBC_URL"));
    properties.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("TEAM_SETTINGS_TEST_DB_USER", "agentos"));
    properties.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("TEAM_SETTINGS_TEST_DB_PASSWORD", "agentos"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void concurrentSameKeyHasOneEffectAndOriginalReceipt() throws Exception {
    UUID key = UUID.randomUUID();
    Command command = command(context(), "Once", null, List.of());
    List<Result> results =
        race(
            () -> service.update(org, team, human, key, command),
            () -> service.update(org, team, human, key, command));
    assertThat(results.get(0).receipt()).isEqualTo(results.get(1).receipt());
    assertThat(results).extracting(Result::replay).containsExactlyInAnyOrder(true, false);
    assertThat(count("team_settings_events")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @Test
  void differentCommandsOnSameSnapshotHaveExactlyOneWinner() throws Exception {
    var source = context();
    var results =
        race(
            () -> attempt(team, command(source, "One", null, List.of())),
            () -> attempt(team, command(source, "Two", null, List.of())));
    assertThat(results).containsExactlyInAnyOrder("SUCCESS", "TEAM_SETTINGS_CONFLICT");
    assertThat(count("team_settings_events")).isEqualTo(1);
  }

  @Test
  void competingTeamsCannotStealUnassignedAgent() throws Exception {
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Other','DRAFT',current_timestamp,current_timestamp)",
        other,
        org);
    UUID candidate = agent("MID", "DRAFT", false);
    var first = command(context(), "First", null, List.of(candidate));
    var second = command(service.context(org, other, human), "Second", null, List.of(candidate));
    assertThat(race(() -> attempt(team, first), () -> attempt(other, second)))
        .containsExactlyInAnyOrder("SUCCESS", "TEAM_SETTINGS_BLOCKED");
    assertThat(count("team_settings_events")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select team_id from agents where id=?", UUID.class, candidate))
        .isIn(team, other);
  }

  @Test
  void authorityIsRecheckedAfterOrganizationWait() throws Exception {
    Command command = command(context(), "Must not persist", null, List.of());
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      lockOrganization(holder);
      Future<Result> waiting =
          executor.submit(() -> service.update(org, team, human, UUID.randomUUID(), command));
      awaitDatabaseWait();
      try (var statement =
          holder.prepareStatement(
              "update organization_memberships set role='MEMBER' where organization_id=? and"
                  + " account_id=?")) {
        statement.setObject(1, org);
        statement.setObject(2, actor);
        statement.executeUpdate();
      }
      holder.commit();
      assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(TeamSettingsFailure.class)
          .rootCause()
          .hasMessage("FORBIDDEN");
      assertThat(count("team_settings_events")).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void finalFingerprintAndClockObserveChangesCommittedDuringWait() throws Exception {
    UUID member = agent("MID", "DRAFT", true);
    Command stale = command(context(), "Must conflict", null, List.of(member));
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      lockOrganization(holder);
      Future<Result> waiting =
          executor.submit(() -> service.update(org, team, human, UUID.randomUUID(), stale));
      awaitDatabaseWait();
      try (var statement =
          holder.prepareStatement(
              "update agents set configuration_revision=configuration_revision+1 where id=?")) {
        statement.setObject(1, member);
        statement.executeUpdate();
      }
      holder.commit();
      assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
          .rootCause()
          .hasMessage("TEAM_SETTINGS_CONFLICT");
      lockOrganization(holder);
      Command fresh = command(context(), "After wait", null, List.of(member));
      Future<Result> next =
          executor.submit(() -> service.update(org, team, human, UUID.randomUUID(), fresh));
      awaitDatabaseWait();
      OffsetDateTime beforeRelease;
      try (var statement = holder.createStatement();
          var rs = statement.executeQuery("select clock_timestamp()")) {
        rs.next();
        beforeRelease = rs.getObject(1, OffsetDateTime.class);
      }
      holder.commit();
      assertThat(
              OffsetDateTime.parse(next.get(10, TimeUnit.SECONDS).receipt().recordedAt())
                  .toInstant())
          .isAfterOrEqualTo(beforeRelease.toInstant());
    } finally {
      executor.shutdownNow();
    }
  }

  private void lockOrganization(Connection holder) throws Exception {
    try (var statement =
        holder.prepareStatement("select id from organizations where id=? for update")) {
      statement.setObject(1, org);
      statement.executeQuery().close();
    }
  }

  private void awaitDatabaseWait() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (jdbc.queryForObject(
              "select count(*) from pg_stat_activity where datname=current_database() and"
                  + " pid<>pg_backend_pid() and wait_event_type='Lock' and query like 'select id"
                  + " from organizations%'",
              Long.class)
          > 0) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Writer did not reach a native PostgreSQL lock wait");
  }

  private String attempt(UUID target, Command command) {
    try {
      service.update(org, target, human, UUID.randomUUID(), command);
      return "SUCCESS";
    } catch (TeamSettingsFailure failure) {
      return failure.code();
    }
  }

  private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Future<T> one =
          executor.submit(
              () -> {
                start.await();
                return first.call();
              });
      Future<T> two =
          executor.submit(
              () -> {
                start.await();
                return second.call();
              });
      start.countDown();
      return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }
}

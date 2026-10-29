package com.agentos.taskediting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.taskediting.TaskDraftEditDtos.Command;
import com.agentos.taskediting.TaskDraftEditDtos.Result;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Native PostgreSQL suite, including inherited persistence/rollback/HTTP tests. The Quality
 * PostgreSQL gate explicitly selects {@code *Test,TaskDraftEditPostgresIT} and supplies
 * TASK_EDIT_TEST_JDBC_URL for its disposable database; Flyway runs V001–V017. Default Surefire
 * discovery excludes this IT class. These tests require actual PostgreSQL; their presence does not
 * establish that the native gate has run or passed.
 */
class TaskDraftEditPostgresIT extends TaskDraftEditIntegrationTest {
  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null
        || value.isBlank()
        || ("TASK_EDIT_TEST_JDBC_URL".equals(name) && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException(
          "Explicit native PostgreSQL test configuration required: " + name);
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> requiredEnvironment("TASK_EDIT_TEST_JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> requiredEnvironment("TASK_EDIT_TEST_DB_USER"));
    properties.add(
        "spring.datasource.password", () -> requiredEnvironment("TASK_EDIT_TEST_DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void concurrentSameKeyHasOneEffectAndOriginalReceipt() throws Exception {
    UUID key = UUID.randomUUID();
    Command command = command(context(), "Once");
    List<Result> results =
        race(
            () -> service.update(org, team, mission, task, human, key, command),
            () -> service.update(org, team, mission, task, human, key, command));
    assertThat(results.get(0).receipt()).isEqualTo(results.get(1).receipt());
    assertThat(results).extracting(Result::replay).containsExactlyInAnyOrder(true, false);
    assertThat(count("task_edit_events")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @Test
  void differentCommandsOnSameSnapshotHaveExactlyOneWinner() throws Exception {
    var source = context();
    var results =
        race(
            () -> attempt(team, command(source, "One")),
            () -> attempt(team, command(source, "Two")));
    assertThat(results).containsExactlyInAnyOrder("SUCCESS", "TASK_EDIT_CONFLICT");
    assertThat(count("task_edit_events")).isEqualTo(1);
  }

  @Test
  void authorityIsRecheckedAfterOrganizationWait() throws Exception {
    Command command = command(context(), "Must not persist");
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      lockOrganization(holder);
      Future<Result> waiting =
          executor.submit(
              () -> service.update(org, team, mission, task, human, UUID.randomUUID(), command));
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
          .hasCauseInstanceOf(TaskDraftEditFailure.class)
          .rootCause()
          .hasMessage("FORBIDDEN");
      assertThat(count("task_edit_events")).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void finalFingerprintAndClockObserveChangesCommittedDuringWait() throws Exception {
    Command stale = command(context(), "Must conflict");
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      lockOrganization(holder);
      Future<Result> waiting =
          executor.submit(
              () -> service.update(org, team, mission, task, human, UUID.randomUUID(), stale));
      awaitDatabaseWait();
      try (var statement =
          holder.prepareStatement(
              "update missions set task_intake_version=task_intake_version+1 where id=?")) {
        statement.setObject(1, mission);
        statement.executeUpdate();
      }
      holder.commit();
      assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
          .rootCause()
          .hasMessage("TASK_EDIT_CONFLICT");
      lockOrganization(holder);
      Command fresh = command(context(), "After wait");
      Future<Result> next =
          executor.submit(
              () -> service.update(org, team, mission, task, human, UUID.randomUUID(), fresh));
      awaitDatabaseWait();
      OffsetDateTime beforeRelease;
      try (var statement = holder.createStatement();
          var rs = statement.executeQuery("select clock_timestamp()")) {
        rs.next();
        beforeRelease = rs.getObject(1, OffsetDateTime.class);
      }
      holder.commit();
      assertThat(
              OffsetDateTime.parse(next.get(10, TimeUnit.SECONDS).receipt().committedAt())
                  .toInstant())
          .isAfterOrEqualTo(beforeRelease.toInstant());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void migrationRetainsPreexistingTaskRowsAndAddsScopedConstraints() throws Exception {
    String schema = "task_edit_migration_" + UUID.randomUUID().toString().replace("-", "");
    // A migration schema must never leak into the application connection pool.
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TASK_EDIT_TEST_JDBC_URL"),
            System.getenv().getOrDefault("TASK_EDIT_TEST_DB_USER", "agentos"),
            System.getenv().getOrDefault("TASK_EDIT_TEST_DB_PASSWORD", "agentos"));
    try {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .target("16")
          .load()
          .migrate();
      try (Connection connection = source.getConnection()) {
        connection.setSchema(schema);
        var isolated =
            new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true));
        isolated.update(
            "insert into organizations(id,name,slug,status,created_at,updated_at) values"
                + " (?,'Retained',?,'ACTIVE',current_timestamp,current_timestamp)",
            org,
            org.toString());
        isolated.update(
            "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
                + " (?,?,'Retained','DRAFT',current_timestamp,current_timestamp)",
            team,
            org);
        isolated.update(
            "insert into"
                + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
                + " values"
                + " (?,?,?,'Retained','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
            mission,
            org,
            team);
        isolated.update(
            "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
                + " (?,?,?,?,'Retained legacy','DRAFT')",
            task,
            org,
            team,
            mission);
        var before = isolated.queryForMap("select * from tasks where id=?", task);
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("17")
            .load()
            .migrate();
        var after = isolated.queryForMap("select * from tasks where id=?", task);
        assertThat(after.remove("task_edit_revision")).isEqualTo(0);
        assertThat(after).isEqualTo(before);
        assertThatThrownBy(
                () -> isolated.update("update tasks set task_edit_revision=-1 where id=?", task))
            .isInstanceOf(DataAccessException.class);
        isolated.update(
            "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Migration"
                + " actor','unused')",
            actor,
            actor + "@example.test");
        assertThatThrownBy(
                () ->
                    isolated.update(
                        "insert into"
                            + " task_edit_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)"
                            + " values (?,?,?,?,?,1,?,current_timestamp,'{}','{}')",
                        UUID.randomUUID(),
                        org,
                        team,
                        mission,
                        UUID.randomUUID(),
                        actor))
            .isInstanceOf(DataAccessException.class);
        assertThat(isolated.queryForObject("select count(*) from task_edit_events", Long.class))
            .isZero();
        assertThat(isolated.queryForObject("select count(*) from task_edit_receipts", Long.class))
            .isZero();
      }
    } finally {
      jdbc.execute("drop schema if exists " + schema + " cascade");
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
      service.update(org, target, mission, task, human, UUID.randomUUID(), command);
      return "SUCCESS";
    } catch (TaskDraftEditFailure failure) {
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

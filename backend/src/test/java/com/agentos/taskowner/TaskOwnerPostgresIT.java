package com.agentos.taskowner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.taskowner.TaskOwnerDtos.Command;
import com.agentos.taskowner.TaskOwnerDtos.Result;
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
 * PostgreSQL gate explicitly selects {@code *Test,TaskOwnerPostgresIT} and supplies
 * TASK_OWNER_TEST_JDBC_URL for its disposable database; Flyway runs through V020. Default Surefire
 * discovery excludes this IT class. These tests require actual PostgreSQL; their presence does not
 * establish that the native gate has run or passed.
 */
class TaskOwnerPostgresIT extends TaskOwnerIntegrationTest {
  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      value = System.getenv(name.replace("TASK_OWNER_TEST_", "TASK_EDIT_TEST_"));
    if (value == null
        || value.isBlank()
        || (name.endsWith("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException(
          "Explicit native PostgreSQL test configuration required: " + name);
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> requiredEnvironment("TASK_OWNER_TEST_JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> requiredEnvironment("TASK_OWNER_TEST_DB_USER"));
    properties.add(
        "spring.datasource.password", () -> requiredEnvironment("TASK_OWNER_TEST_DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void concurrentSameKeyHasOneEffectAndOriginalReceipt() throws Exception {
    UUID key = UUID.randomUUID();
    Command command = command(context(), agent("Once"));
    List<Result> results =
        race(
            () -> service.update(org, team, mission, task, human, key, command),
            () -> service.update(org, team, mission, task, human, key, command));
    assertThat(results.get(0).receipt()).isEqualTo(results.get(1).receipt());
    assertThat(results).extracting(Result::replay).containsExactlyInAnyOrder(true, false);
    assertThat(count("task_owner_events")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @Test
  void differentCommandsOnSameSnapshotHaveExactlyOneWinner() throws Exception {
    var source = context();
    var one = command(source, agent("One"));
    var two = command(source, agent("Two"));
    var results = race(() -> attempt(team, one), () -> attempt(team, two));
    assertThat(results).containsExactlyInAnyOrder("SUCCESS", "TASK_OWNER_CONFLICT");
    assertThat(count("task_owner_events")).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ROLE", "DISABLED", "CREDENTIAL"})
  void authorityIsRecheckedAfterOrganizationWait(String revocation) throws Exception {
    Command command = command(context(), agent("Must not persist"));
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      lockOrganization(holder);
      Future<Result> waiting =
          executor.submit(
              () -> service.update(org, team, mission, task, human, UUID.randomUUID(), command));
      awaitDatabaseWait();
      String sql =
          switch (revocation) {
            case "ROLE" ->
                "update organization_memberships set role='MEMBER' where organization_id=? and account_id=?";
            case "DISABLED" -> "update human_accounts set enabled=false where id=?";
            default ->
                "update human_accounts set credential_version=credential_version+1 where id=?";
          };
      try (var statement = holder.prepareStatement(sql)) {
        statement.setObject(1, revocation.equals("ROLE") ? org : actor);
        if (revocation.equals("ROLE")) statement.setObject(2, actor);
        statement.executeUpdate();
      }
      holder.commit();
      assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(TaskOwnerFailure.class)
          .rootCause()
          .hasMessage("FORBIDDEN");
      assertThat(count("task_owner_events")).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void finalFingerprintAndClockObserveChangesCommittedDuringWait() throws Exception {
    Command stale = command(context(), agent("Must conflict"));
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
          .hasMessage("TASK_OWNER_CONFLICT");
      Command fresh = command(context(), agent("After wait"));
      lockOrganization(holder);
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
    String schema = "task_owner_migration_" + UUID.randomUUID().toString().replace("-", "");
    // A migration schema must never leak into the application connection pool.
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            requiredEnvironment("TASK_OWNER_TEST_JDBC_URL"),
            requiredEnvironment("TASK_OWNER_TEST_DB_USER"),
            requiredEnvironment("TASK_OWNER_TEST_DB_PASSWORD"));
    try {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .target("19")
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
        UUID owner = UUID.randomUUID(), oldEvent = UUID.randomUUID(), oldKey = UUID.randomUUID();
        isolated.update(
            "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Migration actor','unused')",
            actor,
            actor + "@example.test");
        isolated.update(
            "insert into agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) values (?,?,?,'Retained owner','Role','MID','DRAFT','SUPERVISED',current_timestamp,current_timestamp)",
            owner,
            org,
            team);
        isolated.update(
            "update tasks set owner_agent_id=?,task_edit_revision=7,description='Old description',acceptance_criteria='Old criteria',priority='HIGH' where id=?",
            owner,
            task);
        isolated.update(
            "insert into task_edit_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json) values (?,?,?,?,?,7,?,current_timestamp,'{}','{}')",
            oldEvent,
            org,
            team,
            mission,
            task,
            actor);
        isolated.update(
            "insert into task_edit_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response) values (?,?,?,?,?,?,?,?,?)",
            org,
            team,
            mission,
            task,
            actor,
            oldKey,
            oldEvent,
            "a".repeat(64),
            "retained edit receipt");
        isolated.update(
            "insert into task_create_receipts values (?,?,?,?,?)",
            org,
            actor,
            oldKey,
            "b".repeat(64),
            "retained creation receipt");
        var oldReceipts =
            isolated.queryForList("select * from task_edit_receipts where organization_id=?", org);
        var oldCreates =
            isolated.queryForList(
                "select * from task_create_receipts where organization_id=?", org);
        var oldEvents =
            isolated.queryForList("select * from task_edit_events where organization_id=?", org);
        var oldOwner = isolated.queryForMap("select * from agents where id=?", owner);
        var before = isolated.queryForMap("select * from tasks where id=?", task);
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("20")
            .load()
            .migrate();
        var after = isolated.queryForMap("select * from tasks where id=?", task);
        assertThat(after.remove("task_owner_revision")).isEqualTo(0);
        assertThat(after).isEqualTo(before);
        assertThatThrownBy(
                () -> isolated.update("update tasks set task_owner_revision=-1 where id=?", task))
            .isInstanceOf(DataAccessException.class);
        assertThat(
                isolated.queryForList(
                    "select * from task_edit_receipts where organization_id=?", org))
            .isEqualTo(oldReceipts);
        assertThat(
                isolated.queryForList(
                    "select * from task_create_receipts where organization_id=?", org))
            .isEqualTo(oldCreates);
        assertThat(
                isolated.queryForList(
                    "select * from task_edit_events where organization_id=?", org))
            .isEqualTo(oldEvents);
        assertThat(isolated.queryForMap("select * from agents where id=?", owner))
            .isEqualTo(oldOwner);
        assertThatThrownBy(
                () ->
                    isolated.update(
                        "insert into"
                            + " task_owner_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)"
                            + " values (?,?,?,?,?,1,?,current_timestamp,'{}','{}')",
                        UUID.randomUUID(),
                        org,
                        team,
                        mission,
                        UUID.randomUUID(),
                        actor))
            .isInstanceOf(DataAccessException.class);
        assertThat(
                isolated.queryForObject(
                    "select count(*) from task_owner_events where organization_id=?",
                    Long.class,
                    org))
            .isZero();
        assertThat(
                isolated.queryForObject(
                    "select count(*) from task_owner_receipts where organization_id=?",
                    Long.class,
                    org))
            .isZero();
        UUID event = UUID.randomUUID();
        String eventSql =
            "insert into task_owner_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json) values (?,?,?,?,?,?,?,current_timestamp,'{}','{}')";
        assertThatThrownBy(
                () ->
                    isolated.update(
                        eventSql, UUID.randomUUID(), org, team, mission, task, 0, actor))
            .isInstanceOf(DataAccessException.class);
        isolated.update(eventSql, event, org, team, mission, task, 1, actor);
        assertThatThrownBy(
                () ->
                    isolated.update(
                        eventSql, UUID.randomUUID(), org, team, mission, task, 1, actor))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                () ->
                    isolated.update(
                        eventSql,
                        UUID.randomUUID(),
                        org,
                        UUID.randomUUID(),
                        mission,
                        task,
                        2,
                        actor))
            .isInstanceOf(DataAccessException.class);
        String receiptSql =
            "insert into task_owner_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response,created_at) values (?,?,?,?,?,?,?,?,?,current_timestamp)";
        assertThatThrownBy(
                () ->
                    isolated.update(
                        receiptSql,
                        org,
                        team,
                        mission,
                        task,
                        actor,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "c".repeat(64),
                        "{}"))
            .isInstanceOf(DataAccessException.class);
        isolated.update(
            receiptSql, org, team, mission, task, actor, oldKey, event, "c".repeat(64), "{}");
        assertThatThrownBy(
                () ->
                    isolated.update(
                        receiptSql,
                        org,
                        team,
                        mission,
                        task,
                        actor,
                        oldKey,
                        event,
                        "c".repeat(64),
                        "{}"))
            .isInstanceOf(DataAccessException.class);
        UUID otherActor = UUID.randomUUID();
        isolated.update(
            "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Other','unused')",
            otherActor,
            otherActor + "@example.test");
        assertThatThrownBy(
                () ->
                    isolated.update(
                        receiptSql,
                        org,
                        team,
                        mission,
                        task,
                        otherActor,
                        UUID.randomUUID(),
                        event,
                        "c".repeat(64),
                        "{}"))
            .isInstanceOf(DataAccessException.class);
      }
    } finally {
      jdbc.execute("drop schema if exists " + schema + " cascade");
    }
  }

  private int holderPid;

  private void lockOrganization(Connection holder) throws Exception {
    try (var st = holder.createStatement();
        var rs = st.executeQuery("select pg_backend_pid()")) {
      rs.next();
      holderPid = rs.getInt(1);
    }
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
                  + " pid<>pg_backend_pid() and wait_event_type='Lock' and ? = any(pg_blocking_pids(pid))",
              Long.class,
              holderPid)
          > 0) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Writer did not reach a native PostgreSQL lock wait");
  }

  private String attempt(UUID target, Command command) {
    try {
      service.update(org, target, mission, task, human, UUID.randomUUID(), command);
      return "SUCCESS";
    } catch (TaskOwnerFailure failure) {
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

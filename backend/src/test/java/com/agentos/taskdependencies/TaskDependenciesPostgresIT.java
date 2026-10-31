package com.agentos.taskdependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Native-only tests. Inherits fixture helpers, never the H2 test methods. */
@SpringBootTest
@org.springframework.context.annotation.Import(DependencyConnectionProbe.Config.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskDependenciesPostgresIT extends DependencyHttpFixture {
  static String environment(String suffix) {
    String value = System.getenv("TASK_DEPENDENCIES_TEST_" + suffix);
    if (value == null || value.isBlank()) value = System.getenv("TASK_EDIT_TEST_" + suffix);
    if (value == null
        || value.isBlank()
        || (suffix.equals("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException(
          "Explicit native PostgreSQL configuration required: TASK_DEPENDENCIES_TEST_" + suffix);
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

  int lock(Connection holder, String table, UUID id) throws Exception {
    holder.setAutoCommit(false);
    int pid;
    try (var statement = holder.createStatement();
        var rs = statement.executeQuery("select pg_backend_pid()")) {
      rs.next();
      pid = rs.getInt(1);
    }
    try (var statement =
        holder.prepareStatement("select id from " + table + " where id=? for update")) {
      statement.setObject(1, id);
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
                  + " ?=any(pg_blocking_pids(pid)) union select a.pid from pg_stat_activity a"
                  + " join blocked b on b.pid=any(pg_blocking_pids(a.pid)) where"
                  + " a.datname=current_database() and a.wait_event_type='Lock')"
                  + " select count(distinct pid) from blocked",
              Long.class,
              holderPid);
      if (waiting >= writers) return;
      Thread.sleep(15);
    }
    throw new AssertionError(
        "Expected " + writers + " observed PostgreSQL lock waiters for holder " + holderPid);
  }

  @ParameterizedTest
  @ValueSource(strings = {"organizations", "teams", "tasks"})
  void freshSourceReadAndCommitClockFollowObservedOrgTeamTaskWait(String table) throws Exception {
    UUID target = node("Target", "DRAFT");
    var stale = command(task, target);
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid =
          lock(
              holder,
              table,
              table.equals("organizations") ? org : table.equals("teams") ? team : task);
      Future<MvcResult> waiting = executor.submit(() -> send(task, UUID.randomUUID(), stale));
      observedWait(pid, 1);
      try (var statement =
          holder.prepareStatement("update tasks set title='Changed while waiting' where id=?")) {
        statement.setObject(1, task);
        statement.executeUpdate();
      }
      holder.commit();
      error(waiting.get(15, TimeUnit.SECONDS), 409, "TASK_DEPENDENCIES_CONFLICT");
      assertThat(count("task_dependency_events")).isZero();
      var fresh = command(task, target);
      pid =
          lock(
              holder,
              table,
              table.equals("organizations") ? org : table.equals("teams") ? team : task);
      Future<MvcResult> next = executor.submit(() -> send(task, UUID.randomUUID(), fresh));
      observedWait(pid, 1);
      java.time.Instant release;
      try (var statement = holder.createStatement();
          var rs = statement.executeQuery("select clock_timestamp()")) {
        rs.next();
        release = rs.getObject(1, java.time.OffsetDateTime.class).toInstant();
      }
      holder.commit();
      var receipt = data(next.get(15, TimeUnit.SECONDS), 201);
      assertThat(java.time.Instant.parse(receipt.path("committedAt").asText()))
          .isAfterOrEqualTo(release);
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE", "MEMBERSHIP", "DISABLED", "CREDENTIAL"})
  void authorizationIsFreshAfterObservedOrganizationWait(String revocation) throws Exception {
    UUID target = node("Target", "DRAFT");
    var body = command(task, target);
    var before = state();
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lock(holder, "organizations", org);
      Future<MvcResult> waiting = executor.submit(() -> send(task, UUID.randomUUID(), body));
      observedWait(pid, 1);
      String sql =
          switch (revocation) {
            case "ROLE" ->
                "update organization_memberships set role='MEMBER' where account_id=? and"
                    + " organization_id=?";
            case "MEMBERSHIP" ->
                "delete from organization_memberships where account_id=? and organization_id=?";
            case "DISABLED" -> "update human_accounts set enabled=false where id=?";
            default ->
                "update human_accounts set credential_version=credential_version+1 where id=?";
          };
      try (var statement = holder.prepareStatement(sql)) {
        statement.setObject(1, actor);
        if (revocation.equals("ROLE") || revocation.equals("MEMBERSHIP"))
          statement.setObject(2, org);
        statement.executeUpdate();
      }
      holder.commit();
      error(waiting.get(15, TimeUnit.SECONDS), 403, "FORBIDDEN");
      assertThat(state()).isEqualTo(before);
    } finally {
      executor.shutdownNow();
    }
  }

  List<MvcResult> serializedRace(List<Callable<MvcResult>> commands) throws Exception {
    var executor = Executors.newFixedThreadPool(commands.size());
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lock(holder, "organizations", org);
      List<Future<MvcResult>> pending = new ArrayList<>();
      for (var command : commands) pending.add(executor.submit(command));
      observedWait(pid, commands.size());
      holder.commit();
      List<MvcResult> results = new ArrayList<>();
      for (var result : pending) results.add(result.get(20, TimeUnit.SECONDS));
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void sameKeyHasExactlyOneEffectAndIdenticalReceipt() throws Exception {
    UUID target = node("Target", "DRAFT"), key = UUID.randomUUID();
    var body = command(task, target);
    var results = serializedRace(List.of(() -> send(task, key, body), () -> send(task, key, body)));
    assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
        .containsExactlyInAnyOrder(201, 200);
    assertThat(data(results.get(0), results.get(0).getResponse().getStatus()))
        .isEqualTo(data(results.get(1), results.get(1).getResponse().getStatus()));
    assertOneEffect();
  }

  @Test
  void differentKeysOnSameSnapshotHaveOneWinner() throws Exception {
    UUID a = node("A", "DRAFT"), b = node("B", "DRAFT");
    var one = command(task, a);
    var two = command(task, b);
    var results =
        serializedRace(
            List.of(
                () -> send(task, UUID.randomUUID(), one),
                () -> send(task, UUID.randomUUID(), two)));
    assertRaceOneWinner(results);
    assertOneEffect();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "3"})
  void reciprocalAndThreeNodeCycleRacesCannotCommitCycle(String size) throws Exception {
    int n = Integer.parseInt(size);
    List<UUID> nodes = new ArrayList<>();
    nodes.add(task);
    for (int i = 1; i < n; i++) nodes.add(node("Race" + i, "DRAFT"));
    List<Callable<MvcResult>> commands = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      UUID source = nodes.get(i), target = nodes.get((i + 1) % n);
      var body = command(source, target);
      commands.add(() -> send(source, UUID.randomUUID(), body));
    }
    assertRaceOneWinner(serializedRace(commands));
    assertOneEffect();
    assertThat(context().path("canEdit").asBoolean()).isTrue();
  }

  void assertRaceOneWinner(List<MvcResult> results) throws Exception {
    assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count())
        .isEqualTo(1);
    for (var result : results)
      if (result.getResponse().getStatus() != 201) error(result, 409, "TASK_DEPENDENCIES_CONFLICT");
  }

  void assertOneEffect() {
    assertThat(count("task_dependencies")).isEqualTo(1);
    assertThat(count("task_dependency_events")).isEqualTo(1);
    assertThat(count("task_dependency_receipts")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tasks",
        "task_dependencies",
        "task_dependency_events",
        "task_dependency_receipts",
        "audit_events"
      })
  void everyPersistenceFailureRollsBackAllEffectsAndKeyCanBeReused(String table) throws Exception {
    UUID target = node("Target", "DRAFT"), key = UUID.randomUUID();
    var body = command(task, target);
    var before = state();
    String name = "dep_qc_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "create function "
            + name
            + "() returns trigger language plpgsql as $$ begin if NEW.organization_id='"
            + org
            + "'::uuid then raise exception 'dependency QC injected failure'; end if; return NEW;"
            + " end $$");
    try {
      jdbc.execute(
          "create trigger "
              + name
              + " before "
              + (table.equals("tasks") ? "update" : "insert")
              + " on "
              + table
              + " for each row execute function "
              + name
              + "()");
      error(send(task, key, body), 500, "TASK_DEPENDENCIES_UNAVAILABLE");
      assertThat(state()).isEqualTo(before);
      assertThat(read(root(task) + "/receipts/" + key).path("receipt").isNull()).isTrue();
    } finally {
      jdbc.execute("drop trigger if exists " + name + " on " + table);
      jdbc.execute("drop function if exists " + name + "()");
    }
    data(send(task, key, body), 201);
    assertOneEffect();
  }

  ObjectNode editCommand(String title) throws Exception {
    var context = read(root(task).replace("draft-dependencies", "draft-settings"));
    var body = json.createObjectNode();
    body.set("expectedRevision", context.get("revision"));
    body.set("expectedFingerprint", context.get("fingerprint"));
    body.put("title", title);
    body.put("description", "");
    body.put("acceptanceCriteria", "");
    body.put("priority", "NORMAL");
    return body;
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREATE", "EDIT"})
  void taskCreateAndEditParticipateInOrganizationSerialization(String writer) throws Exception {
    UUID target = node("Target", "DRAFT");
    var dependencyBody = command(task, target);
    String url;
    ObjectNode body;
    if (writer.equals("EDIT")) {
      url = root(task).replace("draft-dependencies", "draft-settings");
      body = editCommand("Edited concurrently");
    } else {
      url = root(task).substring(0, root(task).indexOf("/" + task));
      body = json.createObjectNode();
      body.put("title", "Concurrent new task");
      body.put("priority", "NORMAL");
      body.put("description", "Concurrent description");
      body.put("acceptanceCriteria", "Concurrent criteria");
      body.putNull("ownerAgentId");
      body.putArray("dependencyIds");
      body.put("expectedIntakeVersion", 0);
    }
    String requestBody = json.writeValueAsString(body);
    String writerUrl = url;
    var executor = Executors.newSingleThreadExecutor();
    try (Connection holder = jdbc.getDataSource().getConnection()) {
      int pid = lock(holder, "organizations", org);
      Future<MvcResult> pending =
          executor.submit(
              () ->
                  mvc.perform(
                          post(writerUrl)
                              .session(session)
                              .with(auth())
                              .with(csrf().asHeader())
                              .contentType("application/json")
                              .header("Idempotency-Key", UUID.randomUUID())
                              .content(requestBody))
                      .andReturn());
      observedWait(pid, 1);
      holder.commit();
      data(pending.get(15, TimeUnit.SECONDS), 201);
      error(send(task, UUID.randomUUID(), dependencyBody), 409, "TASK_DEPENDENCIES_CONFLICT");
      assertThat(count("task_dependency_events")).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void allHttpReadsUseServerRepeatableReadReadOnlyAndWritesReadCommitted() throws Exception {
    UUID target = node("Target", "DRAFT");
    UUID key = UUID.randomUUID();
    for (String path :
        List.of(
            root(task),
            root(task) + "/eligible-dependencies",
            root(task) + "/eligible-dependencies/" + target,
            root(task) + "/receipts/" + key)) {
      DependencyConnectionProbe.READS.get().clear();
      var before = state();
      read(path);
      assertThat(DependencyConnectionProbe.READS.get())
          .isNotEmpty()
          .allSatisfy(
              o -> {
                assertThat(o.isolation()).isEqualTo("repeatable read");
                assertThat(o.readOnly()).isTrue();
              });
      assertThat(state()).isEqualTo(before);
    }
    var command = command(task, target);
    DependencyConnectionProbe.READS.get().clear();
    data(send(task, key, command), 201);
    assertThat(DependencyConnectionProbe.READS.get())
        .isNotEmpty()
        .allSatisfy(
            o -> {
              assertThat(o.isolation()).isEqualTo("read committed");
              assertThat(o.readOnly()).isFalse();
            });
    DependencyConnectionProbe.READS.remove();
  }

  @Test
  void migrationRetainsLegacyRowsEdgesAndReceiptsWithRevisionZero() throws Exception {
    String schema = "dependency_qc_migration_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            environment("JDBC_URL"), environment("DB_USER"), environment("DB_PASSWORD"));
    try {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .target("20")
          .load()
          .migrate();
      try (Connection connection = source.getConnection()) {
        connection.setSchema(schema);
        var isolated = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
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
        UUID target = UUID.randomUUID(), event = UUID.randomUUID(), key = UUID.randomUUID();
        for (UUID id : List.of(task, target))
          isolated.update(
              "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
                  + " (?,?,?,?,'Retained','DRAFT')",
              id,
              org,
              team,
              mission);
        isolated.update(
            "insert into task_dependencies values (?,?,?,?,?)", org, team, mission, task, target);
        isolated.update(
            "insert into human_accounts(id,email,display_name,password_hash) values"
                + " (?,?,'Retained','unused')",
            actor,
            actor + "@example.test");
        isolated.update(
            "insert into"
                + " task_edit_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)"
                + " values (?,?,?,?,?,1,?,current_timestamp,'{}','{}')",
            event,
            org,
            team,
            mission,
            task,
            actor);
        isolated.update(
            "insert into"
                + " task_edit_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response)"
                + " values (?,?,?,?,?,?,?,?,?)",
            org,
            team,
            mission,
            task,
            actor,
            key,
            event,
            "a".repeat(64),
            "legacy receipt bytes");
        var rows = isolated.queryForList("select * from tasks order by id");
        var edges = isolated.queryForList("select * from task_dependencies");
        var receipts = isolated.queryForList("select * from task_edit_receipts");
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("21")
            .load()
            .migrate();
        var migrated = isolated.queryForList("select * from tasks order by id");
        for (var row : migrated) assertThat(row.remove("dependency_revision")).isEqualTo(0);
        assertThat(migrated).isEqualTo(rows);
        assertThat(isolated.queryForList("select * from task_dependencies")).isEqualTo(edges);
        assertThat(isolated.queryForList("select * from task_edit_receipts")).isEqualTo(receipts);
        assertThat(
                isolated.queryForObject("select count(*) from task_dependency_events", Long.class))
            .isZero();
        assertThat(
                isolated.queryForObject(
                    "select count(*) from task_dependency_receipts", Long.class))
            .isZero();
      }
    } finally {
      jdbc.execute("drop schema if exists " + schema + " cascade");
    }
  }
}

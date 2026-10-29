package com.agentos.missionintake;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Explicit native gate; runs the complete history suite against PostgreSQL without skips. */
class MissionIntakeHistoryPostgresIT extends MissionIntakeHistoryTest {
  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      value = System.getenv(name.replace("MISSION_INTAKE_TEST_", "TASK_EDIT_TEST_"));
    if (value == null
        || value.isBlank()
        || (name.endsWith("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException(
          "Explicit native PostgreSQL configuration required: "
              + name
              + " (or "
              + name.replace("MISSION_INTAKE_TEST_", "TASK_EDIT_TEST_")
              + ")");
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", () -> required("MISSION_INTAKE_TEST_JDBC_URL"));
    p.add("spring.datasource.username", () -> required("MISSION_INTAKE_TEST_DB_USER"));
    p.add("spring.datasource.password", () -> required("MISSION_INTAKE_TEST_DB_PASSWORD"));
    p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void additiveIndexMigrationRetainsAcceptedReceiptAndBusinessRows() throws Exception {
    flips(1);
    String schema = "intake_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            required("MISSION_INTAKE_TEST_JDBC_URL"),
            required("MISSION_INTAKE_TEST_DB_USER"),
            required("MISSION_INTAKE_TEST_DB_PASSWORD"));
    String originalSchema = jdbc.queryForObject("select current_schema()", String.class);
    assertThat(originalSchema).matches("[a-zA-Z_][a-zA-Z0-9_]*");
    try {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .target("18")
          .load()
          .migrate();
      try (Connection connection = source.getConnection()) {
        connection.setSchema(schema);
        var isolated =
            new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true));
        isolated.update(
            "insert into organizations select * from "
                + originalSchema
                + ".organizations where id=?",
            org);
        isolated.update(
            "insert into human_accounts select * from "
                + originalSchema
                + ".human_accounts where id=?",
            actor);
        isolated.update(
            "insert into organization_memberships select * from "
                + originalSchema
                + ".organization_memberships where organization_id=?",
            org);
        isolated.update(
            "insert into teams select * from " + originalSchema + ".teams where organization_id=?",
            org);
        isolated.update(
            "insert into missions select * from "
                + originalSchema
                + ".missions where organization_id=?",
            org);
        isolated.update(
            "insert into tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver,created_at,updated_at,description,acceptance_criteria,priority,owner_agent_id,task_edit_revision) select id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver,created_at,updated_at,description,acceptance_criteria,priority,owner_agent_id,task_edit_revision from " + originalSchema + ".tasks where organization_id=?",
            org);
        isolated.update(
            "insert into mission_intake_receipts select * from "
                + originalSchema
                + ".mission_intake_receipts where organization_id=?",
            org);
        isolated.update(
            "insert into audit_events select * from "
                + originalSchema
                + ".audit_events where organization_id=?",
            org);
        var tables =
            java.util.List.of(
                "organizations",
                "human_accounts",
                "organization_memberships",
                "teams",
                "missions",
                "tasks",
                "mission_intake_receipts",
                "audit_events");
        var before = new java.util.LinkedHashMap<String, Object>();
        for (String table : tables)
          before.put(table, isolated.queryForList("select * from " + table));
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("19")
            .load()
            .migrate();
        for (String table : tables)
          assertThat(isolated.queryForList("select * from " + table)).isEqualTo(before.get(table));
        String index =
            isolated.queryForObject(
                "select indexdef from pg_indexes where schemaname=? and"
                    + " indexname='idx_mission_intake_history'",
                String.class,
                schema);
        assertThat(index)
            .contains("organization_id, team_id, mission_id, created_at DESC, event_id DESC");
        assertThat(
                new MissionIntakeService(isolated, json)
                    .history(org, team, mission, human, 0)
                    .items())
            .hasSize(1);
      }
    } finally {
      jdbc.execute("drop schema if exists " + schema + " cascade");
    }
  }
}

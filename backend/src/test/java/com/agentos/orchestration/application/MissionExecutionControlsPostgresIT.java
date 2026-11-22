package com.agentos.orchestration.application;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the plan graph/security/limits suite against an isolated native PostgreSQL schema. */
@SpringBootTest(
    properties = {
      "agentos.mission-plan-review.enabled=true",
      "agentos.mission-plan-acceptance.enabled=true",
      "agentos.mission-graph-admission.enabled=true",
      "agentos.mission-graph-completion.enabled=true",
      "agentos.mission-completion.enabled=true",
      "agentos.mission-execution-controls.enabled=true"
    })
class MissionExecutionControlsPostgresIT extends MissionExecutionControlsIntegrationTest {
  private static final String SCHEMA =
      "execution_controls_" + UUID.randomUUID().toString().replace("-", "");
  private static JdbcTemplate admin;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    String url = required("TASK_EDIT_TEST_JDBC_URL");
    if (!url.startsWith("jdbc:postgresql:") || url.contains("currentSchema="))
      throw new IllegalStateException("Dedicated native PostgreSQL acceptance database required");
    String user = required("TASK_EDIT_TEST_DB_USER"),
        password = required("TASK_EDIT_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    admin.execute("create schema " + SCHEMA);
    properties.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
    properties.add("spring.datasource.username", () -> user);
    properties.add("spring.datasource.password", () -> password);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.flyway.schemas", () -> SCHEMA);
    properties.add("spring.flyway.default-schema", () -> SCHEMA);
  }

  @AfterAll
  static void removeIsolatedSchema() {
    if (admin != null) admin.execute("drop schema if exists " + SCHEMA + " cascade");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing native PostgreSQL setting: " + name);
    return value;
  }
}

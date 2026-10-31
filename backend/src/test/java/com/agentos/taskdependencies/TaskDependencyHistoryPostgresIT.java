package com.agentos.taskdependencies;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the same HTTP history invariants against explicit native PostgreSQL. */
public class TaskDependencyHistoryPostgresIT extends TaskDependencyHistoryIntegrationTest {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.datasource.url", () -> TaskDependenciesPostgresIT.environment("JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> TaskDependenciesPostgresIT.environment("DB_USER"));
    properties.add(
        "spring.datasource.password", () -> TaskDependenciesPostgresIT.environment("DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }
}

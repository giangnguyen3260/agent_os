package com.agentos.taskowner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only the focused history cases against explicit native PostgreSQL configuration. */
class TaskOwnerHistoryPostgresIT extends TaskOwnerHistoryTest {
  @Autowired PlatformTransactionManager transactions;

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      value = System.getenv(name.replace("TASK_OWNER_HISTORY_TEST_", "TASK_EDIT_TEST_"));
    if (value == null
        || value.isBlank()
        || (name.endsWith("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException(
          "Explicit native PostgreSQL test configuration required: " + name);
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.datasource.url", () -> requiredEnvironment("TASK_OWNER_HISTORY_TEST_JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> requiredEnvironment("TASK_OWNER_HISTORY_TEST_DB_USER"));
    properties.add(
        "spring.datasource.password",
        () -> requiredEnvironment("TASK_OWNER_HISTORY_TEST_DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void oneRepeatableReadSnapshotSurvivesConcurrentAcceptedCommandAndMetadataChanges()
      throws Exception {
    commit(agent("Snapshot owner"));
    var clear = command(context(), null);
    var executor = Executors.newSingleThreadExecutor();
    var read = new TransactionTemplate(transactions);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try {
      read.executeWithoutResult(
          transaction -> {
            var first = history(0);
            assertThat(jdbc.queryForObject("show transaction_isolation", String.class))
                .isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("show transaction_read_only", String.class))
                .isEqualTo("on");
            try {
              executor
                  .submit(
                      () -> {
                        service.update(org, team, mission, task, human, UUID.randomUUID(), clear);
                        jdbc.update(
                            "update human_accounts set display_name='New current name' where id=?",
                            actor);
                        jdbc.update("update teams set name='New team name' where id=?", team);
                      })
                  .get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
              throw new AssertionError(
                  "Concurrent command must commit independently of history read", ex);
            }
            assertThat(history(0)).isEqualTo(first);
          });
      assertThat(history(0).total()).isEqualTo(2);
      assertThat(history(0).teamName()).isEqualTo("New team name");
      assertThat(history(0).items())
          .allSatisfy(item -> assertThat(item.actorName()).isEqualTo("New current name"));
    } finally {
      executor.shutdownNow();
    }
  }
}

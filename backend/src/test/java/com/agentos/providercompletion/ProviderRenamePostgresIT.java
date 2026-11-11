package com.agentos.providercompletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerrename.ProviderRenameCoordinator;
import com.agentos.providertransport.ProviderRenameResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Explicit native selection; uses the existing disposable PostgreSQL fixture environment. */
@SpringBootTest
@ActiveProfiles("test")
class ProviderRenamePostgresIT extends ProviderRenameFixture {
  private static String environment(String suffix) {
    String value = System.getenv("TASK_EDIT_TEST_" + suffix);
    if (value == null
        || value.isBlank()
        || (suffix.equals("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException("Explicit native PostgreSQL configuration required");
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> environment("JDBC_URL"));
    properties.add("spring.datasource.username", () -> environment("DB_USER"));
    properties.add("spring.datasource.password", () -> environment("DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void mixedCreateAndRenameShareExactlyOneThirtiethSlotAcrossInstances() throws Exception {
    for (int index = 0; index < 29; index++) {
      if (index % 2 == 0)
        assertThat(admission.acquireCreate(human, org, UUID.randomUUID(), Provider.OPENAI))
            .isTrue();
      else assertThat(admission.acquireRename(human, org, UUID.randomUUID())).isTrue();
    }
    var before =
        jdbc.queryForMap(
            "select * from provider_write_admission where organization_id=? and actor_id=?",
            org,
            actor);
    var other = new ProviderWriteAdmission(jdbc.getDataSource());
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var create =
          executor.submit(
              () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return quotaAttempt(admission, true);
              });
      var rename =
          executor.submit(
              () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return quotaAttempt(other, false);
              });
      start.countDown();
      assertThat(List.of(create.get(15, TimeUnit.SECONDS), rename.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 429);
      assertThat(quota()).isEqualTo(30);
      var saturated =
          jdbc.queryForMap(
              "select * from provider_write_admission where organization_id=? and actor_id=?",
              org,
              actor);
      assertThat(saturated.get("recent_requests").toString())
          .startsWith(before.get("recent_requests").toString() + ",");
      assertThat(quotaAttempt(admission, true)).isEqualTo(429);
      assertThat(quotaAttempt(other, false)).isEqualTo(429);
      assertThat(
              jdbc.queryForMap(
                  "select * from provider_write_admission where organization_id=? and actor_id=?",
                  org,
                  actor))
          .isEqualTo(saturated);
      assertThat(count("provider_command_receipts")).isEqualTo(1);
      assertThat(count("provider_connection_events")).isEqualTo(1);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void eventAndReceiptInsertFailuresRollbackRenameWhileDurableAdmissionRemains() throws Exception {
    var baseline = configuration();
    var credentials =
        jdbc.queryForList(
            "select * from provider_credential_versions where organization_id=?", org);
    int failures = 0;
    for (String table : new String[] {"provider_connection_events", "provider_command_receipts"}) {
      String constraint = "rename_fixture_" + UUID.randomUUID().toString().replace("-", "");
      String column = table.equals("provider_connection_events") ? "kind" : "operation";
      // Scope a test-only database fault to this disposable organization; no SQL adapter mock.
      jdbc.execute(
          "alter table "
              + table
              + " add constraint "
              + constraint
              + " check (organization_id <> '"
              + org
              + "'::uuid or "
              + column
              + " <> 'RENAME')");
      UUID key = UUID.randomUUID();
      try {
        var result = coordinator.rename(human, org, key, new Rename(id, 1, "Must roll back"));
        assertThat(result.state()).isEqualTo(WriteState.REJECTED);
        assertThat(result.failure().code()).isEqualTo(Code.RECEIPT_UNAVAILABLE);
        assertThat(result.result()).isNull();
        var response = new MockHttpServletResponse();
        var json = new ObjectMapper();
        ProviderRenameResponses.writeOutcome(json, response, result);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var envelope = json.readTree(response.getContentAsByteArray());
        assertThat(envelope.at("/error/code").asText())
            .isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
        assertThat(envelope.at("/error/details")).isEqualTo(json.createObjectNode());
        assertThat(envelope.has("data")).isFalse();
        assertThat(response.getContentAsString())
            .doesNotContain(constraint, "Must roll back", "RECEIPT_UNAVAILABLE");
        assertThat(completion.lookupOwn(human, org, key).state())
            .isEqualTo(ProviderCompletionBoundary.LookupState.ABSENT);
        assertThat(configuration()).isEqualTo(baseline);
        assertThat(
                jdbc.queryForList(
                    "select * from provider_credential_versions where organization_id=?", org))
            .isEqualTo(credentials);
        assertThat(count("provider_connection_events")).isEqualTo(1);
        assertThat(count("provider_command_receipts")).isEqualTo(1);
        assertThat(quota()).isEqualTo(++failures);
      } finally {
        jdbc.execute("alter table " + table + " drop constraint " + constraint);
      }
    }
    assertThat(
            coordinator
                .rename(human, org, UUID.randomUUID(), new Rename(id, 1, "After rollback"))
                .state())
        .isEqualTo(WriteState.COMMITTED);
    assertThat(quota()).isEqualTo(3);
    assertThat(count("provider_connection_events")).isEqualTo(2);
    assertThat(count("provider_command_receipts")).isEqualTo(2);
  }

  @Test
  void disableReceiptFailureRollsBackStateAndEvent() {
    var original = configuration();
    var metadata = metadataCoordinator();
    var command = new RevisionCommand(Operation.DISABLE, id, 1);
    String constraint = "disable_fixture_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table provider_command_receipts add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "'::uuid or operation <> 'DISABLE')");
    UUID key = UUID.randomUUID();
    try {
      var result = metadata.disable(human, org, key, command);
      assertThat(result.state()).isEqualTo(WriteState.REJECTED);
      assertThat(result.failure().code()).isEqualTo(Code.RECEIPT_UNAVAILABLE);
      assertThat(configuration()).isEqualTo(original);
      assertThat(count("provider_connection_events")).isEqualTo(1);
      assertThat(completion.lookupOwn(human, org, key).state())
          .isEqualTo(ProviderCompletionBoundary.LookupState.ABSENT);
      assertThat(quota()).isEqualTo(1);
    } finally {
      jdbc.execute("alter table provider_command_receipts drop constraint " + constraint);
    }
    assertThat(metadata.disable(human, org, key, command).state()).isEqualTo(WriteState.COMMITTED);
  }

  private int quotaAttempt(ProviderWriteAdmission bucket, boolean create) {
    try {
      assertThat(
              create
                  ? bucket.acquireCreate(human, org, UUID.randomUUID(), Provider.OPENAI)
                  : bucket.acquireRename(human, org, UUID.randomUUID()))
          .isTrue();
      return 200;
    } catch (ProviderReceiptFailure failure) {
      assertThat(failure.status()).isEqualTo(429);
      assertThat(failure.code()).isEqualTo("RATE_LIMITED");
      assertThat(failure.retryAfter()).isBetween(1, 60);
      return 429;
    }
  }

  @Test
  void competingExpectedRevisionCommitsOnceAndSameKeyReplaysOneBusinessResult() throws Exception {
    var other =
        new ProviderRenameCoordinator(
            receipts, completion, commands, new ProviderWriteAdmission(jdbc.getDataSource()));
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a =
          executor.submit(
              () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return coordinator.rename(
                    human, org, UUID.randomUUID(), new Rename(id, 1, "Winner A"));
              });
      var b =
          executor.submit(
              () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return other.rename(human, org, UUID.randomUUID(), new Rename(id, 1, "Winner B"));
              });
      start.countDown();
      var outcomes = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      assertThat(outcomes)
          .extracting(WriteOutcome::state)
          .containsExactlyInAnyOrder(WriteState.COMMITTED, WriteState.REJECTED);
      assertThat(
              outcomes.stream()
                  .filter(value -> value.state() == WriteState.REJECTED)
                  .findFirst()
                  .orElseThrow()
                  .failure()
                  .code())
          .isEqualTo(Code.REVISION_CONFLICT);
      assertThat(count("provider_connection_events")).isEqualTo(2);
      assertThat(count("provider_command_receipts")).isEqualTo(2);
      assertThat(quota()).isEqualTo(2);
      UUID key = UUID.randomUUID();
      var command = new Rename(id, 2, "Same key result");
      var sameStart = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                assertThat(sameStart.await(5, TimeUnit.SECONDS)).isTrue();
                return coordinator.rename(human, org, key, command);
              });
      var second =
          executor.submit(
              () -> {
                assertThat(sameStart.await(5, TimeUnit.SECONDS)).isTrue();
                return other.rename(human, org, key, command);
              });
      sameStart.countDown();
      var firstResult = first.get(15, TimeUnit.SECONDS);
      var secondResult = second.get(15, TimeUnit.SECONDS);
      assertThat(firstResult.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(secondResult.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(firstResult.result().receipt()).isEqualTo(secondResult.result().receipt());
      assertThat(List.of(firstResult.result().replayed(), secondResult.result().replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(count("provider_connection_events")).isEqualTo(3);
      assertThat(count("provider_command_receipts")).isEqualTo(3);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}

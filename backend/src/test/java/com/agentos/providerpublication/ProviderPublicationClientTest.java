package com.agentos.providerpublication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providerpublication.ProviderPublicationClient.Reason;
import com.agentos.providerpublication.ProviderPublicationClient.Status;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled JDBC faults establish client behavior, not PostgreSQL guard or observer validity. */
class ProviderPublicationClientTest {
  private static final String PRIVATE = "private-locator-verifier-password-SQL-DETAIL-CONTEXT";
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final ProviderPublicationClient.Scope SCOPE =
      new ProviderPublicationClient.Scope(ID, ID, "OPENAI", 1);

  @Test
  void sixTypedOperationsUseOnlyFixedPreparedStatementsAndIndependentTransactions() {
    Fixture fixture = new Fixture();
    var client = fixture.client();
    List<ProviderPublicationClient.Result<?>> results =
        List.of(
            client.beginHealth(SCOPE),
            client.beginPreparationAssertion(SCOPE),
            client.beginIntegrityVerification(SCOPE),
            client.publishHealth(
                ID, ProviderPublicationClient.HealthOutcome.UNAVAILABLE, true, null),
            client.assertPrepared(ID),
            client.publishIntegrityFailure(ID));
    assertThat(results)
        .allSatisfy(result -> assertThat(result.status()).isEqualTo(Status.REJECTED));
    assertThat(fixture.sql)
        .containsExactly(
            "select * from"
                + " agentos_evidence_private.begin_health(?::uuid,?::uuid,?::text,?::bigint)",
            "select * from"
                + " agentos_evidence_private.begin_preparation_assertion(?::uuid,?::uuid,?::text,?::bigint)",
            "select * from"
                + " agentos_evidence_private.begin_integrity_verification(?::uuid,?::uuid,?::text,?::bigint)",
            "select * from"
                + " agentos_evidence_private.publish_health(?::uuid,?::text,?::boolean,?::timestamptz)",
            "select * from agentos_evidence_private.assert_prepared(?::uuid)",
            "select * from agentos_evidence_private.publish_integrity_failure(?::uuid)");
    assertThat(fixture.connections).isEqualTo(6);
    assertThat(fixture.commits).isEqualTo(6);
    assertThat(fixture.rollbacks).isZero();
    assertThat(fixture.closes).isEqualTo(6);
    assertThat(fixture.healthBinds).containsEntry(3, true).containsKey(4);
    assertThat(fixture.healthBinds.get(4)).isNull();
  }

  @Test
  void captureIsExposedOnlyAfterAcknowledgedCommitAndRedactsItsStringRepresentation() {
    Fixture fixture = new Fixture();
    fixture.acceptCapture();
    fixture.closeFailure = new SQLException(PRIVATE, "08006");
    var result = fixture.client().beginHealth(SCOPE);
    assertThat(result.status()).isEqualTo(Status.ACCEPTED);
    assertThat(fixture.commits).isEqualTo(1);
    assertThat(result.value().attemptId()).isEqualTo(ID);
    assertThat(result.value().binding().externalLocator()).isEqualTo(PRIVATE);
    assertThat(result.toString()).doesNotContain(PRIVATE);
    assertThat(result.value().toString()).doesNotContain(PRIVATE);
    assertThat(result.value().binding().toString()).doesNotContain(PRIVATE);
  }

  @Test
  void lostCommitAcknowledgementDiscardsProvisionalCaptureAndNeverRetriesOrReadsBack() {
    Fixture fixture = new Fixture();
    fixture.acceptCapture();
    fixture.commitFailure = new SQLException(PRIVATE, "08006");
    var result = fixture.client().beginHealth(SCOPE);
    assertThat(result.status()).isEqualTo(Status.UNKNOWN);
    assertThat(result.reason()).isEqualTo(Reason.COMPLETION_UNKNOWN);
    assertThat(result.value()).isNull();
    assertThat(fixture.connections).isEqualTo(1);
    assertThat(fixture.sql).hasSize(1);
    assertThat(fixture.commits).isEqualTo(1);
    assertThat(fixture.rollbacks).isZero();
    assertThat(fixture.closes).isEqualTo(1);
  }

  @Test
  void explicitServerRollbackAtCommitIsFailedButUnclassifiedCommitFailureIsUnknown() {
    for (String state : List.of("40001", "40P01", "XX000")) {
      Fixture fixture = new Fixture();
      fixture.commitFailure = new SQLException(PRIVATE, state);
      var result = fixture.client().assertPrepared(ID);
      assertThat(result.status())
          .isEqualTo(state.startsWith("40") ? Status.FAILED : Status.UNKNOWN);
      assertThat(result.value()).isNull();
      assertThat(fixture.commits).isEqualTo(1);
      assertThat(fixture.rollbacks).isZero();
    }
  }

  @Test
  void statementFailuresRollbackAndUncertainRollbackNeverClaimsKnownFailure() {
    for (boolean rollbackLost : List.of(false, true)) {
      Fixture fixture = new Fixture();
      fixture.queryFailure = new SQLException(PRIVATE, "42501");
      if (rollbackLost) fixture.rollbackFailure = new SQLException(PRIVATE, "08006");
      var result = fixture.client().assertPrepared(ID);
      assertThat(result.status()).isEqualTo(rollbackLost ? Status.UNKNOWN : Status.FAILED);
      assertThat(result.value()).isNull();
      assertThat(fixture.commits).isZero();
      assertThat(fixture.rollbacks).isEqualTo(1);
      assertThat(fixture.sql).hasSize(1);
    }
  }

  @Test
  void malformedResultAndImpossibleStatusCannotBecomeAccepted() {
    for (String status : List.of(PRIVATE, "ALREADY_FAILED", "FAILED")) {
      Fixture fixture = new Fixture();
      fixture.row.put("status", status);
      fixture.row.put("reason", "NONE");
      var result = fixture.client().beginHealth(SCOPE);
      assertThat(result.status()).isEqualTo(Status.FAILED);
      assertThat(result.value()).isNull();
      assertThat(fixture.rollbacks).isEqualTo(1);
      assertThat(fixture.commits).isZero();
    }
    Fixture fixture = new Fixture();
    fixture.row.put("status", "ALREADY_FAILED");
    fixture.row.put("reason", "NONE");
    var result = fixture.client().publishIntegrityFailure(ID);
    assertThat(result.status()).isEqualTo(Status.ALREADY_FAILED);
    assertThat(result.value()).isNull();
    assertThat(fixture.commits).isEqualTo(1);
  }

  @Test
  void existingTransactionIsRejectedWithoutExecutingOrCommittingIt() {
    Fixture fixture = new Fixture();
    fixture.autoCommit = false;
    var result = fixture.client().beginHealth(SCOPE);
    assertThat(result.status()).isEqualTo(Status.FAILED);
    assertThat(fixture.sql).isEmpty();
    assertThat(fixture.commits).isZero();
    assertThat(fixture.rollbacks).isZero();
    assertThat(fixture.closes).isEqualTo(1);
  }

  @Test
  void springAmbientTransactionAndSynchronizationAreRefusedBeforeBorrowing() {
    for (int mode = 0; mode < 3; mode++) {
      boolean actual = mode != 0;
      boolean synchronizedContext = mode != 1;
      Fixture fixture = new Fixture();
      Object key = new Object();
      Object resource = new Object();
      TransactionSynchronizationManager.bindResource(key, resource);
      if (synchronizedContext) TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(actual);
      try {
        var result = fixture.client().beginHealth(SCOPE);
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(fixture.connections).isZero();
        assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(resource);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive())
            .isEqualTo(synchronizedContext);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(actual);
      } finally {
        TransactionSynchronizationManager.unbindResource(key);
        if (synchronizedContext) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
    }
  }

  @Test
  void acceptedCaptureMustMatchRequestedScopeAndCanonicalMetadataAndClockPrecision() {
    Map<String, Object> invalid = new HashMap<>();
    invalid.put("organization_id", UUID.randomUUID());
    invalid.put("provider", "ANTHROPIC");
    invalid.put("external_locator", "private space");
    invalid.put("verifier", "A".repeat(42) + "B=");
    invalid.put("observed_at", OffsetDateTime.parse("2026-11-02T00:00:00.000000001Z"));
    invalid.put("deadline", OffsetDateTime.parse("+10000-01-01T00:00:00Z"));
    for (var entry : invalid.entrySet()) {
      Fixture fixture = new Fixture();
      fixture.acceptCapture();
      fixture.row.put(entry.getKey(), entry.getValue());
      var result = fixture.client().beginHealth(SCOPE);
      assertThat(result.status()).isEqualTo(Status.FAILED);
      assertThat(result.value()).isNull();
      assertThat(fixture.commits).isZero();
      assertThat(fixture.rollbacks).isEqualTo(1);
    }
  }

  @Test
  void publicationCannotReturnAnotherOperationsDescriptorOrCaptureOnlyCapacityReason() {
    Fixture fixture = new Fixture();
    fixture.acceptCapture();
    fixture.row.put("publisher_id", ID);
    fixture.row.put("publisher_epoch", 1L);
    fixture.row.put("publisher_fence", ID);
    fixture.row.put("publication_revision", 1L);
    fixture.row.put("assertion_kind", "HEALTH");
    fixture.row.put("state", "USABLE");
    fixture.row.put("published_at", fixture.row.get("observed_at"));
    fixture.row.put("valid_until", fixture.row.get("deadline"));
    var result = fixture.client().assertPrepared(ID);
    assertThat(result.status()).isEqualTo(Status.FAILED);
    assertThat(result.value()).isNull();
    assertThat(fixture.rollbacks).isEqualTo(1);
    Fixture capacity = new Fixture();
    capacity.row.put("reason", "CAPACITY");
    assertThat(capacity.client().assertPrepared(ID).status()).isEqualTo(Status.FAILED);
    assertThat(capacity.client().beginHealth(SCOPE).reason()).isEqualTo(Reason.CAPACITY);
  }

  @Test
  void dependencyAndCompletionFailuresEmitNoOutputOrNestedDiagnostics() {
    var originalOut = System.out;
    var originalErr = System.err;
    var bytes = new ByteArrayOutputStream();
    try (var captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(captured);
      System.setErr(captured);
      for (boolean atCommit : List.of(false, true)) {
        Fixture fixture = new Fixture();
        SQLException failure = new SQLException(PRIVATE, "08006", new RuntimeException(PRIVATE));
        if (atCommit) fixture.commitFailure = failure;
        else fixture.queryFailure = failure;
        fixture.closeFailure = failure;
        var result = fixture.client().assertPrepared(ID);
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.value()).isNull();
        assertThat(result.toString()).doesNotContain(PRIVATE, "08006");
      }
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    assertThat(bytes.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  @Test
  void privateTypesRejectRootAndNestedSerializationRatherThanSilentlyDroppingFields() {
    Fixture fixture = new Fixture();
    fixture.acceptCapture();
    var result = fixture.client().beginHealth(SCOPE);
    var publication =
        new ProviderPublicationClient.Publication(
            result.value().binding(),
            ID,
            1,
            ID,
            "HEALTH",
            1,
            "USABLE",
            null,
            result.value().observedAt(),
            result.value().observedAt(),
            result.value().deadline());
    var mapper = new ObjectMapper();
    for (Object value :
        List.of(result, result.value(), result.value().binding(), SCOPE, publication)) {
      for (Object envelope :
          List.of(value, List.of(value), Map.of("nested", List.of(value)), new Envelope(value))) {
        assertThatThrownBy(() -> mapper.writeValueAsString(envelope))
            .isInstanceOf(JsonMappingException.class)
            .hasMessageContaining("Private provider publication value")
            .hasMessageNotContaining(PRIVATE);
      }
    }
  }

  private record Envelope(Object value) {}

  private static final class Fixture {
    final List<String> sql = new ArrayList<>();
    final Map<String, Object> row = new HashMap<>();
    final Map<Integer, Object> healthBinds = new HashMap<>();
    int connections;
    int commits;
    int rollbacks;
    int closes;
    boolean autoCommit = true;
    SQLException queryFailure;
    SQLException commitFailure;
    SQLException rollbackFailure;
    SQLException closeFailure;

    Fixture() {
      row.put("status", "REJECTED");
      row.put("reason", "AUTHORITY");
    }

    void acceptCapture() {
      row.put("status", "ACCEPTED");
      row.put("reason", "NONE");
      row.put("attempt_id", ID);
      row.put("organization_id", ID);
      row.put("connection_id", ID);
      row.put("provider", "OPENAI");
      row.put("credential_revision", 1L);
      for (String field :
          List.of("external_locator", "external_key_version", "hmac_key_version", "verifier")) {
        row.put(field, PRIVATE);
      }
      row.put("verifier", "A".repeat(43) + "=");
      row.put("hmac_domain_version", "v2");
      row.put("observed_at", OffsetDateTime.parse("2026-11-02T00:00:00Z"));
      row.put("deadline", OffsetDateTime.parse("2026-11-02T00:01:00Z"));
    }

    ProviderPublicationClient client() {
      DataSource source =
          proxy(
              DataSource.class,
              (ignored, method, args) -> {
                if (!method.getName().equals("getConnection"))
                  throw new UnsupportedOperationException();
                connections++;
                return connection();
              });
      return new ProviderPublicationClient(source);
    }

    Connection connection() {
      return proxy(
          Connection.class,
          (ignored, method, args) -> {
            switch (method.getName()) {
              case "getAutoCommit":
                return autoCommit;
              case "setReadOnly":
                assertThat(args[0]).isEqualTo(false);
                break;
              case "setTransactionIsolation":
                assertThat(args[0]).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                break;
              case "setAutoCommit":
                assertThat(args[0]).isEqualTo(false);
                break;
              case "prepareStatement":
                sql.add((String) args[0]);
                return statement(((String) args[0]).contains(".publish_health("));
              case "commit":
                commits++;
                if (commitFailure != null) throw commitFailure;
                break;
              case "rollback":
                rollbacks++;
                if (rollbackFailure != null) throw rollbackFailure;
                break;
              case "close":
                closes++;
                if (closeFailure != null) throw closeFailure;
                break;
              default:
                throw new UnsupportedOperationException();
            }
            return null;
          });
    }

    PreparedStatement statement(boolean health) {
      return proxy(
          PreparedStatement.class,
          (ignored, method, args) -> {
            if (method.getName().equals("executeQuery")) {
              if (queryFailure != null) throw queryFailure;
              return rows();
            }
            if (method.getName().startsWith("set")) {
              if (health) healthBinds.put((Integer) args[0], args[1]);
              return null;
            }
            if (method.getName().equals("close")) return null;
            throw new UnsupportedOperationException();
          });
    }

    ResultSet rows() {
      int[] count = {0};
      return proxy(
          ResultSet.class,
          (ignored, method, args) -> {
            return switch (method.getName()) {
              case "next" -> ++count[0] == 1;
              case "getString", "getObject" -> row.get(args[0]);
              case "getLong" -> row.getOrDefault(args[0], 0L);
              case "close" -> null;
              default -> throw new UnsupportedOperationException();
            };
          });
    }
  }

  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}

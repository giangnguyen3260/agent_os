package com.agentos.providerpublication;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit worker-only JDBC boundary; not a Spring bean or observer. The six scalar signatures and
 * returned column names match the reviewed SQL API manifest. Never supply a web/operator
 * datasource, shared ambient transaction or logging JDBC proxy.
 */
public final class ProviderPublicationClient {
  public enum Status {
    ACCEPTED,
    ALREADY_FAILED,
    REJECTED,
    FAILED,
    UNKNOWN
  }

  public enum Reason {
    NONE,
    AUTHORITY,
    ATTEMPT,
    BINDING,
    CONFLICT,
    TIME,
    EXHAUSTED,
    CAPACITY,
    INTEGRITY_FAILED,
    DEPENDENCY_UNAVAILABLE,
    COMPLETION_UNKNOWN
  }

  public enum HealthOutcome {
    USABLE,
    UNAVAILABLE
  }

  @JsonSerialize(using = RejectPrivateSerialization.class)
  public record Scope(UUID organizationId, UUID connectionId, String provider, long revision) {
    @Override
    public String toString() {
      return "Scope[redacted]";
    }
  }

  @JsonSerialize(using = RejectPrivateSerialization.class)
  public record Binding(
      Scope scope,
      String externalLocator,
      String externalKeyVersion,
      String hmacKeyVersion,
      String hmacDomainVersion) {
    @Override
    public String toString() {
      return "Binding[redacted]";
    }
  }

  @JsonSerialize(using = RejectPrivateSerialization.class)
  public record Capture(
      UUID attemptId,
      Binding binding,
      String verifier,
      OffsetDateTime observedAt,
      OffsetDateTime deadline) {
    @Override
    public String toString() {
      return "Capture[redacted]";
    }
  }

  @JsonSerialize(using = RejectPrivateSerialization.class)
  public record Publication(
      Binding binding,
      UUID publisherId,
      long publisherEpoch,
      UUID publisherFence,
      String assertionKind,
      long publicationRevision,
      String state,
      OffsetDateTime recordedAt,
      OffsetDateTime observedAt,
      OffsetDateTime publishedAt,
      OffsetDateTime validUntil) {
    @Override
    public String toString() {
      return "Publication[redacted]";
    }
  }

  @JsonSerialize(using = RejectPrivateSerialization.class)
  public record Result<T>(Status status, Reason reason, T value, UUID correlationId) {
    @Override
    public String toString() {
      return "Result[status="
          + status
          + ",reason="
          + reason
          + ",correlationId="
          + correlationId
          + "]";
    }
  }

  /**
   * Rejects root and nested serialization; ignoring a root type alone does not protect containers.
   */
  public static final class RejectPrivateSerialization extends JsonSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      throw JsonMappingException.from(generator, "Private provider publication value");
    }
  }

  private enum Operation {
    BEGIN_HEALTH(
        "select * from agentos_evidence_private.begin_health(?::uuid,?::uuid,?::text,?::bigint)",
        true),
    BEGIN_PREPARATION(
        "select * from"
            + " agentos_evidence_private.begin_preparation_assertion(?::uuid,?::uuid,?::text,?::bigint)",
        true),
    BEGIN_INTEGRITY(
        "select * from"
            + " agentos_evidence_private.begin_integrity_verification(?::uuid,?::uuid,?::text,?::bigint)",
        true),
    HEALTH(
        "select * from"
            + " agentos_evidence_private.publish_health(?::uuid,?::text,?::boolean,?::timestamptz)",
        false),
    PREPARED("select * from agentos_evidence_private.assert_prepared(?::uuid)", false),
    FAILURE("select * from agentos_evidence_private.publish_integrity_failure(?::uuid)", false);

    private final String sql;
    private final boolean capture;

    Operation(String sql, boolean capture) {
      this.sql = sql;
      this.capture = capture;
    }
  }

  private final DataSource workerDataSource;

  public ProviderPublicationClient(DataSource workerDataSource) {
    this.workerDataSource =
        Objects.requireNonNull(workerDataSource, "Explicit worker datasource required");
  }

  public Result<Capture> beginHealth(Scope scope) {
    return execute(Operation.BEGIN_HEALTH, scope, null, null, false, null);
  }

  public Result<Capture> beginPreparationAssertion(Scope scope) {
    return execute(Operation.BEGIN_PREPARATION, scope, null, null, false, null);
  }

  public Result<Capture> beginIntegrityVerification(Scope scope) {
    return execute(Operation.BEGIN_INTEGRITY, scope, null, null, false, null);
  }

  /** Presence is explicit: supplied-null must reject rather than silently extend validity. */
  public Result<Publication> publishHealth(
      UUID attempt, HealthOutcome outcome, boolean expiryPresent, OffsetDateTime componentExpiry) {
    return execute(Operation.HEALTH, null, attempt, outcome, expiryPresent, componentExpiry);
  }

  public Result<Publication> assertPrepared(UUID attempt) {
    return execute(Operation.PREPARED, null, attempt, null, false, null);
  }

  /** Caller must have a separately trusted typed mismatch; generic failures are not mismatches. */
  public Result<Publication> publishIntegrityFailure(UUID attempt) {
    return execute(Operation.FAILURE, null, attempt, null, false, null);
  }

  @SuppressWarnings("unchecked")
  private <T> Result<T> execute(
      Operation operation,
      Scope scope,
      UUID attempt,
      HealthOutcome outcome,
      boolean expiryPresent,
      OffsetDateTime componentExpiry) {
    UUID correlation = UUID.randomUUID();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      return failure(false, correlation);
    }
    Connection connection = null;
    boolean transactionStarted = false;
    boolean commitStarted = false;
    try {
      connection = workerDataSource.getConnection();
      if (!connection.getAutoCommit()) {
        return failure(false, correlation);
      }
      connection.setReadOnly(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      transactionStarted = true;
      Result<T> provisional;
      try (PreparedStatement statement = connection.prepareStatement(operation.sql)) {
        if (operation.capture) {
          if (scope == null) throw new IllegalArgumentException();
          statement.setObject(1, scope.organizationId());
          statement.setObject(2, scope.connectionId());
          statement.setString(3, scope.provider());
          statement.setLong(4, scope.revision());
        } else {
          statement.setObject(1, attempt);
          if (operation == Operation.HEALTH) {
            statement.setString(2, outcome == null ? null : outcome.name());
            statement.setBoolean(3, expiryPresent);
            statement.setObject(4, componentExpiry);
          }
        }
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) throw new IllegalStateException();
          Status status = Status.valueOf(rows.getString("status"));
          Reason reason = Reason.valueOf(rows.getString("reason"));
          if (!validStatus(operation, status, reason)) throw new IllegalStateException();
          Object value = null;
          if (status == Status.ACCEPTED) {
            value = operation.capture ? capture(rows) : publication(rows);
            if (operation.capture) {
              if (!scope.equals(((Capture) value).binding().scope())) {
                throw new IllegalStateException();
              }
            } else {
              Publication published = (Publication) value;
              String expectedKind =
                  switch (operation) {
                    case HEALTH -> "HEALTH";
                    case PREPARED -> "PREPARE";
                    case FAILURE -> "INTEGRITY_FAILURE";
                    default -> throw new IllegalStateException();
                  };
              if (!expectedKind.equals(published.assertionKind())
                  || (operation == Operation.HEALTH
                      && (outcome == null || !outcome.name().equals(published.state())))) {
                throw new IllegalStateException();
              }
            }
          }
          if (rows.next()) throw new IllegalStateException();
          provisional = new Result<>(status, reason, (T) value, correlation);
        }
      }
      // Never expose a capture or accepted descriptor before acknowledgement. Never retry.
      commitStarted = true;
      connection.commit();
      return provisional;
    } catch (SQLException | RuntimeException failure) {
      if (commitStarted) {
        // Only an explicit server transaction rollback establishes rejection at this boundary.
        boolean knownRollback =
            failure instanceof SQLTransactionRollbackException
                || (failure instanceof SQLException sql
                    && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("40"));
        return failure(!knownRollback, correlation);
      }
      boolean connectionLost =
          failure instanceof SQLException sql
              && sql.getSQLState() != null
              && sql.getSQLState().startsWith("08");
      if (transactionStarted) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
          return failure(true, correlation);
        }
      }
      return failure(connectionLost && transactionStarted, correlation);
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
          // Acknowledged commit stays known. Close diagnostics must never escape or be logged.
        }
      }
    }
  }

  private static boolean validStatus(Operation operation, Status status, Reason reason) {
    return switch (status) {
      case ACCEPTED -> reason == Reason.NONE;
      case ALREADY_FAILED -> operation == Operation.FAILURE && reason == Reason.NONE;
      case REJECTED ->
          reason != Reason.NONE
              && reason != Reason.DEPENDENCY_UNAVAILABLE
              && reason != Reason.COMPLETION_UNKNOWN
              && (reason != Reason.CAPACITY || operation.capture);
      default -> false;
    };
  }

  private static <T> Result<T> failure(boolean unknown, UUID correlation) {
    return new Result<>(
        unknown ? Status.UNKNOWN : Status.FAILED,
        unknown ? Reason.COMPLETION_UNKNOWN : Reason.DEPENDENCY_UNAVAILABLE,
        null,
        correlation);
  }

  private static Binding binding(ResultSet rows) throws SQLException {
    Binding result =
        new Binding(
            new Scope(
                rows.getObject("organization_id", UUID.class),
                rows.getObject("connection_id", UUID.class),
                rows.getString("provider"),
                rows.getLong("credential_revision")),
            rows.getString("external_locator"),
            rows.getString("external_key_version"),
            rows.getString("hmac_key_version"),
            rows.getString("hmac_domain_version"));
    Objects.requireNonNull(result.scope().organizationId());
    Objects.requireNonNull(result.scope().connectionId());
    if (result.scope().revision() <= 0
        || !java.util.List.of("OPENAI", "ANTHROPIC", "GOOGLE_GEMINI")
            .contains(result.scope().provider())
        || !"v2".equals(result.hmacDomainVersion())) {
      throw new IllegalStateException();
    }
    for (String value :
        new String[] {
          result.externalLocator(), result.externalKeyVersion(), result.hmacKeyVersion()
        }) {
      if (value == null || !value.matches("[A-Za-z0-9_./:@-]{1,256}")) {
        throw new IllegalStateException();
      }
    }
    return result;
  }

  private static Capture capture(ResultSet rows) throws SQLException {
    Capture result =
        new Capture(
            rows.getObject("attempt_id", UUID.class),
            binding(rows),
            rows.getString("verifier"),
            rows.getObject("observed_at", OffsetDateTime.class),
            rows.getObject("deadline", OffsetDateTime.class));
    Objects.requireNonNull(result.attemptId());
    Objects.requireNonNull(result.verifier());
    if (!result.verifier().matches("[A-Za-z0-9+/]{43}=")) throw new IllegalStateException();
    byte[] decoded = Base64.getDecoder().decode(result.verifier());
    if (decoded.length != 32
        || !Base64.getEncoder().encodeToString(decoded).equals(result.verifier())) {
      throw new IllegalStateException();
    }
    Objects.requireNonNull(result.observedAt());
    Objects.requireNonNull(result.deadline());
    if (!validTime(result.observedAt())
        || !validTime(result.deadline())
        || !result.deadline().isEqual(result.observedAt().plusSeconds(60))) {
      throw new IllegalStateException();
    }
    return result;
  }

  private static Publication publication(ResultSet rows) throws SQLException {
    Publication result =
        new Publication(
            binding(rows),
            rows.getObject("publisher_id", UUID.class),
            rows.getLong("publisher_epoch"),
            rows.getObject("publisher_fence", UUID.class),
            rows.getString("assertion_kind"),
            rows.getLong("publication_revision"),
            rows.getString("state"),
            rows.getObject("recorded_at", OffsetDateTime.class),
            rows.getObject("observed_at", OffsetDateTime.class),
            rows.getObject("published_at", OffsetDateTime.class),
            rows.getObject("valid_until", OffsetDateTime.class));
    Objects.requireNonNull(result.publisherId());
    Objects.requireNonNull(result.publisherFence());
    if (result.publisherEpoch() <= 0 || result.publicationRevision() <= 0) {
      throw new IllegalStateException();
    }
    switch (result.assertionKind()) {
      case "HEALTH" -> {
        if (!java.util.List.of("USABLE", "UNAVAILABLE").contains(result.state())
            || result.recordedAt() != null) throw new IllegalStateException();
        Objects.requireNonNull(result.observedAt());
        Objects.requireNonNull(result.publishedAt());
        Objects.requireNonNull(result.validUntil());
        if (!validTime(result.observedAt())
            || !validTime(result.publishedAt())
            || !validTime(result.validUntil())
            || result.observedAt().isAfter(result.publishedAt())
            || !result.publishedAt().isBefore(result.validUntil())
            || result.validUntil().isAfter(result.observedAt().plusSeconds(60))) {
          throw new IllegalStateException();
        }
      }
      case "PREPARE", "INTEGRITY_FAILURE" -> {
        String expected = result.assertionKind().equals("PREPARE") ? "VERIFIED_AT_WRITE" : "FAILED";
        if (!expected.equals(result.state())
            || result.observedAt() != null
            || result.publishedAt() != null
            || result.validUntil() != null) throw new IllegalStateException();
        Objects.requireNonNull(result.recordedAt());
        if (!validTime(result.recordedAt())) throw new IllegalStateException();
      }
      default -> throw new IllegalStateException();
    }
    return result;
  }

  private static boolean validTime(OffsetDateTime value) {
    return value != null
        && value.getNano() % 1000 == 0
        && !value.toInstant().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
        && !value.toInstant().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"));
  }
}

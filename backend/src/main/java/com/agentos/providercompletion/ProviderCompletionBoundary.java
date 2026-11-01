package com.agentos.providercompletion;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary.Command;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providerreceipts.ProviderReceiptService.Result;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit internal completion boundary. Construct with the configured manager and receipt proxy.
 */
public final class ProviderCompletionBoundary {
  public enum Code {
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    INVALID_COMMAND(400),
    INVALID_KEY(400),
    INVALID_PREPARATION(400),
    NOT_FOUND(404),
    IDEMPOTENCY_CONFLICT(409),
    REVISION_CONFLICT(409),
    NO_CHANGE(409),
    REVISION_EXHAUSTED(409),
    CONNECTION_CONFLICT(409),
    PREPARATION_REQUIRED(409),
    RECEIPT_UNAVAILABLE(500),
    COMMAND_UNAVAILABLE(503),
    COMMAND_OUTCOME_UNKNOWN(503),
    LOOKUP_UNAVAILABLE(503);
    private final int status;

    Code(int status) {
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  @JsonIgnoreType
  public record Failure(Code code) {
    public Failure {
      if (code == null) throw invalid();
    }

    public int status() {
      return code.status();
    }

    @Override
    public String toString() {
      return "Failure[redacted]";
    }
  }

  public enum WriteState {
    COMMITTED,
    REJECTED,
    UNKNOWN,
    UNAVAILABLE
  }

  public enum LookupState {
    FOUND,
    ABSENT,
    REJECTED,
    UNAVAILABLE
  }

  @JsonIgnoreType
  public record WriteOutcome(WriteState state, Result result, Failure failure) {
    public WriteOutcome {
      if (state == null) throw invalid();
      boolean valid =
          switch (state) {
            case COMMITTED -> result != null && result.receipt() != null && failure == null;
            case REJECTED ->
                result == null
                    && failure != null
                    && (receiptCode(failure.code()) || failure.code() == Code.COMMAND_UNAVAILABLE);
            case UNKNOWN ->
                result == null && failure != null && failure.code() == Code.COMMAND_OUTCOME_UNKNOWN;
            case UNAVAILABLE ->
                result == null && failure != null && failure.code() == Code.COMMAND_UNAVAILABLE;
          };
      if (!valid) throw invalid();
    }

    @Override
    public String toString() {
      return "WriteOutcome[redacted]";
    }
  }

  @JsonIgnoreType
  public record LookupOutcome(LookupState state, Receipt receipt, Failure failure) {
    public LookupOutcome {
      if (state == null) throw invalid();
      boolean valid =
          switch (state) {
            case FOUND -> receipt != null && failure == null;
            case ABSENT -> receipt == null && failure == null;
            case REJECTED -> receipt == null && failure != null && denial(failure.code());
            case UNAVAILABLE ->
                receipt == null && failure != null && failure.code() == Code.LOOKUP_UNAVAILABLE;
          };
      if (!valid) throw invalid();
    }

    @Override
    public String toString() {
      return "LookupOutcome[redacted]";
    }
  }

  private final PlatformTransactionManager manager;
  private final DataSource dataSource;
  private final ProviderReceiptService receipts;

  public ProviderCompletionBoundary(
      PlatformTransactionManager manager, JdbcTemplate jdbc, ProviderReceiptService receipts) {
    if (manager == null || jdbc == null || receipts == null) throw invalid();
    try {
      this.dataSource = jdbc.getDataSource();
    } catch (RuntimeException failure) {
      throw invalid();
    }
    if (dataSource == null) throw invalid();
    this.manager = manager;
    this.receipts = receipts;
  }

  public WriteOutcome executePrepared(
      HumanIdentity actor,
      UUID org,
      UUID key,
      Command command,
      Metadata prepared,
      UUID createId,
      CredentialBoundary.Metadata credential) {
    Owned owned = begin(false);
    if (owned == null) return write(WriteState.UNAVAILABLE, Code.COMMAND_UNAVAILABLE);
    if (!owned.valid)
      return rollback(owned)
          ? write(WriteState.UNAVAILABLE, Code.COMMAND_UNAVAILABLE)
          : write(WriteState.UNKNOWN, Code.COMMAND_OUTCOME_UNKNOWN);
    Result result;
    try {
      result = receipts.execute(actor, org, key, command, prepared, createId, credential);
    } catch (RuntimeException failure) {
      Code code = known(failure);
      return rollback(owned)
          ? write(WriteState.REJECTED, code == null ? Code.COMMAND_UNAVAILABLE : code)
          : write(WriteState.UNKNOWN, Code.COMMAND_OUTCOME_UNKNOWN);
    }
    if (!commit(owned) || result == null || result.receipt() == null)
      return write(WriteState.UNKNOWN, Code.COMMAND_OUTCOME_UNKNOWN);
    return new WriteOutcome(WriteState.COMMITTED, result, null);
  }

  public LookupOutcome lookupOwn(HumanIdentity actor, UUID org, UUID key) {
    Owned owned = begin(true);
    if (owned == null) return unavailableLookup();
    if (!owned.valid) {
      rollback(owned);
      return unavailableLookup();
    }
    Result result;
    try {
      result = receipts.findOwn(actor, org, key);
    } catch (RuntimeException failure) {
      Code code = known(failure);
      if (rollback(owned) && denial(code))
        return new LookupOutcome(LookupState.REJECTED, null, new Failure(code));
      return unavailableLookup();
    }
    if (!commit(owned)) return unavailableLookup();
    if (result == null) return new LookupOutcome(LookupState.ABSENT, null, null);
    if (result.receipt() == null) return unavailableLookup();
    return new LookupOutcome(LookupState.FOUND, result.receipt(), null);
  }

  private Owned begin(boolean readOnly) {
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()
          || TransactionSynchronizationManager.hasResource(dataSource)) return null;
      var definition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
      int isolation =
          readOnly
              ? TransactionDefinition.ISOLATION_REPEATABLE_READ
              : TransactionDefinition.ISOLATION_READ_COMMITTED;
      definition.setIsolationLevel(isolation);
      definition.setReadOnly(readOnly);
      TransactionStatus status = manager.getTransaction(definition);
      if (status == null || !status.isNewTransaction()) return null;
      Owned owned = new Owned(status);
      try {
        if (TransactionSynchronizationManager.isSynchronizationActive())
          TransactionSynchronizationManager.registerSynchronization(owned);
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        owned.valid =
            TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly() == readOnly
                && resource instanceof ConnectionHolder holder
                && holder.getConnection().getTransactionIsolation() == isolation;
      } catch (Exception failure) {
        owned.valid = false;
      }
      return owned;
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private boolean commit(Owned owned) {
    try {
      manager.commit(owned.status);
      return owned.status.isCompleted()
          && owned.completion == TransactionSynchronization.STATUS_COMMITTED;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private boolean rollback(Owned owned) {
    try {
      manager.rollback(owned.status);
      return owned.status.isCompleted()
          && owned.completion == TransactionSynchronization.STATUS_ROLLED_BACK;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private static final class Owned implements TransactionSynchronization {
    final TransactionStatus status;
    boolean valid;
    int completion = -1;
    boolean recorded;

    Owned(TransactionStatus status) {
      this.status = status;
    }

    @Override
    public void afterCompletion(int status) {
      completion = recorded ? TransactionSynchronization.STATUS_UNKNOWN : status;
      recorded = true;
    }
  }

  private static Code known(RuntimeException failure) {
    if (!(failure instanceof ProviderReceiptFailure receipt)) return null;
    for (Code code : Code.values())
      if (receiptCode(code)
          && code.name().equals(receipt.code())
          && code.status() == receipt.status()) return code;
    return null;
  }

  private static boolean receiptCode(Code code) {
    return code != null
        && code != Code.COMMAND_UNAVAILABLE
        && code != Code.COMMAND_OUTCOME_UNKNOWN
        && code != Code.LOOKUP_UNAVAILABLE;
  }

  private static boolean denial(Code code) {
    return code == Code.UNAUTHORIZED || code == Code.FORBIDDEN || code == Code.INVALID_KEY;
  }

  private static WriteOutcome write(WriteState state, Code code) {
    return new WriteOutcome(state, null, new Failure(code));
  }

  private static LookupOutcome unavailableLookup() {
    return new LookupOutcome(LookupState.UNAVAILABLE, null, new Failure(Code.LOOKUP_UNAVAILABLE));
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("COMPLETION_BOUNDARY_INVALID", null);
  }
}

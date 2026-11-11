package com.agentos.providermetadata;

import com.agentos.auth.HumanIdentity;
import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Command;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerreceipts.ProviderReceiptService;
import java.util.UUID;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates metadata commands with durable admission and historical command keys. */
public final class ProviderMetadataCoordinator {
  private final ProviderReceiptService receipts;
  private final ProviderCompletionBoundary completion;
  private final CommandBoundary commands;
  private final ProviderWriteAdmission admission;

  public ProviderMetadataCoordinator(
      ProviderReceiptService receipts,
      ProviderCompletionBoundary completion,
      CommandBoundary commands,
      ProviderWriteAdmission admission) {
    if (receipts == null
        || !AopUtils.isAopProxy(receipts)
        || completion == null
        || commands == null
        || admission == null) throw new IllegalArgumentException("INVALID_CONFIGURATION");
    this.receipts = receipts;
    this.completion = completion;
    this.commands = commands;
    this.admission = admission;
  }

  public WriteOutcome rename(HumanIdentity actor, UUID organization, UUID key, Rename command) {
    return execute(actor, organization, key, command);
  }

  public WriteOutcome disable(
      HumanIdentity actor, UUID organization, UUID key, RevisionCommand command) {
    if (command == null || command.operation() != Operation.DISABLE)
      return new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.INVALID_COMMAND));
    return execute(actor, organization, key, command);
  }

  public WriteOutcome enable(
      HumanIdentity actor, UUID organization, UUID key, RevisionCommand command) {
    if (command == null || command.operation() != Operation.ENABLE)
      return new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.INVALID_COMMAND));
    return execute(actor, organization, key, command);
  }

  /** Command key access occurs outside transactions and does not require credential storage. */
  private WriteOutcome execute(HumanIdentity actor, UUID organization, UUID key, Command command) {
    boolean committing = false;
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()
          || command == null) return unavailable();
      // The proxied RR probe reauthorizes before historical HMAC access or fresh admission.
      var historical = receipts.probeOwn(actor, organization, key);
      if (historical == null && !admission.acquireMetadata(actor, organization, key)) {
        historical = receipts.probeOwn(actor, organization, key);
        if (historical == null) return unavailable();
      }
      var scope = new CommandBoundary.Scope(organization, actor.id());
      var digest =
          historical == null
              ? commands.prepareCurrent(scope, command, null)
              : commands.prepareVersion(scope, command, null, historical.metadata().keyVersion());
      committing = true;
      var result = complete(actor, organization, key, command, digest);
      // A concurrent receipt may have selected another HMAC version. Re-probe only once.
      if (result.state() == WriteState.REJECTED
          && result.failure().code() == Code.PREPARATION_REQUIRED) {
        committing = false;
        historical = receipts.probeOwn(actor, organization, key);
        if (historical == null) return result;
        digest = commands.prepareVersion(scope, command, null, historical.metadata().keyVersion());
        committing = true;
        return complete(actor, organization, key, command, digest);
      }
      return result;
    } catch (ProviderReceiptFailure failure) {
      if (failure.status() == 401 || failure.status() == 403) {
        return new WriteOutcome(
            WriteState.REJECTED,
            null,
            new Failure(failure.status() == 401 ? Code.UNAUTHORIZED : Code.FORBIDDEN));
      }
      if (!committing && failure.code().equals("RATE_LIMITED")) {
        return new WriteOutcome(
            WriteState.REJECTED, null, new Failure(Code.RATE_LIMITED, failure.retryAfter()));
      }
      return committing ? unknown() : unavailable();
    } catch (RuntimeException failure) {
      return committing ? unknown() : unavailable();
    }
  }

  private WriteOutcome complete(
      HumanIdentity actor, UUID organization, UUID key, Command command, Metadata digest) {
    if (command instanceof Rename rename)
      return completion.executeRenamePrepared(actor, organization, key, rename, digest);
    var stateChange = (RevisionCommand) command;
    return stateChange.operation() == Operation.ENABLE
        ? completion.executeEnablePrepared(actor, organization, key, stateChange, digest)
        : completion.executeDisablePrepared(actor, organization, key, stateChange, digest);
  }

  private static WriteOutcome unavailable() {
    return new WriteOutcome(WriteState.UNAVAILABLE, null, new Failure(Code.COMMAND_UNAVAILABLE));
  }

  private static WriteOutcome unknown() {
    return new WriteOutcome(WriteState.UNKNOWN, null, new Failure(Code.COMMAND_OUTCOME_UNKNOWN));
  }
}

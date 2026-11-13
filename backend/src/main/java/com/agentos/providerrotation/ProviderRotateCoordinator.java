package com.agentos.providerrotation;

import com.agentos.auth.HumanIdentity;
import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Rotate;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providerreceipts.ProviderReceiptService.Probe;
import com.agentos.providertransport.ProviderRotateBody;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal composition for governed credential rotation and historical replay. */
public final class ProviderRotateCoordinator {
  private final ProviderReceiptService receipts;
  private final ProviderCompletionBoundary completion;
  private final CommandBoundary commands;
  private final CredentialBoundary credentials;
  private final ProviderWriteAdmission admission;

  public ProviderRotateCoordinator(
      ProviderReceiptService receipts,
      ProviderCompletionBoundary completion,
      CommandBoundary commands,
      CredentialBoundary credentials,
      ProviderWriteAdmission admission) {
    if (!AopUtils.isAopProxy(receipts)
        || completion == null
        || commands == null
        || credentials == null
        || admission == null) throw new IllegalArgumentException("INVALID_CONFIGURATION");
    this.receipts = receipts;
    this.completion = completion;
    this.commands = commands;
    this.credentials = credentials;
    this.admission = admission;
  }

  /** Takes ownership of the body. A managed caller supplies journaled credential storage. */
  public WriteOutcome rotate(HumanIdentity actor, UUID org, UUID key, ProviderRotateBody body) {
    char[] secret = null;
    try (body) {
      if (body == null
          || TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()) return unavailable();
      var historical = receipts.probeOwn(actor, org, key);
      Rotate command = body.command();
      byte[] borrowed = body.credential();
      secret = new char[borrowed.length];
      for (int i = 0; i < borrowed.length; i++) secret[i] = (char) (borrowed[i] & 0xff);
      var scope = new CommandBoundary.Scope(org, actor.id());
      if (historical != null) return replay(actor, key, scope, command, secret, historical);

      CommandBoundary.Metadata digest;
      CredentialBoundary.Metadata credential;
      try {
        if (!admission.acquireMetadata(actor, org, key)) {
          historical = receipts.probeOwn(actor, org, key);
          return historical == null
              ? unavailable()
              : replay(actor, key, scope, command, secret, historical);
        }
        var candidate = receipts.prepareRotateScope(actor, org, command);
        digest = commands.prepareCurrent(scope, command, secret);
        credential = credentials.prepare(candidate, secret.clone());
      } catch (RuntimeException preparationFailure) {
        historical = receipts.probeOwn(actor, org, key);
        if (historical != null) return replay(actor, key, scope, command, secret, historical);
        return rejection(preparationFailure);
      }
      var outcome = completion.executeRotatePrepared(actor, org, key, command, digest, credential);
      if (outcome.state() != WriteState.REJECTED
          || outcome.failure().code() != Code.PREPARATION_REQUIRED) return outcome;
      historical = receipts.probeOwn(actor, org, key);
      return historical == null
          ? unavailable()
          : replay(actor, key, scope, command, secret, historical);
    } catch (RuntimeException failure) {
      return rejection(failure);
    } finally {
      if (secret != null) Arrays.fill(secret, '\0');
    }
  }

  private WriteOutcome replay(
      HumanIdentity actor,
      UUID key,
      CommandBoundary.Scope scope,
      Rotate command,
      char[] secret,
      Probe historical) {
    var receipt = historical.result().receipt();
    if (receipt.operation() != Operation.ROTATE || !command.target().equals(receipt.connectionId()))
      return new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.IDEMPOTENCY_CONFLICT));
    var digest =
        commands.prepareVersion(scope, command, secret, historical.metadata().keyVersion());
    return completion.executeRotatePrepared(
        actor, scope.organization(), key, command, digest, null);
  }

  private static WriteOutcome rejection(RuntimeException failure) {
    if (failure instanceof ProviderReceiptFailure receipt) {
      Code code =
          switch (receipt.code()) {
            case "UNAUTHORIZED" -> Code.UNAUTHORIZED;
            case "FORBIDDEN" -> Code.FORBIDDEN;
            case "INVALID_KEY" -> Code.INVALID_KEY;
            case "INVALID_COMMAND" -> Code.INVALID_COMMAND;
            case "NOT_FOUND" -> Code.NOT_FOUND;
            case "REVISION_CONFLICT" -> Code.REVISION_CONFLICT;
            case "REVISION_EXHAUSTED" -> Code.REVISION_EXHAUSTED;
            case "RATE_LIMITED" -> Code.RATE_LIMITED;
            default -> null;
          };
      if (code != null && code.status() == receipt.status())
        return new WriteOutcome(WriteState.REJECTED, null, new Failure(code, receipt.retryAfter()));
    }
    return unavailable();
  }

  private static WriteOutcome unavailable() {
    return new WriteOutcome(WriteState.UNAVAILABLE, null, new Failure(Code.COMMAND_UNAVAILABLE));
  }
}

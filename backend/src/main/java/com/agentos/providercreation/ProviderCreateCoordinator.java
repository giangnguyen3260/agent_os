package com.agentos.providercreation;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providertransport.ProviderCreateBody;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal preparation/replay composition, not a public command gate or automatic bean. */
public final class ProviderCreateCoordinator {
  private final ProviderReceiptService receipts;
  private final ProviderCompletionBoundary completion;
  private final CommandBoundary commands;
  private final CredentialBoundary credentials;

  public ProviderCreateCoordinator(
      ProviderReceiptService receipts,
      ProviderCompletionBoundary completion,
      CommandBoundary commands,
      CredentialBoundary credentials) {
    if (receipts == null
        || !AopUtils.isAopProxy(receipts)
        || completion == null
        || commands == null
        || credentials == null) throw new IllegalArgumentException("INVALID_CONFIGURATION");
    this.receipts = receipts;
    this.completion = completion;
    this.commands = commands;
    this.credentials = credentials;
  }

  /** Consumes/closes the decoded body. Future transport must first apply its full command gates. */
  public WriteOutcome create(
      HumanIdentity actor, UUID organization, UUID key, ProviderCreateBody body) {
    char[] secret = null;
    boolean committing = false;
    try (body) {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()
          || body == null) return unavailable();
      // The proxied RR probe authenticates/authorizes and commits before any key service access.
      var historical = receipts.probeOwn(actor, organization, key);
      var command = body.command();
      var scope = new CommandBoundary.Scope(organization, actor.id());
      byte[] borrowed = body.credential();
      secret = new char[borrowed.length];
      for (int i = 0; i < borrowed.length; i++) secret[i] = (char) (borrowed[i] & 0xff);
      if (historical != null) {
        var digest =
            commands.prepareVersion(scope, command, secret, historical.metadata().keyVersion());
        committing = true;
        return completion.executeCreatePrepared(
            actor, organization, key, command, digest, null, null);
      }
      var digest = commands.prepareCurrent(scope, command, secret);
      UUID connection = UUID.randomUUID();
      var credentialScope =
          new CredentialBoundary.Scope(
              organization,
              connection,
              CredentialBoundary.Provider.valueOf(command.provider().name()),
              1);
      var prepared = credentials.prepare(credentialScope, secret.clone());
      committing = true;
      var result =
          completion.executeCreatePrepared(
              actor, organization, key, command, digest, connection, prepared);
      // One bounded historical re-probe after a concurrent receipt chose another HMAC version.
      if (result.state() == WriteState.REJECTED
          && result.failure().code() == Code.PREPARATION_REQUIRED) {
        committing = false;
        historical = receipts.probeOwn(actor, organization, key);
        if (historical == null) return result;
        digest =
            commands.prepareVersion(scope, command, secret, historical.metadata().keyVersion());
        committing = true;
        return completion.executeCreatePrepared(
            actor, organization, key, command, digest, connection, prepared);
      }
      return result;
    } catch (ProviderReceiptFailure failure) {
      if (failure.status() == 401 || failure.status() == 403) {
        return new WriteOutcome(
            WriteState.REJECTED,
            null,
            new Failure(failure.status() == 401 ? Code.UNAUTHORIZED : Code.FORBIDDEN));
      }
      return committing ? unknown() : unavailable();
    } catch (RuntimeException failure) {
      return committing ? unknown() : unavailable();
    } finally {
      if (secret != null) Arrays.fill(secret, '\0');
    }
  }

  private static WriteOutcome unavailable() {
    return new WriteOutcome(WriteState.UNAVAILABLE, null, new Failure(Code.COMMAND_UNAVAILABLE));
  }

  private static WriteOutcome unknown() {
    return new WriteOutcome(WriteState.UNKNOWN, null, new Failure(Code.COMMAND_OUTCOME_UNKNOWN));
  }
}

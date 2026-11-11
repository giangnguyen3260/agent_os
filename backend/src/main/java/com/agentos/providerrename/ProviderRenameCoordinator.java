package com.agentos.providerrename;

import com.agentos.auth.HumanIdentity;
import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.agentos.providerreceipts.ProviderReceiptService;
import java.util.UUID;

/** Rename entry point used by the managed HTTP configuration. */
public final class ProviderRenameCoordinator {
  private final ProviderMetadataCoordinator metadata;

  public ProviderRenameCoordinator(
      ProviderReceiptService receipts,
      ProviderCompletionBoundary completion,
      CommandBoundary commands,
      ProviderWriteAdmission admission) {
    metadata = new ProviderMetadataCoordinator(receipts, completion, commands, admission);
  }

  public WriteOutcome rename(HumanIdentity actor, UUID organization, UUID key, Rename command) {
    return metadata.rename(actor, organization, key, command);
  }
}

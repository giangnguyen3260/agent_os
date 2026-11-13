package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import java.io.InputStream;
import java.util.UUID;

/** Decodes only the expected revision for an authorized Disable target. */
public final class ProviderDisableBody {
  private ProviderDisableBody() {}

  /** Reads at most 8193 bytes and leaves the caller-owned stream open. */
  public static RevisionCommand read(UUID target, InputStream input) {
    return ProviderMetadataBody.readRevisionCommand(target, input, Operation.DISABLE);
  }
}

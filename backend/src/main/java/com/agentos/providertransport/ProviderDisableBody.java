package com.agentos.providertransport;

import static com.agentos.providertransport.ProviderMetadataBody.invalid;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.fasterxml.jackson.core.JsonToken;
import java.io.InputStream;
import java.util.UUID;

/** Decodes only the expected revision for an authorized Disable target. */
public final class ProviderDisableBody {
  private ProviderDisableBody() {}

  /** Reads at most 8193 bytes and leaves the caller-owned stream open. */
  public static RevisionCommand read(UUID target, InputStream input) {
    return ProviderMetadataBody.read(
        input,
        parser -> {
          if (parser.nextToken() != JsonToken.FIELD_NAME
              || !"expectedRevision".equals(parser.currentName())
              || parser.nextToken() != JsonToken.VALUE_STRING) throw invalid();
          long revision = ProviderMetadataBody.revision(parser.getText());
          if (parser.nextToken() != JsonToken.END_OBJECT) throw invalid();
          return new RevisionCommand(Operation.DISABLE, target, revision);
        });
  }
}

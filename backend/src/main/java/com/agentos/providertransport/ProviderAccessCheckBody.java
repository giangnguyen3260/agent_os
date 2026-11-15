package com.agentos.providertransport;

import static com.agentos.providertransport.ProviderMetadataBody.invalid;
import static com.agentos.providertransport.ProviderMetadataBody.revision;

import com.agentos.providercommands.CommandBoundary.CredentialCommand;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.fasterxml.jackson.core.JsonToken;
import java.io.InputStream;
import java.util.UUID;

/** Bounded body decoder for a reviewed provider check against two exact revisions. */
public final class ProviderAccessCheckBody {
  private ProviderAccessCheckBody() {}

  /** Reads a caller-owned stream without closing it. */
  public static CredentialCommand read(UUID target, InputStream input) {
    return read(target, input, Operation.ACCESS_CHECK);
  }

  public static CredentialCommand readDiscovery(UUID target, InputStream input) {
    return read(target, input, Operation.MODEL_DISCOVERY);
  }

  private static CredentialCommand read(UUID target, InputStream input, Operation operation) {
    return ProviderMetadataBody.read(
        input,
        parser -> {
          Long configurationRevision = null;
          Long credentialRevision = null;
          while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid();
            String field = parser.currentName();
            if (parser.nextToken() != JsonToken.VALUE_STRING) throw invalid();
            switch (field) {
              case "expectedRevision" -> configurationRevision = revision(parser.getText());
              case "expectedCredentialRevision" -> credentialRevision = revision(parser.getText());
              default -> throw invalid();
            }
          }
          if (configurationRevision == null || credentialRevision == null) throw invalid();
          return new CredentialCommand(
              operation, target, configurationRevision, credentialRevision);
        });
  }
}

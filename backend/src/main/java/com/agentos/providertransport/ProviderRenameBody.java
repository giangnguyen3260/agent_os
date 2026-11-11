package com.agentos.providertransport;

import static com.agentos.providertransport.ProviderMetadataBody.invalid;

import com.agentos.providercommands.CommandBoundary.Rename;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Isolated Rename decoder; caller supplies the authorized target and transport deadline. */
@JsonIgnoreType
public final class ProviderRenameBody {
  private final Rename command;

  private ProviderRenameBody(Rename command) {
    this.command = command;
  }

  /** Reads at most 8193 bytes, leaving the caller-owned input stream open. */
  public static ProviderRenameBody read(UUID target, InputStream input) {
    return ProviderMetadataBody.read(input, parser -> decode(target, parser));
  }

  /** Consumes and erases the exclusively caller-owned body on success and failure. */
  public static ProviderRenameBody consume(UUID target, byte[] body) {
    return ProviderMetadataBody.consume(body, parser -> decode(target, parser));
  }

  private static ProviderRenameBody decode(UUID target, JsonParser parser) throws IOException {
    String revision = null;
    String name = null;
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid();
      String field = parser.currentName();
      if (parser.nextToken() != JsonToken.VALUE_STRING) throw invalid();
      switch (field) {
        case "expectedRevision" -> revision = parser.getText();
        case "name" -> name = parser.getText();
        default -> throw invalid();
      }
    }
    if (name == null) throw invalid();
    return new ProviderRenameBody(
        new Rename(target, ProviderMetadataBody.revision(revision), name));
  }

  public Rename command() {
    return command;
  }

  @Override
  public String toString() {
    return "ProviderRenameBody[redacted]";
  }
}

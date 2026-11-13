package com.agentos.providertransport;

import static com.agentos.providertransport.ProviderMetadataBody.invalid;

import com.agentos.providercommands.CommandBoundary.Rotate;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.core.JsonToken;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

/** Owns the transient credential in a decoded Rotate request. */
@JsonIgnoreType
public final class ProviderRotateBody implements AutoCloseable {
  private final Rotate command;
  private final byte[] key;
  private boolean closed;

  private ProviderRotateBody(Rotate command, byte[] key) {
    this.command = command;
    this.key = key;
  }

  /** Reads at most 8193 bytes and leaves the caller-owned stream open. */
  public static ProviderRotateBody read(UUID target, InputStream input) {
    return ProviderCredentialInput.read(input, body -> consume(target, body));
  }

  /** Consumes and erases the exclusively caller-owned request body. */
  public static ProviderRotateBody consume(UUID target, byte[] body) {
    byte[] secret = null;
    try {
      Rotate command;
      try (var parser = ProviderCredentialInput.parser(body)) {
        if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
        String expectedRevision = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid();
          String field = parser.currentName();
          if (parser.nextToken() != JsonToken.VALUE_STRING) throw invalid();
          switch (field) {
            case "expectedRevision" -> expectedRevision = parser.getText();
            case "apiKey" -> secret = ProviderCredentialInput.secret(parser);
            default -> throw invalid();
          }
        }
        if (secret == null || parser.nextToken() != null) throw invalid();
        command = new Rotate(target, ProviderMetadataBody.revision(expectedRevision));
      }
      var result = new ProviderRotateBody(command, secret);
      secret = null;
      return result;
    } catch (Exception failure) {
      throw invalid();
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
      if (secret != null) Arrays.fill(secret, (byte) 0);
    }
  }

  public Rotate command() {
    if (closed) throw invalid();
    return command;
  }

  /** Borrowed credential buffer. The caller must release it by closing this body. */
  public byte[] credential() {
    if (closed) throw invalid();
    return key;
  }

  @Override
  public void close() {
    Arrays.fill(key, (byte) 0);
    closed = true;
  }

  @Override
  public String toString() {
    return "ProviderRotateBody[redacted]";
  }
}

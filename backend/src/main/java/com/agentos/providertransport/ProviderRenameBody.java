package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Rename;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Isolated Rename decoder; the caller supplies the authorized path target and transport deadline.
 */
@JsonIgnoreType
public final class ProviderRenameBody {
  private static final JsonFactory JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private final Rename command;

  private ProviderRenameBody(Rename command) {
    this.command = command;
  }

  /** Reads at most 8193 bytes, leaving the caller-owned input stream open. */
  public static ProviderRenameBody read(UUID target, InputStream input) {
    byte[] bounded = new byte[8193];
    try {
      if (input == null) throw invalid();
      int used = 0;
      while (used < bounded.length) {
        int count = input.read(bounded, used, bounded.length - used);
        if (count == -1) return consume(target, Arrays.copyOf(bounded, used));
        if (count <= 0 || count > bounded.length - used) throw invalid();
        used += count;
      }
      throw invalid();
    } catch (Exception failure) {
      throw invalid();
    } finally {
      Arrays.fill(bounded, (byte) 0);
    }
  }

  /** Consumes and erases the exclusively caller-owned body on success and failure. */
  public static ProviderRenameBody consume(UUID target, byte[] body) {
    char[] decoded = null;
    try {
      if (body == null || body.length == 0 || body.length > 8192) throw invalid();
      var characters =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body));
      decoded = new char[characters.remaining()];
      characters.get(decoded);
      if (characters.hasArray()) Arrays.fill(characters.array(), '\0');
      // Parsing explicit decoded characters prevents Jackson's byte-input encoding autodetection.
      int offset = decoded.length > 0 && decoded[0] == '\ufeff' ? 1 : 0;
      try (JsonParser parser = JSON.createParser(decoded, offset, decoded.length - offset)) {
        if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
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
        if (revision == null
            || name == null
            || !revision.matches("0|[1-9][0-9]{0,18}")
            || parser.nextToken() != null) throw invalid();
        return new ProviderRenameBody(new Rename(target, Long.parseLong(revision), name));
      }
    } catch (Exception failure) {
      throw invalid();
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
      if (decoded != null) Arrays.fill(decoded, '\0');
    }
  }

  public Rename command() {
    return command;
  }

  @Override
  public String toString() {
    return "ProviderRenameBody[redacted]";
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_INPUT");
  }
}

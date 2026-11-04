package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Provider;
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
import java.util.HashSet;

/** Isolated create-body decoder. Authorization and transport gates belong to its future caller. */
@JsonIgnoreType
public final class ProviderCreateBody implements AutoCloseable {
  private static final JsonFactory JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private final Create command;
  private final byte[] key;
  private boolean closed;

  private ProviderCreateBody(Create command, byte[] key) {
    this.command = command;
    this.key = key;
  }

  /**
   * Reads at most 8193 bytes without trusting Content-Length. The caller owns stream lifecycle and
   * must enforce authentication, ingress concurrency and transport deadlines before calling.
   */
  public static ProviderCreateBody read(InputStream input) {
    byte[] bounded = new byte[8193];
    try {
      if (input == null) throw invalid();
      int used = 0;
      while (used < bounded.length) {
        int count = input.read(bounded, used, bounded.length - used);
        if (count == -1) return consume(Arrays.copyOf(bounded, used));
        // A non-progressing or broken stream must not create an unbounded loop.
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

  /** Consumes and erases the exclusively caller-owned body, including on failure. */
  public static ProviderCreateBody consume(byte[] body) {
    byte[] secret = null;
    try {
      if (body == null || body.length == 0 || body.length > 8192) throw invalid();
      // Validate UTF-8 without constructing an immutable String containing the credential.
      var decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body));
      if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
      try (JsonParser parser = JSON.createParser(body)) {
        if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
        var fields = new HashSet<String>();
        String provider = null;
        String mode = null;
        String name = null;
        String workspace = null;
        boolean hasWorkspace = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid();
          String field = parser.currentName();
          fields.add(field);
          parser.nextToken();
          switch (field) {
            case "provider" -> provider = text(parser);
            case "credentialMode" -> mode = text(parser);
            case "name" -> name = text(parser);
            case "apiKey" -> secret = secret(parser);
            case "options" -> {
              if (parser.currentToken() != JsonToken.START_OBJECT) throw invalid();
              if (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME
                    || !"workspaceId".equals(parser.currentName())) throw invalid();
                hasWorkspace = true;
                parser.nextToken();
                workspace = parser.currentToken() == JsonToken.VALUE_NULL ? null : text(parser);
                if (parser.nextToken() != JsonToken.END_OBJECT) throw invalid();
              }
            }
            default -> throw invalid();
          }
        }
        if (fields.size() != 5 || secret == null || parser.nextToken() != null) throw invalid();
        Provider type = Provider.valueOf(provider);
        if (hasWorkspace != (type == Provider.ANTHROPIC)) throw invalid();
        var result =
            new ProviderCreateBody(
                new Create(type, CredentialMode.valueOf(mode), name, workspace), secret);
        secret = null;
        return result;
      }
    } catch (Exception failure) {
      throw invalid();
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
      if (secret != null) Arrays.fill(secret, (byte) 0);
    }
  }

  public Create command() {
    if (closed) throw invalid();
    return command;
  }

  /** Borrowed credential buffer; caller must not retain it after close. */
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
    return "ProviderCreateBody[redacted]";
  }

  private static String text(JsonParser parser) throws Exception {
    if (parser.currentToken() != JsonToken.VALUE_STRING) throw invalid();
    return parser.getText();
  }

  private static byte[] secret(JsonParser parser) throws Exception {
    if (parser.currentToken() != JsonToken.VALUE_STRING) throw invalid();
    int length = parser.getTextLength();
    char[] chars = parser.getTextCharacters();
    int offset = parser.getTextOffset();
    try {
      if (length < 1 || length > 4096) throw invalid();
      for (int i = 0; i < length; i++) {
        if (chars[offset + i] < 0x21 || chars[offset + i] > 0x7e) throw invalid();
      }
      byte[] result = new byte[length];
      for (int i = 0; i < length; i++) result[i] = (byte) chars[offset + i];
      return result;
    } finally {
      Arrays.fill(chars, offset, offset + length, '\0');
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_INPUT");
  }
}

package com.agentos.providertransport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ProviderMetadataBody {
  private static final JsonFactory JSON =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private ProviderMetadataBody() {}

  interface Decoder<T> {
    T decode(JsonParser parser) throws IOException;
  }

  /** Reads at most 8193 bytes, leaving the caller-owned input stream open. */
  static <T> T read(InputStream input, Decoder<T> decoder) {
    byte[] bounded = new byte[8193];
    try {
      if (input == null) throw invalid();
      int used = 0;
      while (used < bounded.length) {
        int count = input.read(bounded, used, bounded.length - used);
        if (count == -1) return consume(Arrays.copyOf(bounded, used), decoder);
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
  static <T> T consume(byte[] body, Decoder<T> decoder) {
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
        T result = decoder.decode(parser);
        if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null)
          throw invalid();
        return result;
      }
    } catch (Exception failure) {
      throw invalid();
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
      if (decoded != null) Arrays.fill(decoded, '\0');
    }
  }

  static long revision(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
    return Long.parseLong(value);
  }

  static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_INPUT");
  }
}

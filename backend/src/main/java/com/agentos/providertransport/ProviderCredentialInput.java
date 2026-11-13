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
import java.util.function.Function;

/** Shared bounded input and credential decoding for secret-bearing commands. */
final class ProviderCredentialInput {
  private static final JsonFactory JSON =
      JsonFactory.builder()
          .disable(JsonFactory.Feature.CHARSET_DETECTION)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private ProviderCredentialInput() {}

  static <T> T read(InputStream input, Function<byte[], T> consume) {
    byte[] bounded = new byte[8193];
    try {
      if (input == null) throw invalid();
      int used = 0;
      while (used < bounded.length) {
        int count = input.read(bounded, used, bounded.length - used);
        if (count == -1) return consume.apply(Arrays.copyOf(bounded, used));
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

  static JsonParser parser(byte[] body) throws IOException {
    if (body == null || body.length == 0 || body.length > 8192) throw invalid();
    // Validate UTF-8 without constructing an immutable String containing the credential.
    var decoded =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body));
    if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
    // Preserve the existing single UTF-8 BOM allowance without detecting other encodings.
    int offset =
        body.length >= 3
                && body[0] == (byte) 0xef
                && body[1] == (byte) 0xbb
                && body[2] == (byte) 0xbf
            ? 3
            : 0;
    return JSON.createParser(body, offset, body.length - offset);
  }

  static byte[] secret(JsonParser parser) throws IOException {
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
    return ProviderMetadataBody.invalid();
  }
}

package com.agentos.taskowner;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Isolated parser: never changes application-wide Jackson coercion or duplicate handling. */
final class TaskOwnerWire {
  static final int MAX_BYTES = 4096;
  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Set<String> FIELDS = Set.of("expectedRevision", "expectedFingerprint", "ownerAgentId", "ownerFingerprint");

  private TaskOwnerWire() {}

  static int page(String value) {
    if (value == null) return 0;
    if (!value.matches("0|[1-9][0-9]{0,5}")) throw TaskOwnerFailure.bad();
    int page = Integer.parseInt(value);
    if (page > 100000) throw TaskOwnerFailure.bad();
    return page;
  }

  static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw TaskOwnerFailure.bad();
    return UUID.fromString(value);
  }

  static String text(String value, int max, boolean blank) {
    if (value == null || value.codePointCount(0, value.length()) > max
        || (!blank && value.isBlank())) throw TaskOwnerFailure.bad();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') throw TaskOwnerFailure.bad();
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw TaskOwnerFailure.bad();
      } else if (Character.isLowSurrogate(c)) throw TaskOwnerFailure.bad();
    }
    return value;
  }

  static String query(String value) {
    String raw = text(value == null ? "" : value, Integer.MAX_VALUE, true);
    return text(raw.strip(), 100, true);
  }

  static TaskOwnerDtos.Command read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw TaskOwnerFailure.bad();
    String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (java.nio.charset.CharacterCodingException ex) {
      throw TaskOwnerFailure.bad();
    }
    try (var parser = JSON.getFactory().createParser(source)) {
      JsonNode root = JSON.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null)
        throw TaskOwnerFailure.bad();
      Set<String> names = new HashSet<>();
      root.fieldNames().forEachRemaining(names::add);
      if (!names.equals(FIELDS)) throw TaskOwnerFailure.bad();
      int revision = integer(root.get("expectedRevision"), 0, Integer.MAX_VALUE);
      String fingerprint = string(root.get("expectedFingerprint"));
      if (!fingerprint.matches("[0-9a-f]{64}")) throw TaskOwnerFailure.bad();
      JsonNode id = root.get("ownerAgentId"), fp = root.get("ownerFingerprint");
      if (id.isNull()) {
        if (!fp.isNull()) throw TaskOwnerFailure.bad();
        return new TaskOwnerDtos.Command(revision, fingerprint, null, null);
      }
      String targetFingerprint = string(fp);
      if (!targetFingerprint.matches("[0-9a-f]{64}")) throw TaskOwnerFailure.bad();
      return new TaskOwnerDtos.Command(revision, fingerprint, uuid(string(id)), targetFingerprint);
    } catch (com.fasterxml.jackson.core.JacksonException | ArithmeticException ex) {
      throw TaskOwnerFailure.bad();
    }
  }

  private static String string(JsonNode value) {
    if (value == null || !value.isTextual()) throw TaskOwnerFailure.bad();
    return value.textValue();
  }

  private static int integer(JsonNode value, int min, int max) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < min
        || value.intValue() > max) throw TaskOwnerFailure.bad();
    return value.intValue();
  }
}

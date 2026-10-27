package com.agentos.taskediting;

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
final class TaskDraftEditWire {
  static final int MAX_BYTES = 65536;
  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Set<String> FIELDS =
      Set.of(
          "expectedRevision",
          "expectedFingerprint",
          "title",
          "description",
          "acceptanceCriteria",
          "priority");

  private TaskDraftEditWire() {}

  static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw TaskDraftEditFailure.bad();
    return UUID.fromString(value);
  }

  static String safe(String value, int max, boolean multiline) {
    if (value == null) {
      throw TaskDraftEditFailure.bad();
    }
    // Validate before stripping so leading control characters cannot disappear.
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c < 32 && !(multiline && (c == 9 || c == 10 || c == 13))) || c == 127)
        throw TaskDraftEditFailure.bad();
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw TaskDraftEditFailure.bad();
      } else if (Character.isLowSurrogate(c)) throw TaskDraftEditFailure.bad();
    }
    String result = value.strip();
    int length = result.codePointCount(0, result.length());
    if (length > max || length == 0) throw TaskDraftEditFailure.bad();
    return result;
  }

  static TaskDraftEditDtos.Command read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw TaskDraftEditFailure.bad();
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
      throw TaskDraftEditFailure.bad();
    }
    try (var parser = JSON.getFactory().createParser(source)) {
      JsonNode root = JSON.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null)
        throw TaskDraftEditFailure.bad();
      Set<String> names = new HashSet<>();
      root.fieldNames().forEachRemaining(names::add);
      if (!names.equals(FIELDS)) throw TaskDraftEditFailure.bad();
      int revision = integer(root.get("expectedRevision"), 0, Integer.MAX_VALUE);
      String fingerprint = string(root.get("expectedFingerprint"));
      if (!fingerprint.matches("[0-9a-f]{64}")) throw TaskDraftEditFailure.bad();
      String priority = string(root.get("priority"));
      if (!Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority))
        throw TaskDraftEditFailure.bad();
      return new TaskDraftEditDtos.Command(
          revision,
          fingerprint,
          safe(string(root.get("title")), 255, false),
          safe(string(root.get("description")), 8000, true),
          safe(string(root.get("acceptanceCriteria")), 4000, true),
          priority);
    } catch (com.fasterxml.jackson.core.JacksonException | ArithmeticException ex) {
      throw TaskDraftEditFailure.bad();
    }
  }

  private static String string(JsonNode value) {
    if (value == null || !value.isTextual()) throw TaskDraftEditFailure.bad();
    return value.textValue();
  }

  private static int integer(JsonNode value, int min, int max) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < min
        || value.intValue() > max) throw TaskDraftEditFailure.bad();
    return value.intValue();
  }
}

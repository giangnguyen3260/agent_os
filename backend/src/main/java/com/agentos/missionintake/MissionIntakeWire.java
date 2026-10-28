package com.agentos.missionintake;

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

final class MissionIntakeWire {
  static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

  private MissionIntakeWire() {}

  static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw MissionIntakeFailure.bad();
    return UUID.fromString(value);
  }

  static long version(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw MissionIntakeFailure.bad();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw MissionIntakeFailure.bad();
    }
  }

  static JsonNode object(String source, Set<String> fields) throws IOException {
    try (var parser = JSON.getFactory().createParser(source)) {
      JsonNode root = JSON.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null)
        throw MissionIntakeFailure.bad();
      Set<String> names = new HashSet<>();
      root.fieldNames().forEachRemaining(names::add);
      if (!names.equals(fields)) throw MissionIntakeFailure.bad();
      return root;
    }
  }

  static MissionIntakeDtos.Command read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(4097);
    if (bytes.length > 4096) throw MissionIntakeFailure.bad();
    try {
      String source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = object(source, Set.of("expectedVersion", "expectedFingerprint", "paused"));
      if (!root.get("expectedVersion").isTextual()
          || !root.get("expectedFingerprint").isTextual()
          || !root.get("paused").isBoolean()) throw MissionIntakeFailure.bad();
      String version = root.get("expectedVersion").textValue();
      version(version);
      String fingerprint = root.get("expectedFingerprint").textValue();
      if (!fingerprint.matches("[0-9a-f]{64}")) throw MissionIntakeFailure.bad();
      return new MissionIntakeDtos.Command(version, fingerprint, root.get("paused").booleanValue());
    } catch (com.fasterxml.jackson.core.JacksonException
        | java.nio.charset.CharacterCodingException ex) {
      throw MissionIntakeFailure.bad();
    }
  }
}

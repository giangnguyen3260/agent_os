package com.agentos.teamsettings;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Isolated parser: never changes application-wide Jackson coercion or duplicate handling. */
final class TeamSettingsWire {
  static final int MAX_BYTES = 32768;
  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Set<String> FIELDS =
      Set.of(
          "expectedRevision",
          "expectedFingerprint",
          "name",
          "purpose",
          "description",
          "managerAgentId",
          "agentIds",
          "intake",
          "concurrency",
          "monthlyBudget",
          "acknowledgement");

  private TeamSettingsWire() {}

  static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw TeamSettingsFailure.bad();
    return UUID.fromString(value);
  }

  static String safe(String value, int max, boolean required) {
    if (value == null) {
      if (required) throw TeamSettingsFailure.bad();
      return null;
    }
    // Validate before stripping so leading control characters cannot disappear.
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 32 || c == 127) throw TeamSettingsFailure.bad();
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw TeamSettingsFailure.bad();
      } else if (Character.isLowSurrogate(c)) throw TeamSettingsFailure.bad();
    }
    String result = required ? value.strip() : value;
    int length = result.codePointCount(0, result.length());
    if (length > max || (required && length == 0)) throw TeamSettingsFailure.bad();
    return result;
  }

  static TeamSettingsDtos.Command read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw TeamSettingsFailure.bad();
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
      throw TeamSettingsFailure.bad();
    }
    try (var parser = JSON.getFactory().createParser(source)) {
      JsonNode root = JSON.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null)
        throw TeamSettingsFailure.bad();
      Set<String> names = new HashSet<>();
      root.fieldNames().forEachRemaining(names::add);
      if (!names.equals(FIELDS)) throw TeamSettingsFailure.bad();
      int revision = integer(root.get("expectedRevision"), 0, Integer.MAX_VALUE);
      String fingerprint = string(root.get("expectedFingerprint"));
      if (!fingerprint.matches("[0-9a-f]{64}")) throw TeamSettingsFailure.bad();
      UUID manager =
          root.get("managerAgentId").isNull() ? null : uuid(string(root.get("managerAgentId")));
      JsonNode array = root.get("agentIds");
      if (!array.isArray() || array.size() + (manager == null ? 0 : 1) > 200)
        throw TeamSettingsFailure.bad();
      var ids = new ArrayList<UUID>();
      for (JsonNode id : array) ids.add(uuid(string(id)));
      if (new HashSet<>(ids).size() != ids.size() || ids.contains(manager))
        throw TeamSettingsFailure.bad();
      ids.sort(Comparator.comparing(UUID::toString));
      if (!"MANUAL".equals(string(root.get("intake")))) throw TeamSettingsFailure.bad();
      if (!root.get("acknowledgement").isBoolean() || !root.get("acknowledgement").booleanValue())
        throw TeamSettingsFailure.bad();
      JsonNode money = root.get("monthlyBudget");
      if (!money.isNumber()) throw TeamSettingsFailure.bad();
      // Read lexical decimal, rather than JsonNode's default double representation.
      BigDecimal budget = decimal(bytes);
      if (budget.scale() > 2
          || budget.signum() < 0
          || budget.compareTo(new BigDecimal("1000000")) > 0) throw TeamSettingsFailure.bad();
      return new TeamSettingsDtos.Command(
          revision,
          fingerprint,
          safe(string(root.get("name")), 255, true),
          safe(string(root.get("purpose")), 2000, true),
          root.get("description").isNull()
              ? null
              : safe(string(root.get("description")), 4000, false),
          manager,
          java.util.List.copyOf(ids),
          "MANUAL",
          integer(root.get("concurrency"), 1, 1000),
          budget.setScale(2),
          true);
    } catch (com.fasterxml.jackson.core.JacksonException | ArithmeticException ex) {
      throw TeamSettingsFailure.bad();
    }
  }

  private static BigDecimal decimal(byte[] bytes) throws IOException {
    try (var parser = JSON.getFactory().createParser(bytes)) {
      while (parser.nextToken() != null) {
        if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME
            && "monthlyBudget".equals(parser.currentName())) {
          parser.nextToken();
          return parser.getDecimalValue();
        }
      }
    }
    throw TeamSettingsFailure.bad();
  }

  private static String string(JsonNode value) {
    if (value == null || !value.isTextual()) throw TeamSettingsFailure.bad();
    return value.textValue();
  }

  private static int integer(JsonNode value, int min, int max) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < min
        || value.intValue() > max) throw TeamSettingsFailure.bad();
    return value.intValue();
  }
}

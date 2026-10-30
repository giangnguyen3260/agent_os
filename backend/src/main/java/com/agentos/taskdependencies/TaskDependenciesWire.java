package com.agentos.taskdependencies;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Isolated parser: never changes application-wide Jackson coercion or duplicate handling. */
final class TaskDependenciesWire {
  static final int MAX_BYTES = 16384;
  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Set<String> FIELDS =
      Set.of("expectedRevision", "expectedFingerprint", "dependencies");

  private TaskDependenciesWire() {}

  static int page(String value) {
    if (value == null) return 0;
    if (!value.matches("0|[1-9][0-9]{0,5}")) throw TaskDependenciesFailure.bad();
    int page = Integer.parseInt(value);
    if (page > 100000) throw TaskDependenciesFailure.bad();
    return page;
  }

  static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw TaskDependenciesFailure.bad();
    return UUID.fromString(value);
  }

  static String text(String value, int max, boolean blank) {
    if (value == null
        || value.codePointCount(0, value.length()) > max
        || (!blank && value.isBlank())) throw TaskDependenciesFailure.bad();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isISOControl(c)) throw TaskDependenciesFailure.bad();
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw TaskDependenciesFailure.bad();
      } else if (Character.isLowSurrogate(c)) throw TaskDependenciesFailure.bad();
    }
    return value;
  }

  static String query(String value) {
    String raw = text(value == null ? "" : value, Integer.MAX_VALUE, true);
    if (raw.indexOf('\u2028') >= 0 || raw.indexOf('\u2029') >= 0)
      throw TaskDependenciesFailure.bad();
    return text(raw.strip(), 100, true);
  }

  static TaskDependenciesDtos.Command read(InputStream input, UUID sourceId) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw TaskDependenciesFailure.bad();
    String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException ex) {
      throw TaskDependenciesFailure.bad();
    }
    try (var parser = JSON.getFactory().createParser(source)) {
      JsonNode root = JSON.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null)
        throw TaskDependenciesFailure.bad();
      Set<String> names = new HashSet<>();
      root.fieldNames().forEachRemaining(names::add);
      if (!names.equals(FIELDS)) throw TaskDependenciesFailure.bad();
      int revision = integer(root.get("expectedRevision"), 0, Integer.MAX_VALUE);
      String fingerprint = string(root.get("expectedFingerprint"));
      if (!fingerprint.matches("[0-9a-f]{64}")) throw TaskDependenciesFailure.bad();
      JsonNode targets = root.get("dependencies");
      if (targets == null || !targets.isArray() || targets.size() > 20)
        throw TaskDependenciesFailure.bad();
      var result = new ArrayList<TaskDependenciesDtos.DesiredTarget>();
      var ids = new HashSet<UUID>();
      for (JsonNode target : targets) {
        exact(target, Set.of("taskId", "fingerprint"));
        UUID id = uuid(string(target.get("taskId")));
        String fp = string(target.get("fingerprint"));
        if (!fp.matches("[0-9a-f]{64}") || !ids.add(id) || id.equals(sourceId))
          throw TaskDependenciesFailure.bad();
        result.add(new TaskDependenciesDtos.DesiredTarget(id, fp));
      }
      result.sort(Comparator.comparing(t -> t.taskId().toString()));
      return new TaskDependenciesDtos.Command(revision, fingerprint, List.copyOf(result));
    } catch (JacksonException | ArithmeticException ex) {
      throw TaskDependenciesFailure.bad();
    }
  }

  static void exact(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject()) throw TaskDependenciesFailure.bad();
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    if (!names.equals(fields)) throw TaskDependenciesFailure.bad();
  }

  static String string(JsonNode value) {
    if (value == null || !value.isTextual()) throw TaskDependenciesFailure.bad();
    return value.textValue();
  }

  static int integer(JsonNode value, int min, int max) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < min
        || value.intValue() > max) throw TaskDependenciesFailure.bad();
    return value.intValue();
  }
}

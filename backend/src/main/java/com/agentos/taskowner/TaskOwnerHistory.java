package com.agentos.taskowner;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict immutable event projection. Never resolves an Agent or a receipt. */
final class TaskOwnerHistory {
  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

  private TaskOwnerHistory() {}

  static TaskOwnerFailure unavailable() {
    return new TaskOwnerFailure(500, "TASK_OWNER_HISTORY_UNAVAILABLE");
  }

  static String text(String value, int max, boolean blank) {
    try {
      return TaskOwnerWire.text(value, max, blank);
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  static UUID id(UUID value) {
    if (value == null) throw unavailable();
    try {
      return TaskOwnerWire.uuid(value.toString());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  private static void keys(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject()) throw unavailable();
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw unavailable();
  }

  private static String string(JsonNode node) {
    if (node == null || !node.isTextual()) throw unavailable();
    return node.textValue();
  }

  static TaskOwnerDtos.Owner owner(String source, UUID raw, boolean after) {
    try (var parser = JSON.getFactory().createParser(source)) {
      JsonNode root = JSON.readTree(parser);
      if (parser.nextToken() != null) throw unavailable();
      keys(root, Set.of("state", "agent"));
      String state = string(root.get("state"));
      JsonNode agent = root.get("agent");
      if ("ASSIGNED".equals(state)) {
        keys(agent, Set.of("id", "name", "level", "status"));
        UUID id = TaskOwnerWire.uuid(string(agent.get("id")));
        String status = text(string(agent.get("status")), 50, false);
        if (!id.equals(raw) || (after && !Set.of("DRAFT", "ACTIVE").contains(status)))
          throw unavailable();
        return new TaskOwnerDtos.Owner(
            state,
            new TaskOwnerDtos.Agent(
                id,
                text(string(agent.get("name")), 255, false),
                text(string(agent.get("level")), 50, false),
                status));
      }
      if (!agent.isNull()
          || !("UNASSIGNED".equals(state) || (!after && "UNAVAILABLE".equals(state)))
          || ("UNASSIGNED".equals(state) != (raw == null))) throw unavailable();
      return new TaskOwnerDtos.Owner(state, null);
    } catch (Exception ex) {
      throw unavailable();
    }
  }
}

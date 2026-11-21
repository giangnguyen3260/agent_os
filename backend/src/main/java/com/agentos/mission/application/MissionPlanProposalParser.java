package com.agentos.mission.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Strict data format: no owner, execution, authority or lifecycle directives from the model. */
public final class MissionPlanProposalParser {
  private MissionPlanProposalParser() {}

  public record Task(
      String key,
      String title,
      String description,
      String acceptanceCriteria,
      String priority,
      List<String> dependencies) {
    @Override
    public String toString() {
      return "ProposedTask[redacted]";
    }
  }

  public record Proposal(int schemaVersion, List<Task> tasks, List<String> order) {
    @Override
    public String toString() {
      return "MissionPlanProposal[redacted]";
    }
  }

  private static void fields(JsonNode node, Set<String> names) {
    if (!node.isObject() || node.size() != names.size()) throw invalid();
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!names.contains(name)) throw invalid();
            });
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid planning proposal");
  }

  private static String text(JsonNode node, int max) {
    if (node == null || !node.isTextual()) throw invalid();
    String s = node.textValue();
    if (!s.equals(s.strip())
        || s.isBlank()
        || s.length() > max
        || !StandardCharsets.UTF_8.newEncoder().canEncode(s)
        || s.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
      throw invalid();
    return s;
  }

  public static Proposal parse(String content) {
    if (content == null
        || content.length() > 65536
        || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
        || content.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
    try (var parser = new ObjectMapper().getFactory().createParser(content)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode root = new ObjectMapper().readTree(parser);
      if (parser.nextToken() != null) throw invalid();
      fields(root, Set.of("schemaVersion", "tasks"));
      if (!root.path("schemaVersion").isIntegralNumber()
          || !root.path("schemaVersion").canConvertToInt()
          || root.path("schemaVersion").intValue() != 1
          || !root.path("tasks").isArray()
          || root.path("tasks").isEmpty()
          || root.path("tasks").size() > 20) throw invalid();
      var tasks = new TreeMap<String, Task>();
      int edges = 0;
      for (var node : root.path("tasks")) {
        fields(
            node,
            Set.of(
                "key", "title", "description", "acceptanceCriteria", "priority", "dependencies"));
        String key = text(node.get("key"), 40), priority = text(node.get("priority"), 10);
        if (!key.matches("[a-z][a-z0-9_]{0,39}")
            || !Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority)
            || !node.path("dependencies").isArray()
            || node.path("dependencies").size() > 20) throw invalid();
        var dependencies = new ArrayList<String>();
        for (var dep : node.path("dependencies")) dependencies.add(text(dep, 40));
        if (new HashSet<>(dependencies).size() != dependencies.size() || dependencies.contains(key))
          throw invalid();
        edges += dependencies.size();
        var task =
            new Task(
                key,
                text(node.get("title"), 255),
                text(node.get("description"), 8000),
                text(node.get("acceptanceCriteria"), 4000),
                priority,
                dependencies.stream().sorted().toList());
        if (tasks.put(key, task) != null) throw invalid();
      }
      if (edges > 100) throw invalid();
      for (var task : tasks.values())
        if (!tasks.keySet().containsAll(task.dependencies())) throw invalid();
      var order = new ArrayList<String>();
      var remaining = new java.util.TreeSet<>(tasks.keySet());
      while (!remaining.isEmpty()) {
        var ready =
            remaining.stream().filter(k -> order.containsAll(tasks.get(k).dependencies())).toList();
        if (ready.isEmpty()) throw invalid();
        order.addAll(ready);
        remaining.removeAll(ready);
      }
      return new Proposal(1, List.copyOf(tasks.values()), List.copyOf(order));
    } catch (Exception ex) {
      throw invalid();
    }
  }
}

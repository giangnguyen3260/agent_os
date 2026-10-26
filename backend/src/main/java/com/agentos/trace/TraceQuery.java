package com.agentos.trace;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

public record TraceQuery(String pattern, UUID missionId, UUID taskId, int page) {
  public static UUID uuid(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("Invalid UUID");
    }
    return UUID.fromString(value);
  }

  public static void validateParameters(
      MultiValueMap<String, String> parameters, Set<String> allowed) {
    parameters.forEach(
        (key, values) -> {
          if (!allowed.contains(key) || values.size() != 1) {
            throw new IllegalArgumentException("Invalid query parameters");
          }
        });
  }

  public static TraceQuery parse(MultiValueMap<String, String> parameters) {
    validateParameters(parameters, Set.of("q", "missionId", "taskId", "page"));
    String query = parameters.getFirst("q");
    query = query == null ? "" : query.strip();
    if (query.codePointCount(0, query.length()) > 100) {
      throw new IllegalArgumentException("Query too long");
    }
    String page = parameters.getFirst("page");
    page = page == null ? "0" : page;
    if (!page.matches("0|[1-9][0-9]{0,5}") || Integer.parseInt(page) > 100000) {
      throw new IllegalArgumentException("Invalid page");
    }
    return new TraceQuery(
        "%"
            + query
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%",
        parameters.containsKey("missionId") ? uuid(parameters.getFirst("missionId")) : null,
        parameters.containsKey("taskId") ? uuid(parameters.getFirst("taskId")) : null,
        Integer.parseInt(page));
  }
}

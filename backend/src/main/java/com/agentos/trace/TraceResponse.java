package com.agentos.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TraceResponse {
  private TraceResponse() {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Team(UUID id, String name) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Reference(UUID id, String title) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Event(
      UUID id,
      String eventType,
      String actorType,
      Instant createdAt,
      Team team,
      Reference mission,
      Reference task) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Page(
      UUID organizationId,
      Instant generatedAt,
      String runtimeStatus,
      List<Event> items,
      int page,
      int size,
      long total) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Detail(
      UUID organizationId, Instant generatedAt, String runtimeStatus, Event event) {}
}

package com.agentos.common.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEvent(
    UUID eventId,
    String eventType,
    UUID organizationId,
    UUID missionId,
    UUID taskId,
    ActorType actorType,
    UUID actorId,
    Instant occurredAt,
    Map<String, Object> payload) {

  public static DomainEvent system(
      String eventType, UUID organizationId, Map<String, Object> payload) {
    return new DomainEvent(
        UUID.randomUUID(),
        eventType,
        organizationId,
        null,
        null,
        ActorType.SYSTEM,
        null,
        Instant.now(),
        payload);
  }
}

package com.agentos.observability.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    UUID organizationId,
    UUID missionId,
    String eventType,
    String payload,
    Instant createdAt) {}

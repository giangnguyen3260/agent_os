package com.agentos.organization.api.dto;

import com.agentos.organization.domain.TeamStatus;
import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
    UUID id,
    UUID organizationId,
    String name,
    String purpose,
    TeamStatus status,
    Instant createdAt) {}

package com.agentos.organization.api.dto;

import com.agentos.organization.domain.OrganizationStatus;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
    UUID id,
    String name,
    String slug,
    String description,
    OrganizationStatus status,
    Instant createdAt) {}

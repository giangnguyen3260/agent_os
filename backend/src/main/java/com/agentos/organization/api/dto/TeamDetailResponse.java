package com.agentos.organization.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TeamDetailResponse(
    UUID id,
    UUID organizationId,
    String name,
    String purpose,
    String description,
    String status,
    UUID managerAgentId,
    List<UUID> agentIds,
    String intake,
    Integer concurrency,
    BigDecimal monthlyBudget,
    String currency,
    Instant createdAt) {}

package com.agentos.mission.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MissionDetailResponse(
    UUID id,
    UUID organizationId,
    UUID teamId,
    String teamName,
    UUID managerAgentId,
    String managerName,
    String title,
    String goal,
    String requiredOutput,
    String status,
    String priority,
    LocalDate deadline,
    BigDecimal budgetLimit,
    String currency,
    String approvalMode,
    String context,
    Instant createdAt) {}

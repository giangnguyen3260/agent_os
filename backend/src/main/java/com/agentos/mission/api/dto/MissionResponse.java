package com.agentos.mission.api.dto;

import com.agentos.mission.domain.MissionPriority;
import com.agentos.mission.domain.MissionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MissionResponse(
    UUID id,
    UUID organizationId,
    UUID teamId,
    UUID managerAgentId,
    String title,
    String goal,
    MissionStatus status,
    MissionPriority priority,
    BigDecimal budgetLimit,
    Instant createdAt) {}

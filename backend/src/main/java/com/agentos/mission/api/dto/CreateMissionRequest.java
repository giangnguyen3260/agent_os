package com.agentos.mission.api.dto;

import com.agentos.mission.domain.MissionPriority;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateMissionRequest(
    UUID teamId,
    UUID managerAgentId,
    @NotBlank String title,
    @NotBlank String goal,
    MissionPriority priority,
    BigDecimal budgetLimit) {}

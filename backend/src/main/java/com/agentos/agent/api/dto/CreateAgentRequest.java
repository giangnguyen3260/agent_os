package com.agentos.agent.api.dto;

import com.agentos.agent.domain.AgentLevel;
import com.agentos.agent.domain.AutonomyLevel;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record CreateAgentRequest(
    UUID teamId,
    @NotBlank String name,
    @NotBlank String role,
    AgentLevel level,
    AutonomyLevel autonomyLevel) {}

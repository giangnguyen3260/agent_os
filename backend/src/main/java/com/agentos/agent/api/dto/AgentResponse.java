package com.agentos.agent.api.dto;

import com.agentos.agent.domain.AgentLevel;
import com.agentos.agent.domain.AgentStatus;
import com.agentos.agent.domain.AutonomyLevel;
import java.time.Instant;
import java.util.UUID;

public record AgentResponse(
    UUID id,
    UUID organizationId,
    UUID teamId,
    String name,
    String role,
    AgentLevel level,
    AgentStatus status,
    AutonomyLevel autonomyLevel,
    Instant createdAt) {}

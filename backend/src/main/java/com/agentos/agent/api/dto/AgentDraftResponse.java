package com.agentos.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentDraftResponse(
    UUID id,
    UUID organizationId,
    UUID teamId,
    String name,
    String role,
    String description,
    String responsibilities,
    String expectedInput,
    String outputFormat,
    String acceptanceCriteria,
    String model,
    String skills,
    String tools,
    String limits,
    String level,
    String status,
    String autonomyLevel,
    Instant createdAt) {}

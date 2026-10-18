package com.agentos.model.application;

import java.util.List;
import java.util.UUID;

public record ModelRequest(
    UUID organizationId,
    UUID missionId,
    UUID taskId,
    UUID executionRunId,
    UUID agentId,
    UUID providerId,
    String model,
    List<ModelMessage> messages,
    Integer maxOutputTokens) {}

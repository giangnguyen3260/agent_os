package com.agentos.tool.application;

import java.util.Map;
import java.util.UUID;

public record ToolActionRequest(
    UUID organizationId,
    UUID missionId,
    UUID taskId,
    UUID executionRunId,
    UUID agentId,
    UUID toolId,
    String capability,
    String actionType,
    Map<String, Object> payload,
    String idempotencyKey) {}

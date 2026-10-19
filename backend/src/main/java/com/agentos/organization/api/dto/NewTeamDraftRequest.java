package com.agentos.organization.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record NewTeamDraftRequest(
    String name,
    String purpose,
    String description,
    UUID managerAgentId,
    List<UUID> agentIds,
    String intake,
    @JsonDeserialize(using = IntegerJsonDeserializer.class) Integer concurrency,
    BigDecimal monthlyBudget) {}

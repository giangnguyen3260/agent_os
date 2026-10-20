package com.agentos.agent.api.dto;

import java.math.BigDecimal;

public record AgentConfigurationResponse(
    AgentDraftResponse agent,
    int revision,
    Integer concurrency,
    Integer maxTokensPerRun,
    BigDecimal monthlyBudget,
    String currency) {}

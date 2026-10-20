package com.agentos.agent.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;

/** Full requested configuration; identity and grants are never writable. */
public record AgentConfigurationRequest(
    @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer expectedRevision,
    @JsonDeserialize(using = StrictTextDeserializer.class) String responsibilities,
    @JsonDeserialize(using = StrictTextDeserializer.class) String expectedInput,
    @JsonDeserialize(using = StrictTextDeserializer.class) String outputFormat,
    @JsonDeserialize(using = StrictTextDeserializer.class) String acceptanceCriteria,
    @JsonDeserialize(using = StrictTextDeserializer.class) String model,
    @JsonDeserialize(using = StrictTextDeserializer.class) String skills,
    @JsonDeserialize(using = StrictTextDeserializer.class) String tools,
    @JsonDeserialize(using = StrictTextDeserializer.class) String limits,
    @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer concurrency,
    @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer maxTokensPerRun,
    @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal monthlyBudget) {}

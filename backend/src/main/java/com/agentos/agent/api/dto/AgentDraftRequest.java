package com.agentos.agent.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.UUID;

public record AgentDraftRequest(
    @JsonDeserialize(using = StrictTextDeserializer.class) String name,
    @JsonDeserialize(using = StrictTextDeserializer.class) String role,
    UUID teamId,
    @JsonDeserialize(using = StrictTextDeserializer.class) String description,
    @JsonDeserialize(using = StrictTextDeserializer.class) String responsibilities,
    @JsonDeserialize(using = StrictTextDeserializer.class) String expectedInput,
    @JsonDeserialize(using = StrictTextDeserializer.class) String outputFormat,
    @JsonDeserialize(using = StrictTextDeserializer.class) String acceptanceCriteria,
    @JsonDeserialize(using = StrictTextDeserializer.class) String model,
    @JsonDeserialize(using = StrictTextDeserializer.class) String skills,
    @JsonDeserialize(using = StrictTextDeserializer.class) String tools,
    @JsonDeserialize(using = StrictTextDeserializer.class) String limits) {}

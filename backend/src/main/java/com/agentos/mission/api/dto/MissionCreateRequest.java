package com.agentos.mission.api.dto;

import com.agentos.agent.api.dto.StrictDecimalDeserializer;
import com.agentos.agent.api.dto.StrictTextDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.util.UUID;

public record MissionCreateRequest(
    @JsonDeserialize(using = StrictTextDeserializer.class) String title,
    @JsonDeserialize(using = StrictTextDeserializer.class) String goal,
    @JsonDeserialize(using = StrictTextDeserializer.class) String requiredOutput,
    UUID managerAgentId,
    @JsonDeserialize(using = StrictTextDeserializer.class) String priority,
    @JsonDeserialize(using = StrictTextDeserializer.class) String deadline,
    @JsonDeserialize(using = StrictDecimalDeserializer.class) BigDecimal budgetLimit,
    @JsonDeserialize(using = StrictTextDeserializer.class) String approvalMode,
    @JsonDeserialize(using = StrictTextDeserializer.class) String context) {}

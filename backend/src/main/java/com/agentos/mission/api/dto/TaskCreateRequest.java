package com.agentos.mission.api.dto;

import com.agentos.agent.api.dto.StrictTextDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.UUID;

public record TaskCreateRequest(
    @JsonDeserialize(using = StrictTextDeserializer.class) String title,
    @JsonDeserialize(using = StrictTextDeserializer.class) String description,
    @JsonDeserialize(using = StrictTextDeserializer.class) String acceptanceCriteria,
    @JsonDeserialize(using = StrictTextDeserializer.class) String priority,
    UUID ownerAgentId,
    List<UUID> dependencyIds,
    @JsonDeserialize(using = StrictLongDeserializer.class) Long expectedIntakeVersion) {
  @JsonAnySetter
  public void rejectUnknown(String name, Object value) {
    throw new IllegalArgumentException("Unknown field");
  }
}

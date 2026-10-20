package com.agentos.agent.api.dto;

import com.agentos.agent.domain.AgentStatus;
import java.util.List;
import java.util.UUID;

public record AgentDirectoryResponse(List<Item> items, int page, int size, long total) {
  public record Item(
      UUID id,
      UUID organizationId,
      String name,
      String role,
      AgentStatus status,
      UUID teamId,
      String teamName,
      int configurationRevision,
      Integer concurrencyLimit) {}
}

package com.agentos.mission.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MissionDirectoryResponse(
    List<Item> items, int page, int size, long total, Team team) {
  public record Team(UUID id, String name, String status) {}

  public record Item(
      UUID id,
      UUID organizationId,
      String title,
      String status,
      String priority,
      UUID teamId,
      String teamName,
      UUID managerAgentId,
      String managerName,
      long totalTasks,
      long completedTasks,
      long pendingTasks,
      Instant createdAt) {}
}

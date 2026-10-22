package com.agentos.governance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalCenterResponse(
    Instant generatedAt, List<Item> items, int page, int size, long total, Counts counts) {
  public record Counts(long pending, long needsInformation, long decided) {}

  public record Item(
      UUID id,
      UUID organizationId,
      UUID teamId,
      String teamName,
      UUID missionId,
      String missionTitle,
      UUID taskId,
      String taskTitle,
      UUID requesterId,
      String requesterName,
      String title,
      String actionType,
      String riskLevel,
      String status,
      Instant createdAt,
      Instant updatedAt) {}
}

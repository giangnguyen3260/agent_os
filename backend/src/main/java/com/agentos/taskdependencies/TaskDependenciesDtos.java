package com.agentos.taskdependencies;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public final class TaskDependenciesDtos {
  private TaskDependenciesDtos() {}

  public record Task(UUID id, String title, String status) {}

  public record Candidate(UUID id, String title, String status, String fingerprint) {}

  public record DesiredTarget(UUID taskId, String fingerprint) {}

  public record Context(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID taskId,
      String checkedAt,
      int revision,
      String fingerprint,
      String teamName,
      String missionTitle,
      String taskTitle,
      String taskStatus,
      List<Task> dependencies,
      boolean canEdit,
      List<String> blockers) {}

  public record Candidates(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID taskId,
      int page,
      int pageSize,
      long total,
      String query,
      List<Candidate> items) {}

  public record Command(
      int expectedRevision, String expectedFingerprint, List<DesiredTarget> dependencies) {}

  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID taskId,
      UUID eventId,
      int revision,
      String committedAt,
      List<Task> before,
      List<Task> after) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ReceiptLookup(Receipt receipt) {}

  public record Result(Receipt receipt, boolean replay) {}
}

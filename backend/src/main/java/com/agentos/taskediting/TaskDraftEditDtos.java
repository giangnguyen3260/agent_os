package com.agentos.taskediting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public final class TaskDraftEditDtos {
  private TaskDraftEditDtos() {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Values(
      String title, String description, String acceptanceCriteria, String priority) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
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
      String taskStatus,
      Values values,
      boolean canEdit,
      List<String> blockers) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Command(
      int expectedRevision,
      String expectedFingerprint,
      String title,
      String description,
      String acceptanceCriteria,
      String priority) {
    Values values() {
      return new Values(title, description, acceptanceCriteria, priority);
    }
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID taskId,
      UUID eventId,
      int revision,
      String committedAt,
      Values before,
      Values after) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ReceiptLookup(Receipt receipt) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Result(Receipt receipt, boolean replay) {}
}

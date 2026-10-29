package com.agentos.taskowner;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public final class TaskOwnerDtos {
  private TaskOwnerDtos() {}
  public record Agent(UUID id, String name, String level, String status) {}
  public record Candidate(UUID id, String name, String level, String status, String fingerprint) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Owner(String state, Agent agent) {}
  public record Context(UUID organizationId, UUID teamId, UUID missionId, UUID taskId,
      String checkedAt, int revision, String fingerprint, String teamName, String missionTitle,
      String taskTitle, String taskStatus, Owner owner, boolean canEdit, List<String> blockers) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Command(int expectedRevision, String expectedFingerprint, UUID ownerAgentId,
      String ownerFingerprint) {}
  public record Candidates(UUID organizationId, UUID teamId, UUID missionId, UUID taskId,
      int page, int pageSize, long total, String query, List<Candidate> items) {}
  public record Receipt(UUID organizationId, UUID teamId, UUID missionId, UUID taskId,
      UUID eventId, int revision, String committedAt, Owner before, Owner after) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ReceiptLookup(Receipt receipt) {}
  public record Result(Receipt receipt, boolean replay) {}
}

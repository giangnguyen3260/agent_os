package com.agentos.teamsettings;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class TeamSettingsDtos {
  private TeamSettingsDtos() {}

  public record State(
      String name,
      String purpose,
      String description,
      String status,
      UUID managerAgentId,
      List<UUID> agentIds,
      String intake,
      Integer concurrency,
      BigDecimal monthlyBudget,
      String currency) {}

  public record Member(
      UUID id,
      String name,
      String level,
      String status,
      boolean canRemove,
      List<String> removalBlockers) {}

  public record Context(
      UUID organizationId,
      UUID teamId,
      String checkedAt,
      int revision,
      String fingerprint,
      State team,
      List<Member> members,
      long memberTotal,
      boolean canEdit,
      boolean canChangeMembership,
      List<String> blockers) {}

  public record Candidate(UUID id, String name, String level, String status) {}

  public record Candidates(
      UUID organizationId,
      UUID teamId,
      String checkedAt,
      List<Candidate> items,
      int page,
      int size,
      long total) {}

  public record Command(
      int expectedRevision,
      String expectedFingerprint,
      String name,
      String purpose,
      String description,
      UUID managerAgentId,
      List<UUID> agentIds,
      String intake,
      int concurrency,
      BigDecimal monthlyBudget,
      boolean acknowledgement) {}

  public record Receipt(
      String operation,
      UUID organizationId,
      UUID teamId,
      UUID idempotencyKey,
      int revision,
      UUID eventId,
      UUID actorId,
      String actorName,
      String recordedAt,
      State before,
      State after) {}

  public record Result(Receipt receipt, boolean replay) {}
}

package com.agentos.missionintake;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

public final class MissionIntakeDtos {
  private MissionIntakeDtos() {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Context(UUID organizationId, UUID teamId, UUID missionId, String checkedAt,
      String teamName, String missionTitle, String teamStatus, String missionStatus,
      boolean intakePaused, String intakeVersion, String fingerprint, boolean canChange, List<String> blockers) {}
  public record Command(String expectedVersion, String expectedFingerprint, boolean paused) {}
  public record Receipt(UUID organizationId, UUID teamId, UUID missionId, UUID eventId,
      String versionBefore, String versionAfter, boolean pausedBefore, boolean pausedAfter, String committedAt) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ReceiptLookup(Receipt receipt) {}
  public record Result(Receipt receipt, boolean replay) {}
}

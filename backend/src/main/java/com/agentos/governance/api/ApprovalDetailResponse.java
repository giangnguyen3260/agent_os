package com.agentos.governance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalDetailResponse(
    Instant generatedAt,
    ApprovalCenterResponse.Item request,
    Snapshot snapshot,
    Eligibility eligibility,
    Decision decision,
    long informationRevision,
    boolean executionAvailable) {
  public record Snapshot(
      UUID actionId,
      long version,
      String payloadSha256,
      String actionType,
      PreviewParameters parameters,
      String target,
      String scope,
      String effectSummary,
      String estimatedCost,
      String currency,
      Instant expiresAt) {}

  public record PreviewParameters(String destination, String resourcePath, String visibility) {}

  public record Eligibility(boolean canApprove, boolean canReject, List<String> blockers) {}

  public record Decision(
      UUID id,
      UUID approvalId,
      String status,
      UUID actorId,
      String actorName,
      String reason,
      long snapshotVersion,
      String snapshotSha256,
      Instant decidedAt) {}

  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = ApprovalDecisionCommandDeserializer.class)
  public record Command(
      Long snapshotVersion,
      String snapshotSha256,
      Boolean acknowledgement,
      String reason,
      Long informationRevision) {
    public Command(
        Long snapshotVersion, String snapshotSha256, Boolean acknowledgement, String reason) {
      this(snapshotVersion, snapshotSha256, acknowledgement, reason, 0L);
    }
  }

  public record Result(Decision decision, boolean replay) {}
}

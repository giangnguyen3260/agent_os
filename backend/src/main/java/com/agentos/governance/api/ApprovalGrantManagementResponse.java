package com.agentos.governance.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalGrantManagementResponse {
  private ApprovalGrantManagementResponse() {}

  public record Recipient(
      UUID accountId, String displayName, boolean enabled, boolean membershipPresent) {}

  public record Selection(
      UUID teamId,
      String teamName,
      String teamStatus,
      String actionType,
      String riskLevel,
      long unexpiredCount) {}

  public record Context(
      UUID organizationId,
      UUID accountId,
      Instant checkedAt,
      int revision,
      Recipient recipient,
      Selection selection,
      boolean canIssue,
      List<String> blockers) {}

  public record RevocationContext(
      UUID organizationId,
      UUID accountId,
      Instant checkedAt,
      int revision,
      Recipient recipient,
      MemberAccessResponse.Grant grant,
      String grantFingerprint,
      long remainingScopeGrants,
      boolean canRevoke,
      List<String> blockers) {}

  public record Result(
      String operation,
      UUID organizationId,
      UUID accountId,
      UUID actorId,
      String actorName,
      UUID idempotencyKey,
      UUID grantId,
      UUID eventId,
      int revision,
      Instant recordedAt,
      Instant expiresAt,
      Instant revokedAt) {}

  public record Item(
      UUID id,
      String operation,
      UUID actorId,
      String actorName,
      String reason,
      Instant recordedAt,
      int revision) {}

  public record History(
      UUID organizationId,
      UUID accountId,
      UUID grantId,
      Instant checkedAt,
      List<Item> items,
      int page,
      int size,
      long total) {}

  @JsonDeserialize(using = ApprovalGrantManagementCommandDeserializer.class)
  public record Command(
      int expectedRevision,
      UUID teamId,
      String actionType,
      String riskLevel,
      Integer durationHours,
      String grantFingerprint,
      String reason,
      boolean acknowledgement) {}
}

package com.agentos.governance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberAccessResponse {
  private MemberAccessResponse() {}

  public record Counts(long unexpired, long expired, long revoked) {}

  public record Member(
      UUID accountId,
      String displayName,
      String email,
      String role,
      boolean enabled,
      boolean isSelf) {}

  public record InventoryItem(
      UUID accountId,
      String displayName,
      String email,
      String role,
      boolean enabled,
      boolean isSelf,
      Counts grantCounts) {}

  public record Inventory(
      UUID organizationId,
      Instant generatedAt,
      List<InventoryItem> items,
      int page,
      int size,
      long total) {}

  public record Grant(
      UUID id,
      UUID organizationId,
      UUID accountId,
      UUID teamId,
      String teamName,
      String actionType,
      String riskLevel,
      Instant expiresAt,
      Instant revokedAt,
      String status) {}

  public record Grants(
      UUID organizationId,
      Instant generatedAt,
      Member member,
      List<Grant> items,
      int page,
      int size,
      long total) {}
}

package com.agentos.governance.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberRoleManagementResponse {
  private MemberRoleManagementResponse() {}

  public record Member(
      UUID accountId, String displayName, boolean enabled, String currentRole, boolean isSelf) {}

  public record Context(
      UUID organizationId,
      UUID accountId,
      Instant checkedAt,
      int revision,
      Member member,
      String selectedRole,
      long enabledAdminCount,
      boolean canChange,
      List<String> blockers) {}

  public record Result(
      String operation,
      UUID organizationId,
      UUID accountId,
      UUID actorId,
      String actorName,
      UUID idempotencyKey,
      UUID eventId,
      int revision,
      Instant recordedAt,
      String beforeRole,
      String afterRole) {}

  public record Item(
      UUID id,
      UUID actorId,
      String actorName,
      String beforeRole,
      String afterRole,
      String reason,
      Instant recordedAt,
      int revision) {}

  public record History(
      UUID organizationId,
      UUID accountId,
      Instant checkedAt,
      List<Item> items,
      int page,
      int size,
      long total) {}

  @JsonDeserialize(using = MemberRoleManagementCommandDeserializer.class)
  public record Command(
      int expectedRevision,
      String expectedCurrentRole,
      String newRole,
      String reason,
      boolean acknowledgement) {}
}

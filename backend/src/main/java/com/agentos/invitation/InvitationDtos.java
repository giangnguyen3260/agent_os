package com.agentos.invitation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InvitationDtos {
  private InvitationDtos() {}

  public record Item(
      UUID id,
      String email,
      String role,
      String status,
      int revision,
      Instant createdAt,
      Instant expiresAt,
      Instant acceptedAt,
      Instant revokedAt,
      String deliveryStatus,
      Instant lastRequestedAt) {}

  public record Page(
      UUID organizationId, Instant checkedAt, List<Item> items, int page, int size, long total) {}

  public record Context(
      UUID organizationId,
      Instant checkedAt,
      Item invitation,
      boolean canResend,
      boolean canRevoke) {}

  public record Receipt(
      String operation,
      UUID organizationId,
      UUID invitationId,
      UUID idempotencyKey,
      int revision,
      UUID eventId,
      UUID actorId,
      String actorName,
      Instant recordedAt,
      String email,
      String role,
      Instant expiresAt) {}

  public record Mutation(Receipt receipt, boolean replay) {}

  public record Inspection(
      String state,
      UUID organizationId,
      String organizationName,
      String emailHint,
      String role,
      Instant expiresAt) {}

  public record Acceptance(
      UUID organizationId, String role, boolean requiresLogin, boolean alreadyMember) {}
}

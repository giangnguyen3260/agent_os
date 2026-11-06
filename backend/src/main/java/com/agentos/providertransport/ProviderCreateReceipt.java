package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Failure;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;
import java.util.UUID;

/** Historical Create configuration only; never projects live health or secret metadata. */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ProviderCreateReceipt(
    UUID organizationId,
    UUID commandId,
    Operation operation,
    UUID connectionId,
    UUID actorId,
    String committedAt,
    Snapshot before,
    Snapshot after,
    UUID checkId) {
  private static final DateTimeFormatter CLOCK =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

  static ProviderCreateReceipt from(Receipt receipt) {
    if (receipt == null
        || receipt.operation() != Operation.CREATE
        || receipt.before() != null
        || !canonical(receipt.org())
        || !canonical(receipt.commandId())
        || !canonical(receipt.connectionId())
        || !canonical(receipt.actorId())) {
      return null;
    }
    var after = receipt.after();
    if (after == null
        || !receipt.org().equals(after.organizationId())
        || !receipt.connectionId().equals(after.id())
        || !"ENABLED".equals(after.state())
        || after.revision() != 1
        || after.credentialRevision() != 1) {
      return null;
    }
    try {
      var validated =
          new Create(after.provider(), after.credentialMode(), after.name(), after.workspaceId());
      if (!validated.name().equals(after.name())) return null;
    } catch (Failure ignored) {
      return null;
    }
    String createdAt = time(after.createdAt());
    String updatedAt = time(after.updatedAt());
    String committedAt = time(receipt.recordedAt());
    if (createdAt == null
        || updatedAt == null
        || !after.createdAt().isEqual(after.updatedAt())
        || committedAt == null
        || receipt.recordedAt().isBefore(after.createdAt())) {
      return null;
    }
    return new ProviderCreateReceipt(
        receipt.org(),
        receipt.commandId(),
        Operation.CREATE,
        receipt.connectionId(),
        receipt.actorId(),
        committedAt,
        null,
        new Snapshot(
            after.organizationId(),
            after.id(),
            after.provider(),
            after.credentialMode(),
            after.name(),
            after.provider() == Provider.ANTHROPIC
                ? new WorkspaceOptions(after.workspaceId())
                : Map.of(),
            after.state(),
            "1",
            "1",
            createdAt,
            updatedAt),
        null);
  }

  private static boolean canonical(UUID id) {
    return id != null && id.variant() == 2 && id.version() >= 1 && id.version() <= 5;
  }

  private static String time(OffsetDateTime value) {
    if (value == null) return null;
    try {
      int year = value.withOffsetSameInstant(ZoneOffset.UTC).getYear();
      return year < 1 || year > 9999 ? null : CLOCK.format(value);
    } catch (DateTimeException ignored) {
      return null;
    }
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record Snapshot(
      UUID organizationId,
      UUID id,
      Provider provider,
      CredentialMode credentialMode,
      String name,
      Object options,
      String state,
      String revision,
      String credentialRevision,
      String createdAt,
      String updatedAt) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record WorkspaceOptions(String workspaceId) {}
}

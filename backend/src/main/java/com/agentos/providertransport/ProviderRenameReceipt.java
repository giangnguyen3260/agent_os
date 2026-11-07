package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.Failure;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providerreceipts.ProviderReceiptService.Configuration;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providertransport.ProviderCreateReceipt.Snapshot;
import com.agentos.providertransport.ProviderCreateReceipt.WorkspaceOptions;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Historical Rename configuration only; no current health or credential metadata reads. */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ProviderRenameReceipt(
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

  static ProviderRenameReceipt from(Receipt receipt) {
    if (receipt == null
        || receipt.operation() != Operation.RENAME
        || !canonical(receipt.org())
        || !canonical(receipt.commandId())
        || !canonical(receipt.connectionId())
        || !canonical(receipt.actorId())) return null;
    var before = receipt.before();
    var after = receipt.after();
    if (!valid(before, receipt)
        || !valid(after, receipt)
        || before.revision() == Long.MAX_VALUE
        || after.revision() != before.revision() + 1
        || before.credentialRevision() > before.revision()
        || before.credentialRevision() != after.credentialRevision()
        || before.provider() != after.provider()
        || before.credentialMode() != after.credentialMode()
        || !Objects.equals(before.workspaceId(), after.workspaceId())
        || !before.state().equals(after.state())
        || before.name().equals(after.name())
        || !before.createdAt().isEqual(after.createdAt())
        || before.updatedAt().isBefore(before.createdAt())
        || after.updatedAt().isBefore(before.updatedAt())
        || time(receipt.recordedAt()) == null
        || receipt.recordedAt().isBefore(after.updatedAt())) return null;
    return new ProviderRenameReceipt(
        receipt.org(),
        receipt.commandId(),
        Operation.RENAME,
        receipt.connectionId(),
        receipt.actorId(),
        time(receipt.recordedAt()),
        snapshot(before),
        snapshot(after),
        null);
  }

  private static boolean valid(Configuration value, Receipt receipt) {
    if (value == null
        || !receipt.org().equals(value.organizationId())
        || !receipt.connectionId().equals(value.id())
        || value.revision() < 1
        || value.credentialRevision() < 1
        || !("ENABLED".equals(value.state()) || "DISABLED".equals(value.state()))
        || time(value.createdAt()) == null
        || time(value.updatedAt()) == null) return false;
    try {
      var validated =
          new Create(value.provider(), value.credentialMode(), value.name(), value.workspaceId());
      return validated.name().equals(value.name());
    } catch (Failure ignored) {
      return false;
    }
  }

  private static Snapshot snapshot(Configuration value) {
    return new Snapshot(
        value.organizationId(),
        value.id(),
        value.provider(),
        value.credentialMode(),
        value.name(),
        value.provider() == Provider.ANTHROPIC
            ? new WorkspaceOptions(value.workspaceId())
            : Map.of(),
        value.state(),
        Long.toString(value.revision()),
        Long.toString(value.credentialRevision()),
        time(value.createdAt()),
        time(value.updatedAt()));
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
}

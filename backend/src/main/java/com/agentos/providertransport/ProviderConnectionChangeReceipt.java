package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providertransport.ProviderCreateReceipt.Snapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * Historical connection change configuration only; no current health or credential metadata reads.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ProviderConnectionChangeReceipt(
    UUID organizationId,
    UUID commandId,
    Operation operation,
    UUID connectionId,
    UUID actorId,
    String committedAt,
    Snapshot before,
    Snapshot after,
    UUID checkId) {
  static ProviderConnectionChangeReceipt from(Receipt receipt, Operation operation) {
    if (!ProviderHistoricalConfiguration.validTransition(receipt)
        || receipt.operation() != operation
        || !validState(receipt, operation)
        || !receipt.before().name().equals(receipt.after().name())) return null;
    return new ProviderConnectionChangeReceipt(
        receipt.org(),
        receipt.commandId(),
        operation,
        receipt.connectionId(),
        receipt.actorId(),
        ProviderHistoricalConfiguration.time(receipt.recordedAt()),
        ProviderHistoricalConfiguration.snapshot(receipt.before()),
        ProviderHistoricalConfiguration.snapshot(receipt.after()),
        null);
  }

  private static boolean validState(Receipt receipt, Operation operation) {
    String before = receipt.before().state();
    String after = receipt.after().state();
    return switch (operation) {
      case ENABLE -> "DISABLED".equals(before) && "ENABLED".equals(after);
      case DISABLE -> "ENABLED".equals(before) && "DISABLED".equals(after);
      case ROTATE -> before.equals(after);
      default -> false;
    };
  }
}

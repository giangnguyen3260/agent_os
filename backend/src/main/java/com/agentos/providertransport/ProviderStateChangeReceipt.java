package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providertransport.ProviderCreateReceipt.Snapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Historical state change configuration only; no current health or credential metadata reads. */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ProviderStateChangeReceipt(
    UUID organizationId,
    UUID commandId,
    Operation operation,
    UUID connectionId,
    UUID actorId,
    String committedAt,
    Snapshot before,
    Snapshot after,
    UUID checkId) {
  static ProviderStateChangeReceipt from(Receipt receipt, Operation operation) {
    boolean enabling = operation == Operation.ENABLE;
    if (!ProviderHistoricalConfiguration.validTransition(receipt)
        || receipt.operation() != operation
        || !(enabling ? "DISABLED" : "ENABLED").equals(receipt.before().state())
        || !(enabling ? "ENABLED" : "DISABLED").equals(receipt.after().state())
        || !receipt.before().name().equals(receipt.after().name())) return null;
    return new ProviderStateChangeReceipt(
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
}

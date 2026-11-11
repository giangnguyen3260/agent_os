package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providertransport.ProviderCreateReceipt.Snapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Historical Disable configuration only; no current health or credential metadata reads. */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ProviderDisableReceipt(
    UUID organizationId,
    UUID commandId,
    Operation operation,
    UUID connectionId,
    UUID actorId,
    String committedAt,
    Snapshot before,
    Snapshot after,
    UUID checkId) {
  static ProviderDisableReceipt from(Receipt receipt) {
    if (!ProviderHistoricalConfiguration.validTransition(receipt)
        || receipt.operation() != Operation.DISABLE
        || !"ENABLED".equals(receipt.before().state())
        || !"DISABLED".equals(receipt.after().state())
        || !receipt.before().name().equals(receipt.after().name())) return null;
    return new ProviderDisableReceipt(
        receipt.org(),
        receipt.commandId(),
        Operation.DISABLE,
        receipt.connectionId(),
        receipt.actorId(),
        ProviderHistoricalConfiguration.time(receipt.recordedAt()),
        ProviderHistoricalConfiguration.snapshot(receipt.before()),
        ProviderHistoricalConfiguration.snapshot(receipt.after()),
        null);
  }
}

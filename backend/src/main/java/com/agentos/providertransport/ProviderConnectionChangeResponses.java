package com.agentos.providertransport;

import static com.agentos.providertransport.ProviderCreateResponses.error;

import com.agentos.common.api.ApiResponse;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** State change projections for a response whose Retry-After header has not been set. */
final class ProviderConnectionChangeResponses {
  private ProviderConnectionChangeResponses() {}

  /** Writes a confirmed state change receipt or its safe public error response. */
  static void writeOutcome(
      ObjectMapper json, HttpServletResponse response, WriteOutcome outcome, Operation operation)
      throws IOException {
    if (json == null || response == null) {
      throw new IllegalArgumentException("Provider response inputs required");
    }
    if (outcome != null && outcome.state() != WriteState.COMMITTED) {
      error(json, response, outcome);
      return;
    }
    var result = outcome == null ? null : outcome.result();
    var receipt =
        result == null ? null : ProviderConnectionChangeReceipt.from(result.receipt(), operation);
    if (receipt == null) {
      error(json, response, new Failure(Code.RECEIPT_UNAVAILABLE));
      return;
    }
    success(json, response, result.replayed() ? 200 : 201, receipt);
  }

  /** Writes only the caller's confirmed historical state change receipt, or a confirmed absence. */
  static void writeLookup(
      ObjectMapper json, HttpServletResponse response, LookupOutcome outcome, Operation operation)
      throws IOException {
    if (json == null || response == null) {
      throw new IllegalArgumentException("Provider response inputs required");
    }
    if (outcome == null) {
      error(json, response, new Failure(Code.LOOKUP_UNAVAILABLE));
      return;
    }
    if (outcome.failure() != null) {
      error(json, response, outcome.failure());
      return;
    }
    var receipt = ProviderConnectionChangeReceipt.from(outcome.receipt(), operation);
    if (outcome.state() == LookupState.FOUND && receipt == null) {
      error(json, response, new Failure(Code.RECEIPT_UNAVAILABLE));
      return;
    }
    success(json, response, 200, new LookupData(receipt));
  }

  private static void success(
      ObjectMapper json, HttpServletResponse response, int status, Object data) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    json.writeValue(response.getOutputStream(), ApiResponse.ok(data));
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private record LookupData(ProviderConnectionChangeReceipt receipt) {}
}

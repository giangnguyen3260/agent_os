package com.agentos.providertransport;

import static com.agentos.providertransport.ProviderCreateResponses.error;

import com.agentos.common.api.ApiResponse;
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

/** Disable projections for a response whose Retry-After header has not been set. */
public final class ProviderDisableResponses {
  private ProviderDisableResponses() {}

  /** Writes a confirmed Disable receipt or its safe public error response. */
  public static void writeOutcome(
      ObjectMapper json, HttpServletResponse response, WriteOutcome outcome) throws IOException {
    if (json == null || response == null) {
      throw new IllegalArgumentException("Provider response inputs required");
    }
    if (outcome != null && outcome.state() != WriteState.COMMITTED) {
      error(json, response, outcome);
      return;
    }
    var result = outcome == null ? null : outcome.result();
    var receipt = result == null ? null : ProviderDisableReceipt.from(result.receipt());
    if (receipt == null) {
      error(json, response, new Failure(Code.RECEIPT_UNAVAILABLE));
      return;
    }
    success(json, response, result.replayed() ? 200 : 201, receipt);
  }

  /** Writes only the caller's confirmed historical Disable receipt, or a confirmed absence. */
  public static void writeLookup(
      ObjectMapper json, HttpServletResponse response, LookupOutcome outcome) throws IOException {
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
    var receipt = ProviderDisableReceipt.from(outcome.receipt());
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
  private record LookupData(ProviderDisableReceipt receipt) {}
}

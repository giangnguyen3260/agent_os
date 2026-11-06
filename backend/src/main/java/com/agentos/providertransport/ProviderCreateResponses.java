package com.agentos.providertransport;

import com.agentos.common.api.ApiErrorResponse;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Error projection for a response whose Retry-After header has not been set. */
public final class ProviderCreateResponses {
  private ProviderCreateResponses() {}

  /**
   * A committed result requires a separately reviewed historical receipt projection. Reject it
   * before touching the response instead of misrepresenting success as a failed or retryable
   * command.
   */
  public static void error(ObjectMapper json, HttpServletResponse response, WriteOutcome outcome)
      throws IOException {
    if (outcome == null || outcome.state() == WriteState.COMMITTED) {
      throw new IllegalArgumentException("Provider error outcome required");
    }
    error(json, response, outcome.failure());
  }

  public static void error(ObjectMapper json, HttpServletResponse response, Failure failure)
      throws IOException {
    if (json == null || response == null || failure == null) {
      throw new IllegalArgumentException("Provider error response inputs required");
    }
    PublicFailure projected = project(failure);
    response.setStatus(projected.status());
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    if (projected.retryAfter() > 0) {
      response.setHeader("Retry-After", Integer.toString(projected.retryAfter()));
    }
    json.writeValue(
        response.getOutputStream(), ApiErrorResponse.of(projected.code(), projected.message()));
  }

  private static PublicFailure project(Failure failure) {
    return switch (failure.code()) {
      case UNAUTHORIZED -> new PublicFailure(401, "UNAUTHORIZED", "Authentication required", 0);
      case FORBIDDEN -> new PublicFailure(403, "FORBIDDEN", "Request denied", 0);
      case INVALID_COMMAND, INVALID_KEY ->
          new PublicFailure(400, "BAD_REQUEST", "Invalid provider settings request", 0);
      case NOT_FOUND -> new PublicFailure(404, "NOT_FOUND", "Provider connection not found", 0);
      case IDEMPOTENCY_CONFLICT -> conflict("IDEMPOTENCY_CONFLICT");
      case REVISION_CONFLICT -> conflict("REVISION_CONFLICT");
      case NO_CHANGE -> conflict("NO_CHANGE");
      case REVISION_EXHAUSTED -> conflict("REVISION_EXHAUSTED");
      case PROVIDER_DISABLED -> conflict("PROVIDER_DISABLED");
      case CONNECTION_LIMIT -> conflict("CONNECTION_LIMIT");
      case RATE_LIMITED ->
          failure.retryAfter() >= 1
              ? new PublicFailure(429, "RATE_LIMITED", "Too many requests", failure.retryAfter())
              : unavailable();
      case INVALID_PREPARATION,
              CONNECTION_CONFLICT,
              PREPARATION_REQUIRED,
              RECEIPT_UNAVAILABLE,
              COMMAND_UNAVAILABLE,
              COMMAND_OUTCOME_UNKNOWN,
              LOOKUP_UNAVAILABLE ->
          unavailable();
    };
  }

  private static PublicFailure conflict(String code) {
    return new PublicFailure(
        409, code, "Provider connection command conflicts with current state", 0);
  }

  private static PublicFailure unavailable() {
    return new PublicFailure(
        503, "PROVIDER_CONNECTION_UNAVAILABLE", "Provider settings are unavailable", 0);
  }

  private record PublicFailure(int status, String code, String message, int retryAfter) {}
}

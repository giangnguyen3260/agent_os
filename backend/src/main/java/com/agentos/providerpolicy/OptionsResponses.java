package com.agentos.providerpolicy;

import com.agentos.common.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class OptionsResponses {
  private OptionsResponses() {}

  static String message(int status) {
    return switch (status) {
      case 400 -> "Invalid provider settings request";
      case 401 -> "Authentication required";
      case 403 -> "Request denied";
      case 405 -> "Method not allowed";
      case 429 -> "Too many requests";
      default -> "Provider settings are unavailable";
    };
  }

  static void error(
      ObjectMapper json,
      HttpServletRequest request,
      HttpServletResponse response,
      PolicyFailure failure)
      throws IOException {
    response.setStatus(failure.status());
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    if (failure.status() == 405) response.setHeader("Allow", "GET");
    if (failure.status() == 429)
      response.setHeader(
          "Retry-After", Integer.toString(Math.max(1, Math.min(60, failure.retryAfter()))));
    if (!request.getMethod().equals("HEAD"))
      json.writeValue(
          response.getOutputStream(),
          ApiErrorResponse.of(failure.code(), message(failure.status())));
  }
}

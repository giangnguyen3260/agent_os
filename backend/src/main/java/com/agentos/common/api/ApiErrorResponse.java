package com.agentos.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ApiErrorResponse(ApiError error, ApiMeta meta) {

  public static ApiErrorResponse of(String code, String message) {
    return new ApiErrorResponse(new ApiError(code, message, Map.of()), ApiMeta.current());
  }

  public record ApiError(
      String code,
      String message,
      @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, Object> details) {}
}

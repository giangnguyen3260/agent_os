package com.agentos.common.api;

public record ApiResponse<T>(T data, ApiMeta meta) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(data, ApiMeta.current());
  }
}

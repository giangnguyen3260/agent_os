package com.agentos.common.api;

import java.util.UUID;

public record ApiMeta(String requestId) {

  public static ApiMeta current() {
    return new ApiMeta(UUID.randomUUID().toString());
  }
}

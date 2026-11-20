package com.agentos.orchestration.application;

import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;

/** Managed credential access uses its dedicated writer, never the restricted list reader. */
@com.fasterxml.jackson.annotation.JsonIgnoreType
public record OpenAiExecutionRuntime(
    CredentialBoundary credentials, ProviderConnectionService connections) {
  public OpenAiExecutionRuntime {
    java.util.Objects.requireNonNull(credentials);
    java.util.Objects.requireNonNull(connections);
  }

  @Override
  public String toString() {
    return "OpenAiExecutionRuntime[redacted]";
  }
}

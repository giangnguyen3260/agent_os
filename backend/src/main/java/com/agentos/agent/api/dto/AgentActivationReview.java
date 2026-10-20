package com.agentos.agent.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentActivationReview(
    UUID organizationId,
    UUID agentId,
    String name,
    String status,
    int revision,
    Instant checkedAt,
    boolean activationAllowed,
    List<Check> checks) {
  public enum Status {
    PASS,
    BLOCKED,
    UNAVAILABLE
  }

  public record Check(String code, String label, Status status, String message) {}
}

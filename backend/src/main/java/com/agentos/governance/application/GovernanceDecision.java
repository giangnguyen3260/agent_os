package com.agentos.governance.application;

public record GovernanceDecision(Decision decision, String reason) {

  public enum Decision {
    ALLOW,
    APPROVAL_REQUIRED,
    DENY
  }
}

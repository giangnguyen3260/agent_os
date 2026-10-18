package com.agentos.governance.application;

public interface GovernanceEngine {
  GovernanceDecision evaluate(GovernanceRequest request);
}

package com.agentos.governance.infrastructure;

import com.agentos.governance.application.GovernanceDecision;
import com.agentos.governance.application.GovernanceEngine;
import com.agentos.governance.application.GovernanceRequest;
import org.springframework.stereotype.Component;

@Component
public class DefaultGovernanceEngine implements GovernanceEngine {

  @Override
  public GovernanceDecision evaluate(GovernanceRequest request) {
    return new GovernanceDecision(GovernanceDecision.Decision.ALLOW, "Default MVP policy");
  }
}

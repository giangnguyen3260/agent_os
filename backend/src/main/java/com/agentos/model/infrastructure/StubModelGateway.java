package com.agentos.model.infrastructure;

import com.agentos.agent.application.AgentModelGrantService;
import com.agentos.model.application.ModelGateway;
import com.agentos.model.application.ModelRequest;
import com.agentos.model.application.ModelResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StubModelGateway implements ModelGateway {
  private final AgentModelGrantService modelGrants;
  private final boolean discoveryEnabled;

  public StubModelGateway(
      AgentModelGrantService modelGrants,
      @Value("${agentos.provider-model-discovery.http-enabled:false}") boolean discoveryEnabled) {
    this.modelGrants = modelGrants;
    this.discoveryEnabled = discoveryEnabled;
  }

  @Override
  public ModelResponse execute(ModelRequest request) {
    if (!discoveryEnabled
        || request == null
        || !modelGrants.hasCurrentGrant(
            request.organizationId(), request.agentId(), request.providerId(), request.model())) {
      throw new IllegalStateException("Model execution requires a current grant for this binding.");
    }
    throw new IllegalStateException(
        "Model execution is blocked until runtime claim, plan and budget checks are available.");
  }
}

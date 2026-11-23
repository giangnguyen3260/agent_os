package com.agentos.tool.infrastructure;

import com.agentos.tool.application.ToolActionRequest;
import com.agentos.tool.application.ToolGateway;
import com.agentos.tool.application.ToolResult;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "agentos.tools.text-metrics.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class StubToolGateway implements ToolGateway {

  @Override
  public ToolResult execute(ToolActionRequest request) {
    throw new IllegalStateException(
        "Tool execution is blocked until scoped authorization is available.");
  }
}

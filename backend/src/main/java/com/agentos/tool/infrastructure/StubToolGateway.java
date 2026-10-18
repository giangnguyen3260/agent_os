package com.agentos.tool.infrastructure;

import com.agentos.tool.application.ToolActionRequest;
import com.agentos.tool.application.ToolGateway;
import com.agentos.tool.application.ToolResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StubToolGateway implements ToolGateway {

  @Override
  public ToolResult execute(ToolActionRequest request) {
    return new ToolResult("STUBBED", Map.of());
  }
}

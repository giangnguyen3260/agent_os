package com.agentos.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.tool.application.ToolActionRequest;
import com.agentos.tool.application.ToolGateway;
import com.agentos.tool.infrastructure.StubToolGateway;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubToolGatewayTest {
  private final ToolGateway gateway = new StubToolGateway();

  @Test
  void completeRequestCannotAuthorizeToolExecution() {
    var request =
        new ToolActionRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "read-document",
            "read",
            Map.of("documentId", "private-document"),
            UUID.randomUUID().toString());

    assertThatThrownBy(() -> gateway.execute(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Tool execution is blocked until scoped authorization is available.");
  }
}

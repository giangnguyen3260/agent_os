package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.OpenAiExecutionFlow;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.execution.openai.enabled", havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}/execution-runs/{runId}/start")
class OpenAiExecutionController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = OpenAiStartCommandDeserializer.class)
  record Command(String expectedRevision, UUID idempotencyKey, String fingerprint) {}

  private final OpenAiExecutionFlow flow;

  OpenAiExecutionController(OpenAiExecutionFlow flow) {
    this.flow = flow;
  }

  @GetMapping
  ApiResponse<OpenAiExecutionFlow.Preflight> preflight(
      @PathVariable UUID organizationId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ApiResponse.ok(flow.preflight(organizationId, runId, actor));
  }

  @PostMapping
  ApiResponse<OpenAiExecutionFlow.Started> start(
      @PathVariable UUID organizationId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody Command command) {
    if (command == null
        || command.expectedRevision() == null
        || !command.expectedRevision().matches("0|[1-9][0-9]{0,18}")
        || command.idempotencyKey() == null
        || command.fingerprint() == null
        || !command.fingerprint().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid Start command");
    long revision;
    try {
      revision = Long.parseLong(command.expectedRevision());
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("Invalid Run revision");
    }
    return ApiResponse.ok(
        flow.start(
            organizationId,
            runId,
            revision,
            command.idempotencyKey(),
            command.fingerprint(),
            actor));
  }
}

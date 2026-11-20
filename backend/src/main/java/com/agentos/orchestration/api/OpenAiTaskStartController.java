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
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks/{taskId}/start")
class OpenAiTaskStartController {
  private final OpenAiExecutionFlow flow;

  OpenAiTaskStartController(OpenAiExecutionFlow flow) {
    this.flow = flow;
  }

  @GetMapping
  ApiResponse<OpenAiExecutionFlow.TaskPreflight> preflight(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @PathVariable UUID taskId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ApiResponse.ok(flow.taskPreflight(organizationId, teamId, missionId, taskId, actor));
  }

  @PostMapping
  ApiResponse<OpenAiExecutionFlow.Started> start(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @PathVariable UUID taskId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody OpenAiExecutionController.Command command) {
    if (command == null
        || !"0".equals(command.expectedRevision())
        || command.idempotencyKey() == null
        || command.fingerprint() == null
        || !command.fingerprint().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid single-task Start");
    return ApiResponse.ok(
        flow.startTask(
            organizationId,
            teamId,
            missionId,
            taskId,
            command.idempotencyKey(),
            command.fingerprint(),
            actor));
  }
}

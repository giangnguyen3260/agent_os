package com.agentos.agent.api;

import com.agentos.agent.api.dto.AgentActivationRequest;
import com.agentos.agent.api.dto.AgentActivationReview;
import com.agentos.agent.application.AgentActivationService;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiErrorResponse.ApiError;
import com.agentos.common.api.ApiMeta;
import com.agentos.common.api.ApiResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/agents/{agentId}")
public class AgentActivationController {
  private final AgentActivationService activations;

  public AgentActivationController(AgentActivationService activations) {
    this.activations = activations;
  }

  @GetMapping("/activation-review")
  public ApiResponse<AgentActivationReview> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(activations.get(organizationId, human, agentId));
  }

  @PostMapping("/activation")
  public void activate(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestBody AgentActivationRequest request) {
    activations.activate(organizationId, human, agentId, request);
  }

  @ExceptionHandler(AgentActivationService.Blocked.class)
  public ResponseEntity<ApiErrorResponse> blocked(AgentActivationService.Blocked ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ApiErrorResponse(
                new ApiError("ACTIVATION_BLOCKED", ex.getMessage(), Map.of("review", ex.review())),
                ApiMeta.current()));
  }
}

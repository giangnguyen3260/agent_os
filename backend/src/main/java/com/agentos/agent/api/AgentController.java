package com.agentos.agent.api;

import com.agentos.agent.api.dto.AgentDraftRequest;
import com.agentos.agent.api.dto.AgentDraftResponse;
import com.agentos.agent.application.AgentDraftService;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/agents")
public class AgentController {
  private final AgentDraftService drafts;

  public AgentController(AgentDraftService drafts) {
    this.drafts = drafts;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AgentDraftResponse>> create(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody AgentDraftRequest request) {
    var created = drafts.create(organizationId, human, key, request);
    return ResponseEntity.status(created.replay() ? 200 : 201)
        .body(ApiResponse.ok(created.response()));
  }

  @GetMapping
  public ApiResponse<AgentDraftService.Page<AgentDraftResponse>> list(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query) {
    return ApiResponse.ok(drafts.list(organizationId, human, page, query));
  }

  @GetMapping("/eligible-teams")
  public ApiResponse<AgentDraftService.Page<AgentDraftService.Candidate>> eligible(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query) {
    return ApiResponse.ok(drafts.eligible(organizationId, human, page, query));
  }

  @GetMapping("/{agentId}")
  public ApiResponse<AgentDraftResponse> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(drafts.get(organizationId, human, agentId));
  }
}

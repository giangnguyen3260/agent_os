package com.agentos.agent.api;

import com.agentos.agent.api.dto.AgentResponse;
import com.agentos.agent.api.dto.CreateAgentRequest;
import com.agentos.agent.application.AgentService;
import com.agentos.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/agents")
public class AgentController {

  private final AgentService agentService;

  public AgentController(AgentService agentService) {
    this.agentService = agentService;
  }

  @PostMapping
  public ApiResponse<AgentResponse> create(
      @PathVariable UUID organizationId, @Valid @RequestBody CreateAgentRequest request) {
    return ApiResponse.ok(agentService.create(organizationId, request));
  }

  @GetMapping
  public ApiResponse<List<AgentResponse>> list(@PathVariable UUID organizationId) {
    return ApiResponse.ok(agentService.list(organizationId));
  }

  @GetMapping("/{agentId}")
  public ApiResponse<AgentResponse> get(
      @PathVariable UUID organizationId, @PathVariable UUID agentId) {
    return ApiResponse.ok(agentService.get(organizationId, agentId));
  }
}

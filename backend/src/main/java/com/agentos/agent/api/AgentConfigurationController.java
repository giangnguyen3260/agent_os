package com.agentos.agent.api;

import com.agentos.agent.api.dto.AgentConfigurationRequest;
import com.agentos.agent.api.dto.AgentConfigurationResponse;
import com.agentos.agent.application.AgentConfigurationService;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/agents/{agentId}/configuration")
public class AgentConfigurationController {
  private final AgentConfigurationService configurations;

  public AgentConfigurationController(AgentConfigurationService configurations) {
    this.configurations = configurations;
  }

  @GetMapping
  public ApiResponse<AgentConfigurationResponse> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(configurations.get(organizationId, human, agentId));
  }

  @PutMapping
  public ApiResponse<AgentConfigurationResponse> put(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody AgentConfigurationRequest request) {
    return ApiResponse.ok(configurations.put(organizationId, human, agentId, key, request));
  }
}

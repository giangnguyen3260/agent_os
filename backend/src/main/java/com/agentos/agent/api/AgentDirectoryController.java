package com.agentos.agent.api;

import com.agentos.agent.api.dto.AgentDirectoryResponse;
import com.agentos.agent.application.AgentDirectoryService;
import com.agentos.agent.domain.AgentStatus;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentDirectoryController {
  private final AgentDirectoryService directory;

  public AgentDirectoryController(AgentDirectoryService directory) {
    this.directory = directory;
  }

  @GetMapping("/api/v1/organizations/{organizationId}/agents/directory")
  public ApiResponse<AgentDirectoryResponse> list(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) AgentStatus status,
      @RequestParam(required = false) UUID teamId) {
    return ApiResponse.ok(directory.list(organizationId, human, page, query, status, teamId));
  }
}

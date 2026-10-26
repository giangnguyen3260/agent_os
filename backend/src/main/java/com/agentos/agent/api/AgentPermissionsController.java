package com.agentos.agent.api;

import com.agentos.agent.api.dto.AgentPermissionsResponse;
import com.agentos.agent.application.AgentPermissionsService;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/agents/{agentId}/permissions")
public class AgentPermissionsController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private final AgentPermissionsService permissions;

  public AgentPermissionsController(AgentPermissionsService permissions) {
    this.permissions = permissions;
  }

  private UUID uuid(String value) {
    if (!UUID_PATTERN.matcher(value).matches())
      throw new IllegalArgumentException("Invalid permissions UUID");
    return UUID.fromString(value);
  }

  @GetMapping
  public ApiResponse<AgentPermissionsResponse> get(
      @PathVariable String organizationId,
      @PathVariable String agentId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam MultiValueMap<String, String> params) {
    if (!params.isEmpty()) throw new IllegalArgumentException("Invalid permissions parameters");
    return ApiResponse.ok(permissions.get(uuid(organizationId), human, uuid(agentId)));
  }
}

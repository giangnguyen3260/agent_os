package com.agentos.organization.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.organization.application.TeamWorkspaceService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/workspace")
public class TeamWorkspaceController {
  private final TeamWorkspaceService workspace;

  public TeamWorkspaceController(TeamWorkspaceService workspace) {
    this.workspace = workspace;
  }

  @GetMapping
  public ApiResponse<TeamWorkspaceService.Workspace> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(workspace.get(organizationId, teamId, human));
  }

  @GetMapping("/tasks")
  public ApiResponse<TeamWorkspaceService.Page<TeamWorkspaceService.Task>> tasks(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam String lane,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(workspace.tasks(organizationId, teamId, human, lane, page));
  }

  @GetMapping("/activity")
  public ApiResponse<TeamWorkspaceService.Page<TeamWorkspaceService.Activity>> activity(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(workspace.activity(organizationId, teamId, human, page));
  }
}

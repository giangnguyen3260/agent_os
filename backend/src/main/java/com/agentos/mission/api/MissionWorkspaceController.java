package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.application.MissionWorkspaceService;
import com.agentos.mission.application.MissionWorkspaceService.Activity;
import com.agentos.mission.application.MissionWorkspaceService.Page;
import com.agentos.mission.application.MissionWorkspaceService.Status;
import com.agentos.mission.application.MissionWorkspaceService.Task;
import com.agentos.mission.application.MissionWorkspaceService.Workspace;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/workspace")
public class MissionWorkspaceController {
  private final MissionWorkspaceService service;

  public MissionWorkspaceController(MissionWorkspaceService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Workspace> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(service.get(organizationId, teamId, missionId, human));
  }

  @GetMapping("/tasks")
  public ApiResponse<Page<Task>> tasks(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "ALL") Status status) {
    return ApiResponse.ok(service.tasks(organizationId, teamId, missionId, human, page, status));
  }

  @GetMapping("/activity")
  public ApiResponse<Page<Activity>> activity(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(service.activity(organizationId, teamId, missionId, human, page));
  }
}

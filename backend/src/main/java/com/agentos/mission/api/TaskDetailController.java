package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.application.TaskDetailService;
import com.agentos.mission.application.TaskDetailService.Detail;
import com.agentos.mission.application.TaskDetailService.Page;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks/{taskId}")
public class TaskDetailController {
  private final TaskDetailService service;

  public TaskDetailController(TaskDetailService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Detail> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @PathVariable UUID taskId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(service.get(organizationId, teamId, missionId, taskId, human));
  }

  @GetMapping("/activity")
  public ApiResponse<Page> activity(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @PathVariable UUID taskId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(service.activity(organizationId, teamId, missionId, taskId, human, page));
  }
}

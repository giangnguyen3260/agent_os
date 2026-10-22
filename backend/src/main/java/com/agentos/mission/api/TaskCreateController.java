package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.api.dto.TaskCreateRequest;
import com.agentos.mission.application.TaskCreateService;
import com.agentos.mission.application.TaskDetailService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks")
public class TaskCreateController {
  private final TaskCreateService tasks;

  public TaskCreateController(TaskCreateService tasks) {
    this.tasks = tasks;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TaskDetailService.Detail>> create(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody TaskCreateRequest request) {
    var result = tasks.create(organizationId, teamId, missionId, human, key, request);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.response()));
  }

  @GetMapping("/creation-context")
  public ApiResponse<TaskCreateService.Context> context(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(tasks.context(organizationId, teamId, missionId, human));
  }

  @GetMapping("/eligible-owners")
  public ApiResponse<TaskCreateService.Candidates<TaskCreateService.Owner>> owners(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query) {
    return ApiResponse.ok(tasks.owners(organizationId, teamId, missionId, human, page, query));
  }

  @GetMapping("/eligible-dependencies")
  public ApiResponse<TaskCreateService.Candidates<TaskCreateService.Dependency>> dependencies(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query) {
    return ApiResponse.ok(
        tasks.dependencies(organizationId, teamId, missionId, human, page, query));
  }
}

package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.ExecutionReviewService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskExecutionReviewController {
  private final ExecutionReviewService reviews;

  public TaskExecutionReviewController(ExecutionReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping(
      "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks/{taskId}/execution-review")
  public ApiResponse<ExecutionReviewService.TaskReview> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @PathVariable UUID taskId,
      @AuthenticationPrincipal HumanIdentity reviewer) {
    return ApiResponse.ok(reviews.forTask(organizationId, teamId, missionId, taskId, reviewer));
  }
}

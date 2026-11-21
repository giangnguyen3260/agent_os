package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.application.MissionPlanReviewService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.mission-plan-review.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/plan-review")
public class MissionPlanReviewController {
  private final MissionPlanReviewService service;
  private final org.springframework.core.env.Environment environment;

  public MissionPlanReviewController(
      MissionPlanReviewService service, org.springframework.core.env.Environment environment) {
    this.service = service;
    this.environment = environment;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<MissionPlanReviewService.Review>> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header(
            "X-AgentOS-Plan-Acceptance",
            Boolean.toString(
                environment.getProperty(
                    "agentos.mission-plan-acceptance.enabled", Boolean.class, false)))
        .header(
            "X-AgentOS-Graph-Admission",
            Boolean.toString(
                environment.getProperty(
                    "agentos.mission-graph-admission.enabled", Boolean.class, false)))
        .body(ApiResponse.ok(service.current(organizationId, teamId, missionId, actor)));
  }
}

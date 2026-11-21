package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.MissionGraphCompletionService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.mission-graph-completion.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/graph-completion")
public class MissionGraphCompletionController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = MissionGraphCompletionCommandDeserializer.class)
  public record Body(
      UUID idempotencyKey,
      MissionGraphCompletionService.Action action,
      String expectedFingerprint) {}

  private final MissionGraphCompletionService service;

  public MissionGraphCompletionController(MissionGraphCompletionService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<MissionGraphCompletionService.Snapshot>> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.current(organizationId, teamId, missionId, actor)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MissionGraphCompletionService.Receipt>> apply(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody Body command) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.apply(
                    organizationId,
                    teamId,
                    missionId,
                    actor,
                    command == null ? null : command.idempotencyKey(),
                    command == null ? null : command.action(),
                    command == null ? null : command.expectedFingerprint())));
  }

  @GetMapping("/outputs/{runId}")
  public ResponseEntity<ApiResponse<MissionGraphCompletionService.Content>> output(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal HumanIdentity actor,
      @org.springframework.web.bind.annotation.RequestParam UUID attemptId,
      @org.springframework.web.bind.annotation.RequestParam String outputSha256) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.output(
                    organizationId, teamId, missionId, actor, runId, attemptId, outputSha256)));
  }

  @ExceptionHandler({
    MissionGraphCompletionService.Conflict.class,
    com.agentos.orchestration.application.MissionGraphAdmissionService.Conflict.class,
    com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class
  })
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "MISSION_GRAPH_COMPLETION_CONFLICT",
                "Mission completion conditions changed; reload current Mission."));
  }
}

package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.MissionGraphAdmissionService;
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
@ConditionalOnProperty(name = "agentos.mission-graph-admission.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/graph-admission")
public class MissionGraphAdmissionController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = MissionGraphAdmissionCommandDeserializer.class)
  public record Body(
      UUID idempotencyKey, String expectedPlanRevision, String expectedFingerprint) {}

  private final MissionGraphAdmissionService service;

  public MissionGraphAdmissionController(MissionGraphAdmissionService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<MissionGraphAdmissionService.Assessment>> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.current(organizationId, teamId, missionId, actor)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MissionGraphAdmissionService.Receipt>> admit(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody Body command) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.admit(
                    organizationId,
                    teamId,
                    missionId,
                    actor,
                    command == null ? null : command.idempotencyKey(),
                    command == null ? null : command.expectedPlanRevision(),
                    command == null ? null : command.expectedFingerprint())));
  }

  @ExceptionHandler({
    MissionGraphAdmissionService.Conflict.class,
    com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class
  })
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "MISSION_GRAPH_ADMISSION_CONFLICT",
                "Mission graph conditions changed; reload the Mission."));
  }
}

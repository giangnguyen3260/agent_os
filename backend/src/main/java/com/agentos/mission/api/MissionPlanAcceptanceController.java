package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.application.MissionPlanAcceptanceService;
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
@ConditionalOnProperty(name = "agentos.mission-plan-acceptance.enabled", havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}")
public class MissionPlanAcceptanceController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = MissionPlanAcceptanceCommandDeserializer.class)
  public record Body(UUID idempotencyKey, String expectedFingerprint) {}

  private final MissionPlanAcceptanceService service;

  public MissionPlanAcceptanceController(MissionPlanAcceptanceService service) {
    this.service = service;
  }

  @GetMapping("/accepted-plan")
  public ResponseEntity<ApiResponse<MissionPlanAcceptanceService.Current>> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.current(organizationId, teamId, missionId, actor)));
  }

  @PostMapping("/plan-review/accept")
  public ResponseEntity<ApiResponse<MissionPlanAcceptanceService.Receipt>> accept(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody Body command) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.accept(
                    organizationId,
                    teamId,
                    missionId,
                    actor,
                    command == null ? null : command.idempotencyKey(),
                    command == null ? null : command.expectedFingerprint())));
  }

  @ExceptionHandler(MissionPlanAcceptanceService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "MISSION_PLAN_ACCEPTANCE_CONFLICT",
                "Plan conditions changed; reload current configuration."));
  }
}

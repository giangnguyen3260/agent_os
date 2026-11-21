package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.MissionCompletionService;
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
@ConditionalOnProperty(name = "agentos.mission-completion.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/completion")
public class MissionCompletionController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = MissionCompletionCommandDeserializer.class)
  public record Body(
      UUID idempotencyKey, MissionCompletionService.Action action, String expectedFingerprint) {}

  private final MissionCompletionService service;

  public MissionCompletionController(MissionCompletionService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<MissionCompletionService.Snapshot>> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.current(organizationId, teamId, missionId, actor)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MissionCompletionService.Receipt>> apply(
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
                    command == null
                        ? null
                        : new MissionCompletionService.Command(
                            command.idempotencyKey(),
                            command.action(),
                            command.expectedFingerprint()))));
  }

  @ExceptionHandler(MissionCompletionService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "MISSION_COMPLETION_CONFLICT",
                "Mission completion conditions changed; reload current Mission."));
  }
}

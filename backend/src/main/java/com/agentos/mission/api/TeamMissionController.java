package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.api.dto.MissionCreateRequest;
import com.agentos.mission.api.dto.MissionDetailResponse;
import com.agentos.mission.application.MissionCreateService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/missions")
public class TeamMissionController {
  private final MissionCreateService missions;

  public TeamMissionController(MissionCreateService missions) {
    this.missions = missions;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MissionDetailResponse>> create(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody MissionCreateRequest request) {
    var result = missions.create(organizationId, teamId, human, key, request);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.response()));
  }

  @GetMapping("/eligible-managers")
  public ApiResponse<MissionCreateService.Candidates> eligible(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query) {
    return ApiResponse.ok(missions.eligible(organizationId, teamId, human, page, query));
  }

  @GetMapping("/{missionId}")
  public ApiResponse<MissionDetailResponse> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(missions.get(organizationId, teamId, human, missionId));
  }
}

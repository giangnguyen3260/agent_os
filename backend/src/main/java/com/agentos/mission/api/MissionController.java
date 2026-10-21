package com.agentos.mission.api;

import com.agentos.common.api.ApiResponse;
import com.agentos.mission.api.dto.MissionResponse;
import com.agentos.mission.application.MissionService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/missions")
public class MissionController {

  private final MissionService missionService;

  public MissionController(MissionService missionService) {
    this.missionService = missionService;
  }

  @PostMapping
  public ApiResponse<MissionResponse> create(@PathVariable UUID organizationId) {
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.GONE, "Use scoped Team mission creation");
  }

  @GetMapping
  public ApiResponse<List<MissionResponse>> list(@PathVariable UUID organizationId) {
    return ApiResponse.ok(missionService.list(organizationId));
  }

  @GetMapping("/{missionId}")
  public ApiResponse<MissionResponse> get(
      @PathVariable UUID organizationId, @PathVariable UUID missionId) {
    return ApiResponse.ok(missionService.get(organizationId, missionId));
  }

  @PostMapping("/{missionId}/plan")
  public ApiResponse<MissionResponse> plan(
      @PathVariable UUID organizationId, @PathVariable UUID missionId) {
    return ApiResponse.ok(missionService.requestPlanning(organizationId, missionId));
  }
}

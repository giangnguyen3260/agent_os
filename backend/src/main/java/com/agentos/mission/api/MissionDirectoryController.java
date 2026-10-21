package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.api.dto.MissionDirectoryResponse;
import com.agentos.mission.application.MissionDirectoryService;
import com.agentos.mission.domain.MissionStatus;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/missions/directory")
public class MissionDirectoryController {
  private final MissionDirectoryService missions;

  public MissionDirectoryController(MissionDirectoryService missions) {
    this.missions = missions;
  }

  @GetMapping
  public ApiResponse<MissionDirectoryResponse> directory(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) MissionStatus status,
      @RequestParam(required = false) UUID teamId) {
    return ApiResponse.ok(missions.directory(organizationId, human, page, query, status, teamId));
  }
}

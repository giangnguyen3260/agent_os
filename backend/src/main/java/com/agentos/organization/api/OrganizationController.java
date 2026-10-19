package com.agentos.organization.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.organization.api.dto.CreateOrganizationRequest;
import com.agentos.organization.api.dto.NewTeamDraftRequest;
import com.agentos.organization.api.dto.OrganizationResponse;
import com.agentos.organization.api.dto.TeamDetailResponse;
import com.agentos.organization.api.dto.TeamResponse;
import com.agentos.organization.application.NewTeamDraftService;
import com.agentos.organization.application.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

  private final OrganizationService organizationService;

  private final NewTeamDraftService drafts;

  public OrganizationController(
      OrganizationService organizationService, NewTeamDraftService drafts) {
    this.organizationService = organizationService;
    this.drafts = drafts;
  }

  @PostMapping
  public ApiResponse<OrganizationResponse> create(
      @Valid @RequestBody CreateOrganizationRequest request) {
    return ApiResponse.ok(organizationService.create(request));
  }

  @GetMapping
  public ApiResponse<List<OrganizationResponse>> list() {
    return ApiResponse.ok(organizationService.list());
  }

  @GetMapping("/{organizationId}")
  public ApiResponse<OrganizationResponse> get(@PathVariable UUID organizationId) {
    return ApiResponse.ok(organizationService.get(organizationId));
  }

  @PostMapping("/{organizationId}/teams")
  public ResponseEntity<ApiResponse<TeamDetailResponse>> createTeam(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody NewTeamDraftRequest request) {
    var created = drafts.create(organizationId, human, key, request);
    return ResponseEntity.status(created.replay() ? 200 : 201)
        .body(ApiResponse.ok(created.response()));
  }

  @GetMapping("/{organizationId}/teams/eligible-agents")
  public ApiResponse<NewTeamDraftService.Candidates> eligibleAgents(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String query) {
    return ApiResponse.ok(drafts.eligible(organizationId, human, page, query));
  }

  @GetMapping("/{organizationId}/teams/{teamId}")
  public ApiResponse<TeamDetailResponse> detail(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(drafts.get(organizationId, human, teamId));
  }

  @GetMapping("/{organizationId}/teams")
  public ApiResponse<List<TeamResponse>> listTeams(@PathVariable UUID organizationId) {
    return ApiResponse.ok(organizationService.listTeams(organizationId));
  }
}

package com.agentos.organization.api;

import com.agentos.common.api.ApiResponse;
import com.agentos.organization.api.dto.CreateOrganizationRequest;
import com.agentos.organization.api.dto.CreateTeamRequest;
import com.agentos.organization.api.dto.OrganizationResponse;
import com.agentos.organization.api.dto.TeamResponse;
import com.agentos.organization.application.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

  private final OrganizationService organizationService;

  public OrganizationController(OrganizationService organizationService) {
    this.organizationService = organizationService;
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
  public ApiResponse<TeamResponse> createTeam(
      @PathVariable UUID organizationId, @Valid @RequestBody CreateTeamRequest request) {
    return ApiResponse.ok(organizationService.createTeam(organizationId, request));
  }

  @GetMapping("/{organizationId}/teams")
  public ApiResponse<List<TeamResponse>> listTeams(@PathVariable UUID organizationId) {
    return ApiResponse.ok(organizationService.listTeams(organizationId));
  }
}

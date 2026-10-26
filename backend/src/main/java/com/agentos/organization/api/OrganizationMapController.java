package com.agentos.organization.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.organization.application.OrganizationMapService;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/organization-map")
public class OrganizationMapController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private final OrganizationMapService service;

  public OrganizationMapController(OrganizationMapService service) {
    this.service = service;
  }

  private UUID uuid(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches())
      throw new IllegalArgumentException("Invalid map UUID");
    return UUID.fromString(value);
  }

  private void validate(MultiValueMap<String, String> params, Set<String> allowed) {
    if (params.entrySet().stream()
        .anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().size() != 1))
      throw new IllegalArgumentException("Invalid map parameters");
  }

  private int page(MultiValueMap<String, String> params) {
    String value = params.getFirst("page");
    if (value == null) return 0;
    if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid map page");
    // Strip leading zeroes before parsing so lexical digits remain valid without overflow.
    String significant = value.replaceFirst("^0+(?!$)", "");
    if (significant.length() > 6) throw new IllegalArgumentException("Invalid map page");
    int page = Integer.parseInt(significant);
    if (page > 100000) throw new IllegalArgumentException("Invalid map page");
    return page;
  }

  @GetMapping
  public ApiResponse<OrganizationMapService.Overview> overview(
      @PathVariable String organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam MultiValueMap<String, String> params) {
    validate(params, Set.of("page", "query", "status"));
    return ApiResponse.ok(
        service.overview(
            uuid(organizationId),
            human,
            page(params),
            params.getFirst("query"),
            params.getFirst("status")));
  }

  @GetMapping("/agents")
  public ApiResponse<OrganizationMapService.Agents> agents(
      @PathVariable String organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam MultiValueMap<String, String> params) {
    validate(params, Set.of("teamId", "page", "query"));
    UUID teamId = params.containsKey("teamId") ? uuid(params.getFirst("teamId")) : null;
    return ApiResponse.ok(
        service.agents(
            uuid(organizationId), human, teamId, page(params), params.getFirst("query")));
  }
}

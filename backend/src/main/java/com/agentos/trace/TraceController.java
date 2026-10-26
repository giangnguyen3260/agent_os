package com.agentos.trace;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/trace-events")
public class TraceController {
  private final TraceExplorerService service;

  public TraceController(TraceExplorerService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<TraceResponse.Page> list(
      @PathVariable String organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam MultiValueMap<String, String> parameters) {
    return ApiResponse.ok(
        service.list(TraceQuery.uuid(organizationId), human, TraceQuery.parse(parameters)));
  }

  @GetMapping("/{eventId}")
  public ApiResponse<TraceResponse.Detail> detail(
      @PathVariable String organizationId,
      @PathVariable String eventId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam MultiValueMap<String, String> parameters) {
    TraceQuery.validateParameters(parameters, Set.of());
    return ApiResponse.ok(
        service.detail(TraceQuery.uuid(organizationId), human, TraceQuery.uuid(eventId)));
  }
}

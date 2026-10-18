package com.agentos.observability.api;

import com.agentos.common.api.ApiResponse;
import com.agentos.observability.api.dto.AuditEventResponse;
import com.agentos.observability.application.TraceService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public class ObservabilityController {

  private final TraceService traceService;

  public ObservabilityController(TraceService traceService) {
    this.traceService = traceService;
  }

  @GetMapping("/audit-events")
  public ApiResponse<List<AuditEventResponse>> auditEvents(@PathVariable UUID organizationId) {
    return ApiResponse.ok(traceService.listAuditEvents(organizationId));
  }

  @GetMapping("/missions/{missionId}/trace")
  public ApiResponse<List<AuditEventResponse>> missionTrace(
      @PathVariable UUID organizationId, @PathVariable UUID missionId) {
    return ApiResponse.ok(traceService.missionTrace(organizationId, missionId));
  }
}

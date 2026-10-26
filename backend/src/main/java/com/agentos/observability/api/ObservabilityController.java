package com.agentos.observability.api;

import com.agentos.common.api.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retires the unbounded payload-bearing preview endpoints in favor of safe trace-events reads. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public class ObservabilityController {
  @GetMapping({"/audit-events", "/missions/{missionId}/trace"})
  public ApiResponse<Void> retired() {
    throw new EntityNotFoundException("Legacy trace endpoint unavailable");
  }
}

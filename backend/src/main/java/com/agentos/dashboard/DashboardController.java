package com.agentos.dashboard;

import com.agentos.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/dashboard")
public class DashboardController {
  private final DashboardService service;

  public DashboardController(DashboardService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<DashboardService.Summary> summary(
      @PathVariable UUID organizationId, @RequestParam(defaultValue = "0") int teamPage) {
    return ApiResponse.ok(service.summary(organizationId, teamPage));
  }

  @GetMapping("/tasks")
  public ApiResponse<DashboardService.Page<DashboardService.Task>> tasks(
      @PathVariable UUID organizationId,
      @RequestParam String state,
      @RequestParam(required = false) UUID teamId,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(service.tasks(organizationId, state, teamId, page));
  }
}

package com.agentos.governance.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.governance.api.MemberAccessResponse.Grants;
import com.agentos.governance.api.MemberAccessResponse.Inventory;
import com.agentos.governance.application.MemberAccessService;
import com.agentos.governance.application.MemberAccessService.Status;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/members")
public class MemberAccessController {
  private final MemberAccessService service;

  public MemberAccessController(MemberAccessService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Inventory> inventory(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String role) {
    return ApiResponse.ok(service.inventory(organizationId, human, page, query, role));
  }

  @GetMapping("/{accountId}/approval-grants")
  public ApiResponse<Grants> grants(
      @PathVariable UUID organizationId,
      @PathVariable UUID accountId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "ALL") Status status) {
    return ApiResponse.ok(service.grants(organizationId, accountId, human, page, status));
  }
}

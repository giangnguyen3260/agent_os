package com.agentos.governance.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.governance.api.MemberRoleManagementResponse.Command;
import com.agentos.governance.api.MemberRoleManagementResponse.Context;
import com.agentos.governance.api.MemberRoleManagementResponse.History;
import com.agentos.governance.api.MemberRoleManagementResponse.Result;
import com.agentos.governance.application.MemberRoleManagementService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/members/{accountId}/role-management")
public class MemberRoleManagementController {
  private final MemberRoleManagementService service;

  public MemberRoleManagementController(MemberRoleManagementService service) {
    this.service = service;
  }

  private static UUID uuid(String value) {
    return MemberRoleManagementCommandDeserializer.uuid(value);
  }

  @GetMapping("/context")
  public ApiResponse<Context> context(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestParam(required = false) String newRole) {
    if (newRole != null) MemberRoleManagementCommandDeserializer.role(newRole);
    return ApiResponse.ok(service.context(uuid(organizationId), uuid(accountId), h, newRole));
  }

  @GetMapping("/history")
  public ApiResponse<History> history(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    return ApiResponse.ok(service.history(uuid(organizationId), uuid(accountId), h, page));
  }

  @GetMapping("/receipts/{key}")
  public ApiResponse<Result> receipt(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity h) {
    return ApiResponse.ok(service.receipt(uuid(organizationId), uuid(accountId), h, uuid(key)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<Result>> mutate(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Command c) {
    var result = service.mutate(uuid(organizationId), uuid(accountId), h, uuid(key), c);
    return ResponseEntity.status(result.replay() ? 200 : 201).body(ApiResponse.ok(result.result()));
  }
}

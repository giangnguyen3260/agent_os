package com.agentos.governance.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Command;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Context;
import com.agentos.governance.api.ApprovalGrantManagementResponse.History;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Result;
import com.agentos.governance.api.ApprovalGrantManagementResponse.RevocationContext;
import com.agentos.governance.application.ApprovalGrantManagementService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/members/{accountId}/approval-grants")
public class ApprovalGrantManagementController {
  private final ApprovalGrantManagementService service;

  public ApprovalGrantManagementController(ApprovalGrantManagementService service) {
    this.service = service;
  }

  private static UUID uuid(String value) {
    return ApprovalGrantManagementCommandDeserializer.uuid(value);
  }

  @GetMapping("/management-context")
  public ApiResponse<Context> context(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestParam(required = false) String teamId,
      @RequestParam(required = false) String riskLevel) {
    return ApiResponse.ok(
        service.context(
            uuid(organizationId),
            uuid(accountId),
            h,
            teamId == null ? null : uuid(teamId),
            riskLevel));
  }

  @GetMapping("/{grantId}/revocation-context")
  public ApiResponse<RevocationContext> revocationContext(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @PathVariable String grantId,
      @AuthenticationPrincipal HumanIdentity h) {
    return ApiResponse.ok(
        service.revocationContext(uuid(organizationId), uuid(accountId), uuid(grantId), h));
  }

  @GetMapping("/{grantId}/management-history")
  public ApiResponse<History> history(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @PathVariable String grantId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(
        service.history(uuid(organizationId), uuid(accountId), uuid(grantId), h, page));
  }

  @GetMapping("/management-receipts/{key}")
  public ApiResponse<Result> receipt(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestParam String operation) {
    return ApiResponse.ok(
        service.receipt(uuid(organizationId), uuid(accountId), h, uuid(key), operation));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<Result>> issue(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Command c) {
    return mutate(organizationId, accountId, null, h, key, c);
  }

  @PostMapping("/{grantId}/revoke")
  public ResponseEntity<ApiResponse<Result>> revoke(
      @PathVariable String organizationId,
      @PathVariable String accountId,
      @PathVariable String grantId,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Command c) {
    return mutate(organizationId, accountId, uuid(grantId), h, key, c);
  }

  private ResponseEntity<ApiResponse<Result>> mutate(
      String org, String account, UUID id, HumanIdentity h, String key, Command c) {
    var result = service.mutate(uuid(org), uuid(account), id, h, uuid(key), c);
    return ResponseEntity.status(result.replay() ? 200 : 201).body(ApiResponse.ok(result.result()));
  }
}

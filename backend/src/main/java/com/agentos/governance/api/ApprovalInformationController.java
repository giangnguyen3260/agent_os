package com.agentos.governance.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.governance.application.ApprovalInformationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/approvals/{approvalId}")
public class ApprovalInformationController {
  private final ApprovalInformationService service;

  public ApprovalInformationController(ApprovalInformationService service) {
    this.service = service;
  }

  @GetMapping("/information")
  public ApiResponse<ApprovalInformationResponse> information(
      @PathVariable UUID organizationId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(service.information(organizationId, approvalId, human));
  }

  @GetMapping("/information/receipts/{key}")
  public ApiResponse<ApprovalInformationResponse.Mutation> receipt(
      @PathVariable UUID organizationId,
      @PathVariable UUID approvalId,
      @PathVariable UUID key,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(service.receipt(organizationId, approvalId, human, key));
  }

  @PostMapping({"/request-information", "/reply"})
  public ResponseEntity<ApiResponse<ApprovalInformationResponse.Mutation>> mutate(
      @PathVariable UUID organizationId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ApprovalInformationResponse.Command body,
      jakarta.servlet.http.HttpServletRequest request) {
    if (!key.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
      throw new IllegalArgumentException("Invalid idempotency key");
    boolean reply = request.getRequestURI().endsWith("/reply");
    if (reply != body.reply()) throw new IllegalArgumentException("Invalid information operation");
    var result = service.exchange(organizationId, approvalId, human, UUID.fromString(key), body);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.mutation()));
  }
}

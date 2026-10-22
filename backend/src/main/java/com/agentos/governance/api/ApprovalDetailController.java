package com.agentos.governance.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.governance.application.ApprovalDetailService;
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
public class ApprovalDetailController {
  private final ApprovalDetailService service;

  public ApprovalDetailController(ApprovalDetailService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<ApprovalDetailResponse> detail(
      @PathVariable UUID organizationId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(service.detail(organizationId, approvalId, human));
  }

  @PostMapping({"/approve", "/reject"})
  public ResponseEntity<ApiResponse<ApprovalDetailResponse.Decision>> decide(
      @PathVariable UUID organizationId,
      @PathVariable UUID approvalId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody ApprovalDetailResponse.Command body,
      jakarta.servlet.http.HttpServletRequest request) {
    var result =
        service.decide(
            organizationId,
            approvalId,
            human,
            key,
            request.getRequestURI().endsWith("/approve"),
            body);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.decision()));
  }
}

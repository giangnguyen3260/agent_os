package com.agentos.governance.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.governance.application.ApprovalCenterService;
import com.agentos.governance.application.ApprovalCenterService.Group;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/approvals")
public class ApprovalCenterController {
  private final ApprovalCenterService approvals;

  public ApprovalCenterController(ApprovalCenterService approvals) {
    this.approvals = approvals;
  }

  @GetMapping
  public ApiResponse<ApprovalCenterResponse> list(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestParam(defaultValue = "PENDING") Group group,
      @RequestParam(defaultValue = "0") int page) {
    return ApiResponse.ok(approvals.list(organizationId, human, group, page));
  }
}

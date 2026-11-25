package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.UnsentRunRecoveryService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.execution.unsent-recovery.enabled", havingValue = "true")
@RequestMapping("/api/v1/organizations/{org}/execution-runs/{run}/unsent-recovery")
public class UnsentRunRecoveryController {
  private final UnsentRunRecoveryService service;

  public UnsentRunRecoveryController(UnsentRunRecoveryService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<UnsentRunRecoveryService.Context>> preview(
      @PathVariable UUID org,
      @PathVariable UUID run,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.preview(org, run, actor)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<UnsentRunRecoveryService.Receipt>> prepare(
      @PathVariable UUID org,
      @PathVariable UUID run,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody WorkerTextToolApprovalController.Body body) {
    if (body == null) throw new IllegalArgumentException("Invalid recovery command");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.prepare(
                    org, run, actor, body.idempotencyKey(), body.expectedFingerprint())));
  }

  @ExceptionHandler({UnsentRunRecoveryService.Conflict.class, IllegalStateException.class})
  public ResponseEntity<java.util.Map<String, String>> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(java.util.Map.of("error", "Recovery conditions changed; refresh current Run"));
  }
}

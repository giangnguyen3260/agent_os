package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.ApprovedWorkerTextTool;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.tools.text-metrics.enabled", havingValue = "true")
@RequestMapping("/api/v1/organizations/{org}/execution-runs/{run}/text-tool-approval")
public class WorkerTextToolApprovalController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = WorkerTextToolApprovalDeserializer.class)
  public record Body(UUID idempotencyKey, String expectedFingerprint) {}

  private final ApprovedWorkerTextTool service;

  public WorkerTextToolApprovalController(ApprovedWorkerTextTool service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<ApprovedWorkerTextTool.Preview>> preview(
      @PathVariable UUID org,
      @PathVariable UUID run,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.preview(org, run, actor)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ApprovedWorkerTextTool.Preview>> approve(
      @PathVariable UUID org,
      @PathVariable UUID run,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody Body body) {
    if (body == null) throw new IllegalArgumentException("Invalid tool approval");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.approve(
                    org, run, actor, body.idempotencyKey(), body.expectedFingerprint())));
  }

  @ExceptionHandler(com.agentos.orchestration.application.TextToolGrantService.Conflict.class)
  @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
  public java.util.Map<String, String> conflict() {
    return java.util.Map.of("error", "Tool approval conditions changed");
  }
}

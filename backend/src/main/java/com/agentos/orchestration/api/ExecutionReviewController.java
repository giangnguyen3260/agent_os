package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.api.dto.StrictLongDeserializer;
import com.agentos.orchestration.application.ExecutionReviewService;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/organizations/{organizationId}/execution-runs/{runId}/review")
public class ExecutionReviewController {
  public record DecisionBody(
      UUID attemptId,
      @JsonDeserialize(using = StrictLongDeserializer.class) Long expectedRevision,
      String outputSha256,
      String decision,
      String reason) {}

  private final ExecutionReviewService reviews;

  public ExecutionReviewController(ExecutionReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  public ApiResponse<ExecutionReviewService.Review> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal HumanIdentity reviewer) {
    return ApiResponse.ok(reviews.current(organizationId, runId, reviewer));
  }

  @PostMapping("/decision")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void decide(
      @PathVariable UUID organizationId,
      @PathVariable UUID runId,
      @AuthenticationPrincipal HumanIdentity reviewer,
      @RequestBody DecisionBody body) {
    reviews.decide(
        organizationId,
        runId,
        reviewer,
        body.attemptId(),
        body.expectedRevision(),
        body.outputSha256(),
        body.decision(),
        body.reason());
  }

  @ExceptionHandler(ExecutionReviewService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> conflict(ExecutionReviewService.Conflict ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of("EXECUTION_REVIEW_CONFLICT", ex.getMessage()));
  }
}

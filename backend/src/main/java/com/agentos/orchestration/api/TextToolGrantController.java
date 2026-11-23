package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.TextToolGrantService;
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
@ConditionalOnProperty(name = "agentos.tools.text-metrics.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{org}/teams/{team}/missions/{mission}/text-tool-grants/{agent}")
public class TextToolGrantController {
  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = TextToolGrantCommandDeserializer.class)
  public record Body(
      UUID idempotencyKey, TextToolGrantService.Operation operation, String expectedFingerprint) {}

  private final TextToolGrantService service;

  public TextToolGrantController(TextToolGrantService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<TextToolGrantService.Context>> current(
      @PathVariable UUID org,
      @PathVariable UUID team,
      @PathVariable UUID mission,
      @PathVariable UUID agent,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.current(new TextToolGrantService.Scope(org, team, mission, agent), actor)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TextToolGrantService.Receipt>> apply(
      @PathVariable UUID org,
      @PathVariable UUID team,
      @PathVariable UUID mission,
      @PathVariable UUID agent,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody Body body) {
    if (body == null) throw new IllegalArgumentException("Invalid Tool grant command");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            ApiResponse.ok(
                service.apply(
                    new TextToolGrantService.Scope(org, team, mission, agent),
                    actor,
                    body.idempotencyKey(),
                    body.operation(),
                    body.expectedFingerprint())));
  }

  @ExceptionHandler(TextToolGrantService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "TEXT_TOOL_GRANT_CONFLICT",
                "Tool grant conditions changed; reload current scope."));
  }
}

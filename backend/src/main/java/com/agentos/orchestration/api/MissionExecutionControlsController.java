package com.agentos.orchestration.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.orchestration.application.MissionExecutionControlsService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.mission-execution-controls.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/execution-controls")
public class MissionExecutionControlsController {
  private final MissionExecutionControlsService service;

  public MissionExecutionControlsController(MissionExecutionControlsService service) {
    this.service = service;
  }

  private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(data));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<MissionExecutionControlsService.Snapshot>> current(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ok(service.current(organizationId, teamId, missionId, actor));
  }

  @GetMapping("/receipt")
  public ResponseEntity<ApiResponse<MissionExecutionControlsService.Receipt>> receipt(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestParam UUID idempotencyKey) {
    return ok(service.receipt(organizationId, teamId, missionId, actor, idempotencyKey));
  }

  @GetMapping("/receipt/snapshot")
  public ResponseEntity<ApiResponse<MissionExecutionControlsService.Snapshot>> reviewedSnapshot(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestParam UUID idempotencyKey) {
    return ok(service.reviewedSnapshot(organizationId, teamId, missionId, actor, idempotencyKey));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MissionExecutionControlsService.Receipt>> apply(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody String raw) {
    MissionExecutionControlsService.Command command;
    if (raw == null || raw.length() > 2048)
      throw new IllegalArgumentException("Invalid execution control command");
    try (var parser = new ObjectMapper().getFactory().createParser(raw)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode node = new ObjectMapper().readTree(parser);
      Set<String> fields = Set.of("idempotencyKey", "expectedFingerprint", "action");
      if (parser.nextToken() != null || !node.isObject() || node.size() != 3)
        throw new IllegalArgumentException();
      node.fieldNames()
          .forEachRemaining(
              k -> {
                if (!fields.contains(k)) throw new IllegalArgumentException();
              });
      for (String f : fields) if (!node.path(f).isTextual()) throw new IllegalArgumentException();
      String key = node.path("idempotencyKey").textValue(),
          fp = node.path("expectedFingerprint").textValue();
      if (!key.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
          || !fp.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      command =
          new MissionExecutionControlsService.Command(
              UUID.fromString(key),
              fp,
              MissionExecutionControlsService.Action.valueOf(node.path("action").textValue()));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid execution control command");
    }
    var result = service.apply(organizationId, teamId, missionId, actor, command);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Idempotency-Replayed", Boolean.toString(result.replay()))
        .body(ApiResponse.ok(result.receipt()));
  }

  @ExceptionHandler(MissionExecutionControlsService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "MISSION_EXECUTION_CONTROL_CONFLICT",
                "Execution changed; reload the current state."));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> invalid() {
    return ResponseEntity.badRequest()
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "INVALID_EXECUTION_CONTROL_COMMAND", "Invalid execution control command."));
  }
}

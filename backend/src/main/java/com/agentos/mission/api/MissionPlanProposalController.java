package com.agentos.mission.api;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.agentos.mission.application.MissionPlanProposalService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
@ConditionalOnProperty(name = "agentos.mission-plan-proposal.enabled", havingValue = "true")
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/plan-proposal")
public class MissionPlanProposalController {
  private final MissionPlanProposalService service;

  public MissionPlanProposalController(MissionPlanProposalService service) {
    this.service = service;
  }

  private <T> ResponseEntity<ApiResponse<T>> ok(T value) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(value));
  }

  private JsonNode body(String raw, Set<String> fields) {
    if (raw == null || raw.length() > 262144)
      throw new IllegalArgumentException("Invalid proposal command");
    try (var parser = new ObjectMapper().getFactory().createParser(raw)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode node = new ObjectMapper().readTree(parser);
      if (parser.nextToken() != null || !node.isObject() || node.size() != fields.size())
        throw new IllegalArgumentException();
      node.fieldNames()
          .forEachRemaining(
              k -> {
                if (!fields.contains(k)) throw new IllegalArgumentException();
              });
      return node;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid proposal command");
    }
  }

  private String text(JsonNode node) {
    if (node == null || !node.isTextual()) throw new IllegalArgumentException("Text required");
    return node.textValue();
  }

  private UUID uuid(JsonNode node) {
    String value = text(node);
    if (!value.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
      throw new IllegalArgumentException("UUID required");
    return UUID.fromString(value);
  }

  private MissionPlanProposalService.Input input(JsonNode node) {
    JsonNode owners = node.get("owners");
    if (owners == null || !owners.isObject() || owners.size() > 20)
      throw new IllegalArgumentException("Owners required");
    Map<String, UUID> bindings = new TreeMap<>();
    owners.fields().forEachRemaining(e -> bindings.put(e.getKey(), uuid(e.getValue())));
    JsonNode content = node.get("proposalJson");
    return new MissionPlanProposalService.Input(
        uuid(node.get("sourceRunId")), content.isNull() ? null : text(content), bindings);
  }

  @GetMapping
  public ResponseEntity<ApiResponse<MissionPlanProposalService.Context>> context(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor) {
    return ok(service.context(organizationId, teamId, missionId, actor));
  }

  @GetMapping("/receipt")
  public ResponseEntity<ApiResponse<MissionPlanProposalService.Applied>> receipt(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestParam UUID idempotencyKey) {
    return ok(service.receipt(organizationId, teamId, missionId, actor, idempotencyKey));
  }

  @PostMapping("/source")
  public ResponseEntity<
          ApiResponse<com.agentos.orchestration.application.AcceptedPlanningOutputReader.Source>>
      source(
          @PathVariable UUID organizationId,
          @PathVariable UUID teamId,
          @PathVariable UUID missionId,
          @AuthenticationPrincipal HumanIdentity actor,
          @RequestBody String raw) {
    return ok(
        service.source(
            organizationId,
            teamId,
            missionId,
            actor,
            uuid(body(raw, Set.of("sourceRunId")).get("sourceRunId"))));
  }

  @PostMapping("/preview")
  public ResponseEntity<ApiResponse<MissionPlanProposalService.Preview>> preview(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody String raw) {
    return ok(
        service.preview(
            organizationId,
            teamId,
            missionId,
            actor,
            input(body(raw, Set.of("sourceRunId", "proposalJson", "owners")))));
  }

  @PostMapping("/apply")
  public ResponseEntity<ApiResponse<MissionPlanProposalService.Receipt>> apply(
      @PathVariable UUID organizationId,
      @PathVariable UUID teamId,
      @PathVariable UUID missionId,
      @AuthenticationPrincipal HumanIdentity actor,
      @RequestBody String raw) {
    var node =
        body(
            raw,
            Set.of(
                "sourceRunId", "proposalJson", "owners", "idempotencyKey", "expectedFingerprint"));
    var result =
        service.apply(
            organizationId,
            teamId,
            missionId,
            actor,
            new MissionPlanProposalService.Command(
                uuid(node.get("idempotencyKey")),
                text(node.get("expectedFingerprint")),
                input(node)));
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Idempotency-Replayed", Boolean.toString(result.replay()))
        .body(ApiResponse.ok(result.receipt()));
  }

  @ExceptionHandler(MissionPlanProposalService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> conflict() {
    return ResponseEntity.status(409)
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "MISSION_PLAN_PROPOSAL_CONFLICT",
                "Reload the empty target and verified planning source."));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> invalid() {
    return ResponseEntity.badRequest()
        .header("Cache-Control", "no-store")
        .body(
            ApiErrorResponse.of(
                "INVALID_PLAN_PROPOSAL", "Invalid proposal data or owner bindings."));
  }
}

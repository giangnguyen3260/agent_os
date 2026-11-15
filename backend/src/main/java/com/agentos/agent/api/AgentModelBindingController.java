package com.agentos.agent.api;

import com.agentos.agent.application.AgentModelBindingService;
import com.agentos.agent.application.AgentModelBindingService.Connections;
import com.agentos.agent.application.AgentModelBindingService.Request;
import com.agentos.agent.application.AgentModelBindingService.Selection;
import com.agentos.agent.application.AgentModelBindingService.State;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.provider-model-discovery.http-enabled", havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}/agents/{agentId}/model-binding")
public class AgentModelBindingController {
  private final AgentModelBindingService bindings;

  public AgentModelBindingController(AgentModelBindingService bindings) {
    this.bindings = bindings;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<State>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human) {
    return response(bindings.get(organizationId, human, agentId));
  }

  @GetMapping("/connections")
  public ResponseEntity<ApiResponse<Connections>> connections(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human) {
    return response(bindings.connections(organizationId, human, agentId));
  }

  @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<State>> put(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode body) {
    if (body == null || !body.isObject() || body.size() != 2 || !body.has("selection"))
      throw invalid();
    var revision = body.path("expectedRevision");
    if (!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.intValue() < 0)
      throw invalid();
    var selection = body.get("selection");
    return response(
        bindings.put(
            organizationId,
            human,
            agentId,
            uuid(key),
            new Request(revision.intValue(), selection.isNull() ? null : selection(selection))));
  }

  private static Selection selection(JsonNode node) {
    if (!node.isObject()
        || node.size() != 3
        || !node.path("connectionId").isTextual()
        || !node.path("checkId").isTextual()
        || !node.path("modelId").isTextual()) throw invalid();
    String model = node.path("modelId").textValue();
    if (model.isBlank()
        || model.codePointCount(0, model.length()) > 256
        || model
            .codePoints()
            .anyMatch(
                code ->
                    Character.isISOControl(code)
                        || code == 0x2028
                        || code == 0x2029
                        || (code >= 0xd800 && code <= 0xdfff))) throw invalid();
    return new Selection(
        uuid(node.path("connectionId").textValue()), uuid(node.path("checkId").textValue()), model);
  }

  private static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.toString().equals(value)) return parsed;
    } catch (IllegalArgumentException failure) {
      // Avoid reflecting malformed input.
    }
    throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid model binding request");
  }

  private static <T> ResponseEntity<ApiResponse<T>> response(T value) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(value));
  }
}

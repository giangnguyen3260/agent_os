package com.agentos.agent.api;

import com.agentos.agent.application.AgentModelGrantService;
import com.agentos.agent.application.AgentModelGrantService.Request;
import com.agentos.agent.application.AgentModelGrantService.State;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.provider-model-discovery.http-enabled", havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}/agents/{agentId}/model-grant")
public class AgentModelGrantController {
  private static final int MAX_BODY_BYTES = 4096;
  private static final ObjectMapper GRANT_JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final AgentModelGrantService grants;

  public AgentModelGrantController(AgentModelGrantService grants) {
    this.grants = grants;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<State>> get(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human) {
    return response(grants.get(organizationId, human, agentId));
  }

  @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<State>> put(
      @PathVariable UUID organizationId,
      @PathVariable UUID agentId,
      @AuthenticationPrincipal HumanIdentity human,
      @RequestHeader("Idempotency-Key") String key,
      InputStream input) {
    var body = read(input);
    if (body == null || !body.isObject() || body.size() != 3 || !body.path("enabled").isBoolean())
      throw invalid();
    var request =
        new Request(
            revision(body.path("expectedRevision")),
            revision(body.path("expectedBindingRevision")),
            body.path("enabled").booleanValue());
    return response(grants.put(organizationId, human, agentId, uuid(key), request));
  }

  private static JsonNode read(InputStream input) {
    try {
      var bytes = input.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) throw invalid();
      return GRANT_JSON.readTree(bytes);
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static int revision(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
      throw invalid();
    return value.intValue();
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
    return new IllegalArgumentException("Invalid model grant request");
  }

  private static ResponseEntity<ApiResponse<State>> response(State state) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(state));
  }
}

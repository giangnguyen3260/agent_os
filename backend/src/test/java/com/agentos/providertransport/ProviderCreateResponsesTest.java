package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providerreceipts.ProviderReceiptService.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ProviderCreateResponsesTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void everyInternalFailureHasAnExplicitPublicStatusAndCode() throws Exception {
    Map<Code, String> expected =
        Map.ofEntries(
            Map.entry(Code.UNAUTHORIZED, "401:UNAUTHORIZED"),
            Map.entry(Code.FORBIDDEN, "403:FORBIDDEN"),
            Map.entry(Code.INVALID_COMMAND, "400:BAD_REQUEST"),
            Map.entry(Code.INVALID_KEY, "400:BAD_REQUEST"),
            Map.entry(Code.INVALID_PREPARATION, "503:PROVIDER_CONNECTION_UNAVAILABLE"),
            Map.entry(Code.NOT_FOUND, "404:NOT_FOUND"),
            Map.entry(Code.IDEMPOTENCY_CONFLICT, "409:IDEMPOTENCY_CONFLICT"),
            Map.entry(Code.REVISION_CONFLICT, "409:REVISION_CONFLICT"),
            Map.entry(Code.NO_CHANGE, "409:NO_CHANGE"),
            Map.entry(Code.REVISION_EXHAUSTED, "409:REVISION_EXHAUSTED"),
            Map.entry(Code.CONNECTION_CONFLICT, "503:PROVIDER_CONNECTION_UNAVAILABLE"),
            Map.entry(Code.PROVIDER_DISABLED, "409:PROVIDER_DISABLED"),
            Map.entry(Code.CONNECTION_LIMIT, "409:CONNECTION_LIMIT"),
            Map.entry(Code.RATE_LIMITED, "429:RATE_LIMITED"),
            Map.entry(Code.PREPARATION_REQUIRED, "503:PROVIDER_CONNECTION_UNAVAILABLE"),
            Map.entry(Code.RECEIPT_UNAVAILABLE, "503:PROVIDER_CONNECTION_UNAVAILABLE"),
            Map.entry(Code.COMMAND_UNAVAILABLE, "503:PROVIDER_CONNECTION_UNAVAILABLE"),
            Map.entry(Code.COMMAND_OUTCOME_UNKNOWN, "503:PROVIDER_CONNECTION_UNAVAILABLE"),
            Map.entry(Code.LOOKUP_UNAVAILABLE, "503:PROVIDER_CONNECTION_UNAVAILABLE"));
    assertThat(expected).hasSize(Code.values().length);
    for (Code code : Code.values()) {
      var response = new MockHttpServletResponse();
      ProviderCreateResponses.error(
          json, response, new Failure(code, code == Code.RATE_LIMITED ? 17 : 0));
      JsonNode body = envelope(response);
      assertThat(response.getStatus() + ":" + body.path("error").path("code").asText())
          .isEqualTo(expected.get(code));
      assertThat(response.getHeader("Retry-After"))
          .isEqualTo(code == Code.RATE_LIMITED ? "17" : null);
    }
  }

  @Test
  void rateDelayPreservesBothBoundsAndMissingMetadataFailsClosed() throws Exception {
    for (int delay : new int[] {1, 60}) {
      var response = new MockHttpServletResponse();
      ProviderCreateResponses.error(json, response, new Failure(Code.RATE_LIMITED, delay));
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader("Retry-After")).isEqualTo(Integer.toString(delay));
      envelope(response);
    }
    var response = new MockHttpServletResponse();
    ProviderCreateResponses.error(json, response, new Failure(Code.RATE_LIMITED));
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isNull();
    assertThat(envelope(response).path("error").path("code").asText())
        .isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
  }

  @Test
  void unknownAndUnavailableExposeNoProvisionalReceiptOrRetryInstruction() throws Exception {
    for (WriteOutcome outcome :
        new WriteOutcome[] {
          new WriteOutcome(WriteState.UNKNOWN, null, new Failure(Code.COMMAND_OUTCOME_UNKNOWN)),
          new WriteOutcome(WriteState.UNAVAILABLE, null, new Failure(Code.COMMAND_UNAVAILABLE))
        }) {
      var response = new MockHttpServletResponse();
      ProviderCreateResponses.error(json, response, outcome);
      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).isNull();
      assertThat(envelope(response).path("error").path("code").asText())
          .isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
      assertThat(response.getContentAsString())
          .doesNotContain("COMMAND_OUTCOME_UNKNOWN", "receipt", "credential", "result", "data");
    }
  }

  @Test
  void rejectedOutcomeUsesSameSafeProjection() throws Exception {
    var response = new MockHttpServletResponse();
    ProviderCreateResponses.error(
        json,
        response,
        new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.IDEMPOTENCY_CONFLICT)));
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(envelope(response).path("error").path("message").asText())
        .isEqualTo("Provider connection command conflicts with current state");
  }

  @Test
  void committedReceiptIsRejectedBeforeAnyResponseMutation() {
    var id = UUID.randomUUID();
    var receipt = new Receipt(id, id, Operation.CREATE, id, id, null, null, null);
    var committed = new WriteOutcome(WriteState.COMMITTED, new Result(receipt, false), null);
    var response = new MockHttpServletResponse();
    assertThatThrownBy(() -> ProviderCreateResponses.error(json, response, committed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Provider error outcome required")
        .hasNoCause();
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(response.getHeaderNames()).isEmpty();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void missingInputsFailWithoutEchoingCallerContent() {
    var response = new MockHttpServletResponse();
    assertThatThrownBy(() -> ProviderCreateResponses.error(json, response, (Failure) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Provider error response inputs required")
        .hasNoCause();
    assertThatThrownBy(() -> ProviderCreateResponses.error(json, response, (WriteOutcome) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Provider error outcome required")
        .hasNoCause();
    assertThat(response.getContentAsByteArray()).isEmpty();
  }

  private JsonNode envelope(MockHttpServletResponse response) throws Exception {
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentType()).isEqualTo("application/json");
    JsonNode body = json.readTree(response.getContentAsByteArray());
    assertThat(body.properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder("error", "meta");
    assertThat(body.path("error").properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder("code", "message", "details");
    assertThat(body.path("error").path("details").isObject()).isTrue();
    assertThat(body.path("error").path("details").size()).isZero();
    assertThat(body.path("meta").properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("requestId");
    assertThat(UUID.fromString(body.path("meta").path("requestId").asText()).version())
        .isEqualTo(4);
    return body;
  }
}

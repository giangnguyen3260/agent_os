package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerreceipts.ProviderReceiptService.Configuration;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providerreceipts.ProviderReceiptService.Result;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ProviderCreateResponsesTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void committedCreateAndReplayReturnExactlyTheSameHistoricalData() throws Exception {
    var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    var org = UUID.randomUUID();
    var command = UUID.randomUUID();
    var connection = UUID.randomUUID();
    var actor = UUID.randomUUID();
    var eventTime = OffsetDateTime.parse("2026-11-06T13:00:00.123456+07:00");
    var recorded = eventTime.plusNanos(1000);
    for (var profile :
        new Configuration[] {
          snapshot(
              org, connection, Provider.OPENAI, CredentialMode.OPENAI_API_KEY, null, eventTime),
          snapshot(
              org,
              connection,
              Provider.GOOGLE_GEMINI,
              CredentialMode.GEMINI_API_KEY,
              null,
              eventTime),
          snapshot(
              org,
              connection,
              Provider.ANTHROPIC,
              CredentialMode.ANTHROPIC_API_KEY,
              null,
              eventTime),
          snapshot(
              org,
              connection,
              Provider.ANTHROPIC,
              CredentialMode.ANTHROPIC_API_KEY,
              "wrkspc_test",
              eventTime)
        }) {
      var receipt =
          new Receipt(org, command, Operation.CREATE, connection, actor, recorded, null, profile);
      var first = new MockHttpServletResponse();
      var replay = new MockHttpServletResponse();
      ProviderCreateResponses.writeOutcome(mapper, first, committed(receipt, false));
      ProviderCreateResponses.writeOutcome(mapper, replay, committed(receipt, true));
      assertThat(first.getStatus()).isEqualTo(201);
      assertThat(replay.getStatus()).isEqualTo(200);
      var firstBody = json.readTree(first.getContentAsByteArray());
      var replayBody = json.readTree(replay.getContentAsByteArray());
      assertThat(firstBody.properties())
          .extracting(Map.Entry::getKey)
          .containsExactlyInAnyOrder("data", "meta");
      assertThat(firstBody.path("meta").properties())
          .extracting(Map.Entry::getKey)
          .containsExactly("requestId");
      assertThat(UUID.fromString(firstBody.path("meta").path("requestId").asText()).version())
          .isEqualTo(4);
      assertThat(replayBody.path("data")).isEqualTo(firstBody.path("data"));
      String options =
          profile.provider() != Provider.ANTHROPIC
              ? "{}"
              : profile.workspaceId() == null
                  ? "{\"workspaceId\":null}"
                  : "{\"workspaceId\":\"wrkspc_test\"}";
      assertThat(firstBody.path("data"))
          .isEqualTo(
              json.readTree(
                  """
                  {
                    "organizationId":"%s","commandId":"%s","operation":"CREATE","connectionId":"%s",
                    "actorId":"%s","committedAt":"2026-11-06T06:00:00.123457Z","before":null,
                    "after":{
                      "organizationId":"%s","id":"%s","provider":"%s","credentialMode":"%s",
                      "name":"Fixture connection","options":%s,"state":"ENABLED","revision":"1",
                      "credentialRevision":"1","createdAt":"2026-11-06T06:00:00.123456Z",
                      "updatedAt":"2026-11-06T06:00:00.123456Z"
                    },"checkId":null
                  }
                  """
                      .formatted(
                          org,
                          command,
                          connection,
                          actor,
                          org,
                          connection,
                          profile.provider(),
                          profile.credentialMode(),
                          options)));
      for (var response : new MockHttpServletResponse[] {first, replay}) {
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Retry-After")).isNull();
      }
    }
  }

  @Test
  void invalidReceiptAndUnknownOutcomeNeverExposeSuccessData() throws Exception {
    var org = UUID.randomUUID();
    var id = UUID.randomUUID();
    var time = OffsetDateTime.parse("2026-11-06T06:00:00.123456Z");
    var after = snapshot(org, id, Provider.OPENAI, CredentialMode.OPENAI_API_KEY, null, time);
    var paddedName =
        new Configuration(
            org,
            id,
            Provider.OPENAI,
            CredentialMode.OPENAI_API_KEY,
            " Padded ",
            null,
            "ENABLED",
            1,
            1,
            time,
            time);
    for (var outcome :
        new WriteOutcome[] {
          committed(new Receipt(org, id, Operation.RENAME, id, id, time, null, after), false),
          committed(new Receipt(org, id, Operation.CREATE, id, id, time, after, after), false),
          committed(new Receipt(org, id, Operation.CREATE, id, id, time, null, null), false),
          committed(
              new Receipt(UUID.randomUUID(), id, Operation.CREATE, id, id, time, null, after),
              true),
          committed(
              new Receipt(org, id, Operation.CREATE, id, id, time.minusNanos(1000), null, after),
              true),
          committed(new Receipt(org, id, Operation.CREATE, id, id, time, null, paddedName), true),
          new WriteOutcome(WriteState.UNKNOWN, null, new Failure(Code.COMMAND_OUTCOME_UNKNOWN))
        }) {
      var response = new MockHttpServletResponse();
      ProviderCreateResponses.writeOutcome(json, response, outcome);
      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(envelope(response).path("error").path("code").asText())
          .isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
      assertThat(response.getContentAsString()).doesNotContain(id.toString(), "Fixture connection");
    }
  }

  @Test
  void receiptLookupPreservesExplicitNullAndRejectsUnsupportedOperations() throws Exception {
    var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    var absent = new MockHttpServletResponse();
    ProviderCreateResponses.writeLookup(
        mapper, absent, new LookupOutcome(LookupState.ABSENT, null, null));
    assertThat(absent.getStatus()).isEqualTo(200);
    assertThat(absent.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(json.readTree(absent.getContentAsByteArray()).path("data"))
        .isEqualTo(json.readTree("{\"receipt\":null}"));

    var id = UUID.randomUUID();
    var time = OffsetDateTime.parse("2026-11-06T06:00:00.123456Z");
    var after = snapshot(id, id, Provider.OPENAI, CredentialMode.OPENAI_API_KEY, null, time);
    var receipt = new Receipt(id, id, Operation.RENAME, id, id, time, after, after);
    var rejected = new MockHttpServletResponse();
    ProviderCreateResponses.writeLookup(
        json, rejected, new LookupOutcome(LookupState.FOUND, receipt, null));
    assertThat(rejected.getStatus()).isEqualTo(503);
    assertThat(envelope(rejected).at("/error/code").asText())
        .isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(rejected.getContentAsString()).doesNotContain(id.toString(), "Fixture connection");
  }

  private static Configuration snapshot(
      UUID org,
      UUID id,
      Provider provider,
      CredentialMode mode,
      String workspace,
      OffsetDateTime time) {
    return new Configuration(
        org, id, provider, mode, "Fixture connection", workspace, "ENABLED", 1, 1, time, time);
  }

  private static WriteOutcome committed(Receipt receipt, boolean replayed) {
    return new WriteOutcome(WriteState.COMMITTED, new Result(receipt, replayed), null);
  }

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
            Map.entry(Code.CREDENTIAL_UNAVAILABLE, "409:CREDENTIAL_UNAVAILABLE"),
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

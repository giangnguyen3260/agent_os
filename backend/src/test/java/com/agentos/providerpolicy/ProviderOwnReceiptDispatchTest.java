package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providerreceipts.ProviderReceiptService.Configuration;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providertransport.ProviderCreateResponses;
import com.agentos.providertransport.ProviderRenameResponses;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Pure dispatch contracts; current authority and session checks remain in existing HTTP tests. */
class ProviderOwnReceiptDispatchTest {
  private final ObjectMapper json =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
  private final UUID org = UUID.randomUUID(),
      actor = UUID.randomUUID(),
      connection = UUID.randomUUID(),
      command = UUID.randomUUID();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-11-08T01:02:03.123456Z");

  @Test
  void createAndRenameDispatchToTheirExactHistoricalProjection() throws Exception {
    for (Operation operation : new Operation[] {Operation.CREATE, Operation.RENAME}) {
      var before = snapshot("Original", 1);
      var after = operation == Operation.CREATE ? before : snapshot("Historical renamed", 2);
      var receipt =
          new Receipt(
              org,
              command,
              operation,
              connection,
              actor,
              time,
              operation == Operation.CREATE ? null : before,
              after);
      var outcome = new LookupOutcome(LookupState.FOUND, receipt, null);
      var dispatched = new MockHttpServletResponse();
      var direct = new MockHttpServletResponse();
      ProviderCreateReceiptController.writeLookup(json, dispatched, outcome, true);
      if (operation == Operation.CREATE) ProviderCreateResponses.writeLookup(json, direct, outcome);
      else ProviderRenameResponses.writeLookup(json, direct, outcome);
      assertThat(dispatched.getStatus()).isEqualTo(200);
      var data = envelope(dispatched).path("data");
      assertThat(data.properties()).extracting(Map.Entry::getKey).containsExactly("receipt");
      assertThat(data).isEqualTo(envelope(direct).path("data"));
      assertThat(data.at("/receipt/operation").asText()).isEqualTo(operation.name());
      assertThat(data.at("/receipt/actorId").asText()).isEqualTo(actor.toString());
      assertThat(data.at("/receipt/after/name").asText()).isEqualTo(after.name());
      assertThat(data.at("/receipt/checkId").isNull()).isTrue();
      assertThat(data.at("/receipt/before").isNull()).isEqualTo(operation == Operation.CREATE);
      assertThat(dispatched.getContentAsString())
          .doesNotContain(
              "apiKey",
              "externalLocator",
              "externalKeyVersion",
              "verifier",
              "digest",
              "eventId",
              "idempotencyKey",
              "health",
              "usable");
    }
  }

  @Test
  void unsupportedAndMalformedFoundReceiptsFailClosedWithoutFallbackOrSensitiveEcho()
      throws Exception {
    for (Operation operation : Operation.values()) {
      if (operation == Operation.CREATE || operation == Operation.RENAME) continue;
      unavailable(
          dispatch(
              new LookupOutcome(
                  LookupState.FOUND,
                  new Receipt(
                      org,
                      command,
                      operation,
                      connection,
                      actor,
                      time,
                      snapshot("secret-marker", 1),
                      snapshot("private-internal-marker", 2)),
                  null)));
    }
    for (Receipt invalid :
        new Receipt[] {
          new Receipt(
              org,
              command,
              null,
              connection,
              actor,
              time,
              snapshot("secret-marker", 1),
              snapshot("private-internal-marker", 2)),
          new Receipt(
              org,
              command,
              Operation.CREATE,
              connection,
              actor,
              time,
              snapshot("secret-marker", 1),
              snapshot("private-internal-marker", 2)),
          new Receipt(
              org,
              command,
              Operation.RENAME,
              connection,
              actor,
              time,
              null,
              snapshot("private-internal-marker", 2)),
          new Receipt(
              org,
              command,
              Operation.RENAME,
              connection,
              actor,
              time,
              snapshot("secret-marker", 1),
              snapshot("private-internal-marker", 9))
        }) unavailable(dispatch(new LookupOutcome(LookupState.FOUND, invalid, null)));
  }

  @Test
  void createOnlyDeploymentKeepsRenameUnsupportedWhileCreateRemainsReadable() throws Exception {
    var before = snapshot("Original", 1);
    var renamed =
        new LookupOutcome(
            LookupState.FOUND,
            new Receipt(
                org,
                command,
                Operation.RENAME,
                connection,
                actor,
                time,
                before,
                snapshot("Renamed", 2)),
            null);
    var rejected = new MockHttpServletResponse();
    ProviderCreateReceiptController.writeLookup(json, rejected, renamed, false);
    unavailable(rejected);
    var created =
        new LookupOutcome(
            LookupState.FOUND,
            new Receipt(org, command, Operation.CREATE, connection, actor, time, null, before),
            null);
    var accepted = new MockHttpServletResponse();
    ProviderCreateReceiptController.writeLookup(json, accepted, created, false);
    assertThat(accepted.getStatus()).isEqualTo(200);
    assertThat(envelope(accepted).at("/data/receipt/operation").asText()).isEqualTo("CREATE");
  }

  @Test
  void absenceStaysExplicitNullAndUnavailableNeverBecomesAbsence() throws Exception {
    var absent = dispatch(new LookupOutcome(LookupState.ABSENT, null, null));
    assertThat(absent.getStatus()).isEqualTo(200);
    assertThat(envelope(absent).path("data")).isEqualTo(json.readTree("{\"receipt\":null}"));
    unavailable(dispatch(null));
    unavailable(
        dispatch(
            new LookupOutcome(
                LookupState.UNAVAILABLE, null, new Failure(Code.LOOKUP_UNAVAILABLE))));
  }

  @Test
  void authorityFailuresRetainSafePublicStatusAndNoReceipt() throws Exception {
    for (Code code : new Code[] {Code.UNAUTHORIZED, Code.FORBIDDEN, Code.INVALID_KEY}) {
      var response = dispatch(new LookupOutcome(LookupState.REJECTED, null, new Failure(code)));
      assertThat(response.getStatus())
          .isEqualTo(code == Code.UNAUTHORIZED ? 401 : code == Code.FORBIDDEN ? 403 : 400);
      var body = envelope(response);
      assertThat(body.has("data")).isFalse();
      assertThat(body.path("error").path("details").size()).isZero();
      assertThat(response.getContentAsString())
          .doesNotContain("receipt", org.toString(), actor.toString());
    }
  }

  private Configuration snapshot(String name, long revision) {
    return new Configuration(
        org,
        connection,
        Provider.OPENAI,
        CredentialMode.OPENAI_API_KEY,
        name,
        null,
        "ENABLED",
        revision,
        1,
        time,
        time);
  }

  private MockHttpServletResponse dispatch(LookupOutcome result) throws Exception {
    var response = new MockHttpServletResponse();
    ProviderCreateReceiptController.writeLookup(json, response, result, true);
    return response;
  }

  private JsonNode envelope(MockHttpServletResponse response) throws Exception {
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentType()).isEqualTo("application/json");
    assertThat(response.getHeader("Retry-After")).isNull();
    var body = json.readTree(response.getContentAsByteArray());
    assertThat(body.properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder(
            body.has("data") ? new String[] {"data", "meta"} : new String[] {"error", "meta"});
    assertThat(body.path("meta").properties())
        .extracting(Map.Entry::getKey)
        .containsExactly("requestId");
    assertThat(UUID.fromString(body.at("/meta/requestId").asText()).version()).isEqualTo(4);
    return body;
  }

  private void unavailable(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(503);
    var body = envelope(response);
    assertThat(body.at("/error/code").asText()).isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(body.has("data")).isFalse();
    assertThat(response.getContentAsString())
        .doesNotContain(
            "secret-marker",
            "private-internal-marker",
            org.toString(),
            command.toString(),
            actor.toString());
  }
}

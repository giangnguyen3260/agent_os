package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;

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

class ProviderDisableResponsesTest {
  private final ObjectMapper json =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
  private final UUID org = UUID.randomUUID(),
      id = UUID.randomUUID(),
      actor = UUID.randomUUID(),
      command = UUID.randomUUID();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-11-11T13:00:00.123456+07:00");

  @Test
  void committedAndReplayHaveTheExactHistoricalConfigurationProjection() throws Exception {
    var receipt =
        receipt(
            Operation.DISABLE,
            snapshot("Fixture", "ENABLED", Long.MAX_VALUE - 1, 1, id, time),
            snapshot("Fixture", "DISABLED", Long.MAX_VALUE, 1, id, time.plusNanos(1000)));
    var first = write(receipt, false);
    var replay = write(receipt, true);
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(replay.getStatus()).isEqualTo(200);
    var data = body(first).path("data");
    assertThat(body(replay).path("data")).isEqualTo(data);
    assertThat(data.properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder(
            "organizationId",
            "commandId",
            "operation",
            "connectionId",
            "actorId",
            "committedAt",
            "before",
            "after",
            "checkId");
    assertThat(data.path("operation").asText()).isEqualTo("DISABLE");
    assertThat(data.path("organizationId").asText()).isEqualTo(org.toString());
    assertThat(data.path("commandId").asText()).isEqualTo(command.toString());
    assertThat(data.path("connectionId").asText()).isEqualTo(id.toString());
    assertThat(data.path("actorId").asText()).isEqualTo(actor.toString());
    assertThat(data.path("committedAt").asText()).isEqualTo("2026-11-11T06:00:01.123456Z");
    assertThat(data.path("checkId").isNull()).isTrue();
    for (String side : new String[] {"before", "after"}) {
      var row = data.path(side);
      assertThat(row.properties())
          .extracting(Map.Entry::getKey)
          .containsExactlyInAnyOrder(
              "organizationId",
              "id",
              "provider",
              "credentialMode",
              "name",
              "options",
              "state",
              "revision",
              "credentialRevision",
              "createdAt",
              "updatedAt");
      assertThat(row.path("options")).isEqualTo(json.readTree("{\"workspaceId\":null}"));
      assertThat(row.path("name").asText()).isEqualTo("Fixture");
      assertThat(row.path("credentialRevision").asText()).isEqualTo("1");
      assertThat(row.path("createdAt").asText()).isEqualTo("2026-11-11T06:00:00.123456Z");
    }
    assertThat(data.at("/before/state").asText()).isEqualTo("ENABLED");
    assertThat(data.at("/after/state").asText()).isEqualTo("DISABLED");
    assertThat(data.at("/before/revision").asText()).isEqualTo(Long.toString(Long.MAX_VALUE - 1));
    assertThat(data.at("/after/revision").asText()).isEqualTo(Long.toString(Long.MAX_VALUE));
    assertThat(data.at("/after/updatedAt").asText()).isEqualTo("2026-11-11T06:00:00.123457Z");
    var lookup = new MockHttpServletResponse();
    ProviderDisableResponses.writeLookup(
        json, lookup, new LookupOutcome(LookupState.FOUND, receipt, null));
    assertThat(lookup.getStatus()).isEqualTo(200);
    assertThat(body(lookup).at("/data/receipt")).isEqualTo(data);
  }

  @Test
  void malformedAndUnsupportedTransitionsNeverExposeConfiguration() throws Exception {
    var before = snapshot("Fixture", "ENABLED", 7, 1, id, time);
    var after = snapshot("Fixture", "DISABLED", 8, 1, id, time);
    for (var invalid :
        new Receipt[] {
          receipt(Operation.ENABLE, before, after),
          receipt(Operation.RENAME, before, after),
          receipt(Operation.DISABLE, after, before),
          receipt(Operation.DISABLE, null, after),
          receipt(Operation.DISABLE, before, snapshot("Fixture", "ENABLED", 8, 1, id, time)),
          receipt(
              Operation.DISABLE, before, snapshot("private-marker", "DISABLED", 8, 1, id, time)),
          receipt(Operation.DISABLE, before, snapshot("Fixture", "DISABLED", 9, 1, id, time)),
          receipt(Operation.DISABLE, before, snapshot("Fixture", "DISABLED", 8, 2, id, time)),
          receipt(
              Operation.DISABLE,
              before,
              snapshot("Fixture", "DISABLED", 8, 1, UUID.randomUUID(), time)),
          receipt(
              Operation.DISABLE,
              before,
              snapshot("Fixture", "DISABLED", 8, 1, id, time.minusNanos(1000)))
        }) {
      unavailable(write(invalid, false));
      var lookup = new MockHttpServletResponse();
      ProviderDisableResponses.writeLookup(
          json, lookup, new LookupOutcome(LookupState.FOUND, invalid, null));
      unavailable(lookup);
    }
  }

  @Test
  void unknownIsSafeAndLookupAbsenceIsExplicit() throws Exception {
    var unknown = new MockHttpServletResponse();
    ProviderDisableResponses.writeOutcome(
        json,
        unknown,
        new WriteOutcome(WriteState.UNKNOWN, null, new Failure(Code.COMMAND_OUTCOME_UNKNOWN)));
    unavailable(unknown);
    var absent = new MockHttpServletResponse();
    ProviderDisableResponses.writeLookup(
        json, absent, new LookupOutcome(LookupState.ABSENT, null, null));
    assertThat(absent.getStatus()).isEqualTo(200);
    assertThat(body(absent).path("data")).isEqualTo(json.readTree("{\"receipt\":null}"));
  }

  private Configuration snapshot(
      String name,
      String state,
      long revision,
      long credential,
      UUID target,
      OffsetDateTime updated) {
    return new Configuration(
        org,
        target,
        Provider.ANTHROPIC,
        CredentialMode.ANTHROPIC_API_KEY,
        name,
        null,
        state,
        revision,
        credential,
        time,
        updated);
  }

  private Receipt receipt(Operation operation, Configuration before, Configuration after) {
    return new Receipt(org, command, operation, id, actor, time.plusSeconds(1), before, after);
  }

  private MockHttpServletResponse write(Receipt receipt, boolean replayed) throws Exception {
    var response = new MockHttpServletResponse();
    ProviderDisableResponses.writeOutcome(
        json,
        response,
        new WriteOutcome(WriteState.COMMITTED, new Result(receipt, replayed), null));
    return response;
  }

  private JsonNode body(MockHttpServletResponse response) throws Exception {
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
    var body = body(response);
    assertThat(body.at("/error/code").asText()).isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(body.has("data")).isFalse();
    assertThat(response.getContentAsString())
        .doesNotContain("private-marker", "Fixture", "COMMAND_OUTCOME_UNKNOWN", id.toString());
  }
}

package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerreceipts.ProviderReceiptService.Configuration;
import com.agentos.providerreceipts.ProviderReceiptService.Receipt;
import com.agentos.providerreceipts.ProviderReceiptService.Result;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ProviderEnableTransportTest {
  private final UUID org = UUID.randomUUID();
  private final UUID id = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-11-11T06:00:00.123456Z");
  private final ObjectMapper json =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

  @Test
  void parsesAnEnableCommandFromTheExactRevisionObject() {
    var command =
        ProviderEnableBody.read(id, input("{\"expectedRevision\":\"9223372036854775807\"}"));
    assertThat(command.operation()).isEqualTo(Operation.ENABLE);
    assertThat(command.target()).isEqualTo(id);
    assertThat(command.expectedRevision()).isEqualTo(Long.MAX_VALUE);
    for (String body :
        new String[] {
          "{\"expectedRevision\":1}",
          "{\"expectedRevision\":\"01\"}",
          "{\"expectedRevision\":\"1\",\"expectedRevision\":\"1\"}",
          "{\"expectedRevision\":\"1\",\"operation\":\"DISABLE\"}",
          "{}"
        }) {
      assertThatThrownBy(() -> ProviderEnableBody.read(id, input(body)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_INPUT");
    }
  }

  @Test
  void enableCommitReplayAndLookupUseTheSameHistoricalSnapshot() throws Exception {
    var receipt =
        receipt(
            Operation.ENABLE,
            snapshot("Fixture", "DISABLED", 7, 1),
            snapshot("Fixture", "ENABLED", 8, 1));
    var first = write(receipt, false);
    var replay = write(receipt, true);
    var lookup = lookup(receipt);
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(lookup.getStatus()).isEqualTo(200);
    assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
    var data = json.readTree(first.getContentAsByteArray()).path("data");
    assertThat(data.path("operation").asText()).isEqualTo("ENABLE");
    assertThat(data.at("/before/state").asText()).isEqualTo("DISABLED");
    assertThat(data.at("/after/state").asText()).isEqualTo("ENABLED");
    assertThat(data.at("/after/options/workspaceId").isNull()).isTrue();
    assertThat(data.at("/after/credentialRevision").asText()).isEqualTo("1");
    assertThat(data.path("checkId").isNull()).isTrue();
    assertThat(json.readTree(replay.getContentAsByteArray()).path("data")).isEqualTo(data);
    assertThat(json.readTree(lookup.getContentAsByteArray()).at("/data/receipt")).isEqualTo(data);
  }

  @Test
  void refusesWrongOperationDirectionOrConfigurationChanges() throws Exception {
    var before = snapshot("Fixture", "DISABLED", 7, 1);
    var after = snapshot("Fixture", "ENABLED", 8, 1);
    for (var invalid :
        new Receipt[] {
          receipt(Operation.DISABLE, before, after),
          receipt(
              Operation.ENABLE,
              snapshot("Fixture", "ENABLED", 7, 1),
              snapshot("Fixture", "DISABLED", 8, 1)),
          receipt(Operation.ENABLE, before, snapshot("Private changed name", "ENABLED", 8, 1)),
          receipt(Operation.ENABLE, before, snapshot("Fixture", "ENABLED", 8, 2))
        }) {
      for (var response : new MockHttpServletResponse[] {write(invalid, false), lookup(invalid)}) {
        assertThat(response.getStatus()).isEqualTo(503);
        var body = json.readTree(response.getContentAsByteArray());
        assertThat(body.has("data")).isFalse();
        assertThat(body.at("/error/code").asText()).isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
        assertThat(response.getContentAsString()).doesNotContain("Private changed name", "Fixture");
      }
    }
  }

  private Configuration snapshot(String name, String state, long revision, long credential) {
    return new Configuration(
        org,
        id,
        Provider.ANTHROPIC,
        CredentialMode.ANTHROPIC_API_KEY,
        name,
        null,
        state,
        revision,
        credential,
        time,
        time);
  }

  private Receipt receipt(Operation operation, Configuration before, Configuration after) {
    return new Receipt(
        org, UUID.randomUUID(), operation, id, actor, time.plusSeconds(1), before, after);
  }

  private MockHttpServletResponse write(Receipt receipt, boolean replay) throws Exception {
    var response = new MockHttpServletResponse();
    ProviderEnableResponses.writeOutcome(
        json, response, new WriteOutcome(WriteState.COMMITTED, new Result(receipt, replay), null));
    return response;
  }

  private MockHttpServletResponse lookup(Receipt receipt) throws Exception {
    var response = new MockHttpServletResponse();
    ProviderEnableResponses.writeLookup(
        json, response, new LookupOutcome(LookupState.FOUND, receipt, null));
    return response;
  }

  private static ByteArrayInputStream input(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }
}

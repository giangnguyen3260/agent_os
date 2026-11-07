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
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ProviderRenameResponsesTest {
  private final ObjectMapper json =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
  private final UUID org = UUID.randomUUID(), id = UUID.randomUUID(), actor = UUID.randomUUID();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-11-06T13:00:00.123456+07:00");

  @Test
  void firstAndReplayHaveExactHistoricalAllowlistForEveryProviderAndState() throws Exception {
    for (Provider provider : Provider.values()) {
      for (String state : new String[] {"ENABLED", "DISABLED"}) {
        for (String workspace :
            provider == Provider.ANTHROPIC
                ? new String[] {null, "wrkspc_test"}
                : new String[] {null}) {
          var before = snapshot(provider, workspace, state, "Before", 7, 3);
          var after = change(change(before, 4, "After"), 7, 8L);
          var receipt = receipt(before, after);
          var first = write(receipt, false);
          var replay = write(receipt, true);
          assertThat(first.getStatus()).isEqualTo(201);
          assertThat(replay.getStatus()).isEqualTo(200);
          var data = envelope(first).path("data");
          assertThat(envelope(replay).path("data")).isEqualTo(data);
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
          assertThat(data.path("operation").asText()).isEqualTo("RENAME");
          assertThat(data.path("organizationId").asText()).isEqualTo(org.toString());
          assertThat(data.path("actorId").asText()).isEqualTo(actor.toString());
          assertThat(data.path("connectionId").asText()).isEqualTo(id.toString());
          assertThat(data.path("commandId").asText()).isEqualTo(receipt.commandId().toString());
          assertThat(data.path("committedAt").asText()).isEqualTo("2026-11-06T06:00:01.123456Z");
          assertThat(data.path("checkId").isNull()).isTrue();
          for (String side : new String[] {"before", "after"}) {
            var snapshot = data.path(side);
            assertThat(snapshot.properties())
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
            assertThat(snapshot.path("organizationId").asText()).isEqualTo(org.toString());
            assertThat(snapshot.path("id").asText()).isEqualTo(id.toString());
            assertThat(snapshot.path("provider").asText()).isEqualTo(provider.name());
            assertThat(snapshot.path("credentialMode").asText())
                .isEqualTo(before.credentialMode().name());
            assertThat(snapshot.path("state").asText()).isEqualTo(state);
            assertThat(snapshot.path("name").asText())
                .isEqualTo(side.equals("before") ? "Before" : "After");
            assertThat(snapshot.path("revision").asText())
                .isEqualTo(side.equals("before") ? "7" : "8");
            assertThat(snapshot.path("credentialRevision").asText()).isEqualTo("3");
            assertThat(snapshot.path("createdAt").asText())
                .isEqualTo("2026-11-06T06:00:00.123456Z");
            assertThat(snapshot.path("updatedAt").asText())
                .isEqualTo("2026-11-06T06:00:00.123456Z");
            assertThat(snapshot.path("options"))
                .isEqualTo(
                    json.readTree(
                        provider != Provider.ANTHROPIC
                            ? "{}"
                            : workspace == null
                                ? "{\"workspaceId\":null}"
                                : "{\"workspaceId\":\"wrkspc_test\"}"));
          }
          assertThat(first.getContentAsString())
              .doesNotContain(
                  "apiKey", "externalLocator", "verifier", "health", "usable", "metadata");
        }
      }
    }
  }

  @Test
  void malformedSnapshotsScopeRevisionsAndChronologyFailClosed() throws Exception {
    var before = snapshot(Provider.OPENAI, null, "ENABLED", "Before", 7, 3);
    var after = change(change(before, 4, "After"), 7, 8L);
    var invalid = new ArrayList<Receipt>();
    for (Configuration bad :
        new Configuration[] {
          null,
          change(after, 0, UUID.randomUUID()),
          change(after, 1, UUID.randomUUID()),
          change(after, 2, null),
          change(after, 3, CredentialMode.GEMINI_API_KEY),
          change(after, 4, null),
          change(after, 4, " After "),
          change(after, 4, "Before"),
          change(after, 4, ""),
          change(after, 5, "secret-in-unsupported-workspace"),
          change(after, 6, "DELETED"),
          change(after, 6, "DISABLED"),
          change(after, 7, 0L),
          change(after, 7, 9L),
          change(after, 8, 0L),
          change(after, 8, 4L),
          change(after, 9, null),
          change(after, 9, time.minusSeconds(1)),
          change(after, 10, null),
          change(after, 10, time.minusSeconds(1)),
          change(after, 10, time.plusSeconds(2)),
          change(after, 9, OffsetDateTime.parse("0000-01-01T00:00:00Z")),
          change(after, 10, OffsetDateTime.parse("+10000-01-01T00:00:00Z"))
        }) invalid.add(receipt(before, bad));
    invalid.add(receipt(null, after));
    invalid.add(receipt(before, snapshot(Provider.GOOGLE_GEMINI, null, "ENABLED", "After", 8, 3)));
    var anthropic = snapshot(Provider.ANTHROPIC, "wrkspc_test", "ENABLED", "Before", 7, 3);
    invalid.add(
        receipt(
            anthropic, change(change(change(anthropic, 4, "After"), 7, 8L), 5, "wrkspc_changed")));
    invalid.add(
        receipt(
            anthropic,
            change(change(change(anthropic, 4, "After"), 7, 8L), 5, "invalid-workspace")));
    invalid.add(new Receipt(org, id, null, id, actor, time, before, after));
    invalid.add(new Receipt(org, id, Operation.RENAME, new UUID(0, 0), actor, time, before, after));
    invalid.add(
        new Receipt(
            org,
            id,
            Operation.RENAME,
            id,
            UUID.fromString("00000000-0000-6000-8000-000000000000"),
            time,
            before,
            after));
    invalid.add(
        new Receipt(
            org,
            id,
            Operation.RENAME,
            id,
            actor,
            OffsetDateTime.parse("+10000-01-01T00:00:00Z"),
            before,
            after));
    invalid.add(
        new Receipt(
            org,
            id,
            Operation.RENAME,
            id,
            actor,
            OffsetDateTime.parse("0000-01-01T00:00:00Z"),
            before,
            after));
    invalid.add(receipt(change(before, 8, 8L), change(after, 8, 8L)));
    invalid.add(receipt(change(before, 7, Long.MAX_VALUE), change(after, 7, Long.MIN_VALUE)));
    invalid.add(receipt(change(before, 10, time.minusNanos(1)), after));
    invalid.add(new Receipt(org, id, Operation.CREATE, id, actor, time, before, after));
    invalid.add(
        new Receipt(UUID.randomUUID(), id, Operation.RENAME, id, actor, time, before, after));
    invalid.add(new Receipt(org, new UUID(0, 0), Operation.RENAME, id, actor, time, before, after));
    invalid.add(new Receipt(org, id, Operation.RENAME, id, null, time, before, after));
    invalid.add(new Receipt(org, id, Operation.RENAME, id, actor, null, before, after));
    invalid.add(
        new Receipt(org, id, Operation.RENAME, id, actor, time.minusNanos(1), before, after));
    for (Receipt receipt : invalid) unavailable(write(receipt, false));
    var malformedName = change(after, 4, "secret-marker ");
    assertThat(write(receipt(before, malformedName), false).getContentAsString())
        .doesNotContain("secret-marker");
  }

  @Test
  void maxAdjacentRevisionAndEquivalentOffsetInstantsAreSupported() throws Exception {
    var before =
        snapshot(
            Provider.ANTHROPIC,
            "wrkspc_test",
            "DISABLED",
            "Before",
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE - 1);
    var after =
        change(
            change(change(before, 4, "After"), 7, Long.MAX_VALUE),
            9,
            time.withOffsetSameInstant(java.time.ZoneOffset.UTC));
    var response = write(receipt(before, after), false);
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(envelope(response).at("/data/after/revision").asText())
        .isEqualTo(Long.toString(Long.MAX_VALUE));
  }

  @Test
  void unknownAndErrorsUseExistingSafeMappingWithoutSuccessOrProvisionalData() throws Exception {
    for (WriteOutcome outcome :
        new WriteOutcome[] {
          null,
          new WriteOutcome(WriteState.UNKNOWN, null, new Failure(Code.COMMAND_OUTCOME_UNKNOWN)),
          new WriteOutcome(WriteState.UNAVAILABLE, null, new Failure(Code.COMMAND_UNAVAILABLE))
        }) {
      var response = new MockHttpServletResponse();
      ProviderRenameResponses.writeOutcome(json, response, outcome);
      unavailable(response);
    }
    var rate = new MockHttpServletResponse();
    ProviderRenameResponses.writeOutcome(
        json,
        rate,
        new WriteOutcome(WriteState.REJECTED, null, new Failure(Code.RATE_LIMITED, 60)));
    assertThat(rate.getStatus()).isEqualTo(429);
    assertThat(rate.getHeader("Retry-After")).isEqualTo("60");
    assertThat(envelope(rate).at("/error/code").asText()).isEqualTo("RATE_LIMITED");
  }

  @Test
  void ownLookupPreservesExplicitAbsenceAndRejectsCreateReceipt() throws Exception {
    var absent = new MockHttpServletResponse();
    ProviderRenameResponses.writeLookup(
        json, absent, new LookupOutcome(LookupState.ABSENT, null, null));
    assertThat(absent.getStatus()).isEqualTo(200);
    assertThat(envelope(absent).path("data")).isEqualTo(json.readTree("{\"receipt\":null}"));
    var before = snapshot(Provider.OPENAI, null, "ENABLED", "Before", 7, 3);
    var receipt = receipt(before, change(change(before, 4, "After"), 7, 8L));
    var found = new MockHttpServletResponse();
    ProviderRenameResponses.writeLookup(
        json, found, new LookupOutcome(LookupState.FOUND, receipt, null));
    assertThat(envelope(found).at("/data/receipt"))
        .isEqualTo(envelope(write(receipt, true)).path("data"));
    var rejected = new MockHttpServletResponse();
    ProviderRenameResponses.writeLookup(
        json,
        rejected,
        new LookupOutcome(
            LookupState.FOUND,
            new Receipt(org, id, Operation.CREATE, id, actor, time, null, before),
            null));
    unavailable(rejected);
    var missing = new MockHttpServletResponse();
    ProviderRenameResponses.writeLookup(json, missing, null);
    unavailable(missing);
    var untouched = new MockHttpServletResponse();
    assertThatThrownBy(() -> ProviderRenameResponses.writeOutcome(null, untouched, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause();
    assertThat(untouched.getHeaderNames()).isEmpty();
    assertThat(untouched.getContentAsByteArray()).isEmpty();
  }

  private Configuration snapshot(
      Provider provider,
      String workspace,
      String state,
      String name,
      long revision,
      long credential) {
    CredentialMode mode =
        switch (provider) {
          case OPENAI -> CredentialMode.OPENAI_API_KEY;
          case ANTHROPIC -> CredentialMode.ANTHROPIC_API_KEY;
          case GOOGLE_GEMINI -> CredentialMode.GEMINI_API_KEY;
        };
    return new Configuration(
        org, id, provider, mode, name, workspace, state, revision, credential, time, time);
  }

  private static Configuration change(Configuration value, int index, Object replacement) {
    Object[] fields = {
      value.organizationId(),
      value.id(),
      value.provider(),
      value.credentialMode(),
      value.name(),
      value.workspaceId(),
      value.state(),
      value.revision(),
      value.credentialRevision(),
      value.createdAt(),
      value.updatedAt()
    };
    fields[index] = replacement;
    return new Configuration(
        (UUID) fields[0],
        (UUID) fields[1],
        (Provider) fields[2],
        (CredentialMode) fields[3],
        (String) fields[4],
        (String) fields[5],
        (String) fields[6],
        (Long) fields[7],
        (Long) fields[8],
        (OffsetDateTime) fields[9],
        (OffsetDateTime) fields[10]);
  }

  private Receipt receipt(Configuration before, Configuration after) {
    return new Receipt(
        org, UUID.randomUUID(), Operation.RENAME, id, actor, time.plusSeconds(1), before, after);
  }

  private MockHttpServletResponse write(Receipt receipt, boolean replayed) throws Exception {
    var response = new MockHttpServletResponse();
    ProviderRenameResponses.writeOutcome(
        json,
        response,
        new WriteOutcome(WriteState.COMMITTED, new Result(receipt, replayed), null));
    return response;
  }

  private JsonNode envelope(MockHttpServletResponse response) throws Exception {
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentType()).isEqualTo("application/json");
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
    assertThat(response.getHeader("Retry-After")).isNull();
    var body = envelope(response);
    assertThat(body.at("/error/code").asText()).isEqualTo("PROVIDER_CONNECTION_UNAVAILABLE");
    assertThat(body.path("error").properties())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder("code", "message", "details");
    assertThat(body.at("/error/details").isEmpty()).isTrue();
    assertThat(response.getContentAsString())
        .doesNotContain(
            org.toString(),
            id.toString(),
            "Before",
            "After",
            "data",
            "receipt",
            "COMMAND_OUTCOME_UNKNOWN");
  }
}

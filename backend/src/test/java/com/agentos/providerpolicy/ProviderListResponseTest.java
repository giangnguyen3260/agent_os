package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderListResponseTest {
  private final UUID organization = UUID.randomUUID();
  private final UUID connection = UUID.randomUUID();

  @Test
  void serializesOnlyTheAllowlistedFieldsAndExplicitNulls() throws Exception {
    var page = new ProviderListSnapshot.Page(1, 0, List.of(row("Connection", false)));
    var json =
        new ObjectMapper().valueToTree(ProviderListResponse.fromSnapshot(organization, page));
    assertThat(fields(json))
        .containsExactlyInAnyOrder("organizationId", "page", "pageSize", "total", "items");
    var item = json.get("items").get(0);
    assertThat(fields(item))
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
            "credentialState",
            "credentialMask",
            "createdAt",
            "updatedAt");
    assertThat(item.get("revision").isTextual()).isTrue();
    assertThat(item.get("options").get("workspaceId").isNull()).isTrue();
    assertThat(item.get("credentialMask").isNull()).isTrue();
    assertThat(item.get("credentialState").asText()).isEqualTo("UNAVAILABLE");
    assertThat(item.get("createdAt").asText()).isEqualTo("2026-11-03T00:00:00.000000Z");
  }

  @Test
  void rejectsTheWholeProjectionWhenSelectedConfigurationIsMalformed() {
    var page = new ProviderListSnapshot.Page(1, 0, List.of(row("Invalid\nname", false)));
    assertThatThrownBy(() -> ProviderListResponse.fromSnapshot(organization, page))
        .isInstanceOfSatisfying(
            PolicyFailure.class, error -> assertThat(error.status()).isEqualTo(503));
  }

  @Test
  void usesOnlyTheFixedMaskWhenSnapshotHasEvidence() {
    var page = new ProviderListSnapshot.Page(1, 0, List.of(row("Connection", true)));
    var item = ProviderListResponse.fromSnapshot(organization, page).items().getFirst();
    assertThat(item.credentialState()).isEqualTo("PRESENT");
    assertThat(item.credentialMask()).isEqualTo("••••••••");
    assertThat(item.state()).isEqualTo("DISABLED");
  }

  private static List<String> fields(JsonNode node) {
    var names = new java.util.ArrayList<String>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private ProviderListSnapshot.Row row(String name, boolean available) {
    var time = OffsetDateTime.parse("2026-11-03T07:00:00+07:00");
    return new ProviderListSnapshot.Row(
        organization,
        connection,
        "ANTHROPIC",
        "ANTHROPIC_API_KEY",
        name,
        null,
        "DISABLED",
        2,
        1,
        time,
        time,
        available);
  }
}

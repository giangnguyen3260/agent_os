package com.agentos.providerconnections;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercredentials.CredentialBoundary.Metadata;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Independent complete-row oracle: only generated event identity and database times are captured.
 */
final class ProviderConnectionExpectedRows {
  private final JdbcTemplate jdbc;
  private final UUID org;
  private final UUID id;
  private final UUID actor;
  private Map<String, Object> parent;
  private final List<Map<String, Object>> versions = new ArrayList<>();
  private final List<Map<String, Object>> events = new ArrayList<>();

  ProviderConnectionExpectedRows(JdbcTemplate jdbc, UUID org, UUID id, UUID actor) {
    this.jdbc = jdbc;
    this.org = org;
    this.id = id;
    this.actor = actor;
  }

  void accept(
      ProviderConnectionService.Connection result,
      Metadata metadata,
      Provider provider,
      String name,
      String workspace,
      String state,
      long revision,
      long credentialRevision,
      String kind) {
    String mode =
        switch (provider) {
          case OPENAI -> "OPENAI_API_KEY";
          case ANTHROPIC -> "ANTHROPIC_API_KEY";
          case GOOGLE_GEMINI -> "GEMINI_API_KEY";
        };
    Object created = parent == null ? result.createdAt() : parent.get("created_at");
    if (kind.equals("CREATE")) assertThat(result.updatedAt()).isEqualTo(result.createdAt());
    parent =
        row(
            "id",
            id,
            "organization_id",
            org,
            "provider",
            provider.name(),
            "mode",
            mode,
            "name",
            name,
            "workspace",
            workspace,
            "state",
            state,
            "configuration_revision",
            revision,
            "credential_revision",
            credentialRevision,
            "created_at",
            created,
            "updated_at",
            result.updatedAt());
    assertThat(result.organization()).isEqualTo(org);
    assertThat(result.id()).isEqualTo(id);
    assertThat(result.provider()).isEqualTo(provider);
    assertThat(result.mode()).isEqualTo(mode);
    assertThat(result.name()).isEqualTo(name);
    assertThat(result.workspace()).isEqualTo(workspace);
    assertThat(result.state()).isEqualTo(state);
    assertThat(result.revision()).isEqualTo(revision);
    assertThat(result.credentialRevision()).isEqualTo(credentialRevision);
    assertThat(result.metadata()).isEqualTo(metadata);
    assertThat(normalize(result.createdAt())).isEqualTo(normalize(created));
    if (kind.equals("CREATE") || kind.equals("ROTATE")) {
      versions.add(
          row(
              "organization_id",
              org,
              "connection_id",
              id,
              "provider",
              provider.name(),
              "credential_revision",
              credentialRevision,
              "external_locator",
              metadata.reference().locator(),
              "external_key_version",
              metadata.reference().keyVersion(),
              "hmac_key_version",
              metadata.macVersion(),
              "hmac_domain_version",
              "v2",
              "verifier",
              metadata.digest(),
              "created_at",
              result.updatedAt()));
    }
    UUID event =
        jdbc.queryForObject(
            "select id from provider_connection_events where organization_id=? and connection_id=?"
                + " and configuration_revision=?",
            UUID.class,
            org,
            id,
            revision);
    assertThat(event).isNotNull();
    events.add(
        row(
            "id",
            event,
            "organization_id",
            org,
            "connection_id",
            id,
            "configuration_revision",
            revision,
            "credential_revision",
            credentialRevision,
            "kind",
            kind,
            "provider",
            provider.name(),
            "mode",
            mode,
            "name",
            name,
            "workspace",
            workspace,
            "state",
            state,
            "actor_id",
            actor,
            "created_at",
            result.updatedAt()));
    assertMatches();
  }

  ProviderConnectionExpectedRows fork() {
    var copy = new ProviderConnectionExpectedRows(jdbc, org, id, actor);
    copy.parent = parent;
    copy.versions.addAll(versions);
    copy.events.addAll(events);
    return copy;
  }

  void assertMatches() {
    assertThat(rows("provider_connections")).containsExactly(parent);
    assertThat(rows("provider_credential_versions")).containsExactlyInAnyOrderElementsOf(versions);
    assertThat(rows("provider_connection_events")).containsExactlyInAnyOrderElementsOf(events);
  }

  private List<Map<String, Object>> rows(String table) {
    return jdbc.queryForList("select * from " + table + " where organization_id=?", org).stream()
        .map(ProviderConnectionExpectedRows::normalizedRow)
        .toList();
  }

  static Map<String, Object> normalizedRow(Map<String, Object> source) {
    var result = new LinkedHashMap<String, Object>();
    source.forEach((key, value) -> result.put(key, normalize(value)));
    return result;
  }

  static Object normalize(Object value) {
    if (value instanceof OffsetDateTime time) return time.toInstant();
    if (value instanceof Timestamp time) return time.toInstant();
    if (value instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
    return value;
  }

  private static Map<String, Object> row(Object... cells) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < cells.length; i += 2)
      result.put((String) cells[i], normalize(cells[i + 1]));
    return result;
  }
}

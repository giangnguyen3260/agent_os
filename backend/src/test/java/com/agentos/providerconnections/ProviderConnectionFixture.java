package com.agentos.providerconnections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Metadata;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared integration cases run against Spring proxies and migrated, real relational databases. */
abstract class ProviderConnectionFixture {
  static final String SENTINEL = "test-only-plaintext-sentinel-82613";
  @Autowired JdbcTemplate jdbc;
  @Autowired ProviderConnectionService service;
  @Autowired PlatformTransactionManager transactions;
  UUID org, actor, id;
  HumanIdentity human;
  int stores;

  @BeforeEach
  void seed() {
    org = UUID.randomUUID();
    actor = UUID.randomUUID();
    id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Provider"
            + " QC',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'QC','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    human = new HumanIdentity(actor, "unused@example.test", "QC", 0);
    assertThat(AopUtils.isAopProxy(service)).isTrue();
  }

  @AfterEach
  void cleanup() {
    jdbc.update("delete from provider_connection_events where organization_id=?", org);
    jdbc.update("delete from provider_connections where organization_id=?", org);
    jdbc.update("delete from provider_credential_versions where organization_id=?", org);
    jdbc.update("delete from organization_memberships where organization_id=?", org);
    jdbc.update("delete from human_accounts where id=?", actor);
    jdbc.update("delete from organizations where id=?", org);
  }

  Metadata prepared(UUID organization, UUID connection, Provider provider, long revision) {
    var boundary =
        new CredentialBoundary(
            new CredentialBoundary.Keys() {
              public String currentVersion() {
                return "test-hmac-v1";
              }

              public SecretKey key(String version) {
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            },
            new CredentialBoundary.Secrets() {
              public Reference store(Scope scope, byte[] value) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(new String(value, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo(SENTINEL);
                stores++;
                return new Reference(
                    scope, "test-only/" + UUID.randomUUID(), "immutable-v" + revision);
              }

              public byte[] read(Reference reference) {
                throw new AssertionError("Persistence must never read secrets");
              }

              public void preparationFailed(Scope scope, Reference reference) {
                throw new AssertionError("Unexpected preparation failure");
              }
            });
    return boundary.prepare(
        new Scope(organization, connection, provider, revision), SENTINEL.toCharArray());
  }

  ProviderConnectionService.Connection create() {
    return service.create(
        human, org, id, "Connection", null, prepared(org, id, Provider.OPENAI, 1));
  }

  void error(Runnable action, int status, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ProviderConnectionFailure.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(status);
              assertThat(ex.code()).isEqualTo(code);
              assertThat(ex.getCause()).isNull();
            });
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  Map<String, List<Map<String, Object>>> snapshot() {
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "provider_connections", "provider_credential_versions", "provider_connection_events"))
      result.put(
          table,
          jdbc.queryForList(
              "select * from " + table + " where organization_id=? order by 1,2,3", org));
    return result;
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void createReloadAndAllowlistedStorageForEveryProvider(Provider provider) {
    Metadata metadata = prepared(org, id, provider, 1);
    var before = existingTables();
    schemaAllowlist();
    var connection =
        service.create(
            human,
            org,
            id,
            "  Cafe\u0301 😀  ",
            provider == Provider.ANTHROPIC ? "wrkspc_123Abc" : null,
            metadata);
    var expected = new ProviderConnectionExpectedRows(jdbc, org, id, actor);
    expected.accept(
        connection,
        metadata,
        provider,
        "Cafe\u0301 😀",
        provider == Provider.ANTHROPIC ? "wrkspc_123Abc" : null,
        "ENABLED",
        1,
        1,
        "CREATE");
    assertThat(connection.name()).isEqualTo("Cafe\u0301 😀");
    assertThat(connection.revision()).isEqualTo(1);
    assertThat(connection.credentialRevision()).isEqualTo(1);
    assertThat(connection.state()).isEqualTo("ENABLED");
    assertThat(service.get(human, org, id)).isEqualTo(connection);
    assertThat(service.list(human, org, 0).items()).containsExactly(connection);
    assertThat(service.credentialVersion(human, org, id, 1)).isEqualTo(metadata);
    assertThat(stores).isEqualTo(1);
    assertThat(existingTables()).isEqualTo(before);
    assertThat(snapshot().toString())
        .doesNotContain(
            SENTINEL,
            java.util.Base64.getEncoder()
                .encodeToString(SENTINEL.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
            java.util.HexFormat.of()
                .formatHex(SENTINEL.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    var version =
        jdbc.queryForMap("select * from provider_credential_versions where organization_id=?", org);
    assertThat(version.get("external_locator")).isEqualTo(metadata.reference().locator());
    assertThat(version.get("external_key_version")).isEqualTo(metadata.reference().keyVersion());
    assertThat(version.get("hmac_key_version")).isEqualTo(metadata.macVersion());
    assertThat(version.get("hmac_domain_version")).isEqualTo("v2");
    assertThat(version.get("verifier")).isEqualTo(metadata.digest());
    var event =
        jdbc.queryForMap("select * from provider_connection_events where organization_id=?", org);
    assertThat(event.toString())
        .doesNotContain(
            metadata.digest(), metadata.reference().locator(), metadata.macVersion(), SENTINEL);
    assertThat(connection.toString()).isEqualTo("Connection[redacted]");
    assertThat(service.list(human, org, 0).toString()).isEqualTo("Page[redacted]");
    for (String table : snapshot().keySet()) assertThat(count(table)).isEqualTo(1);
  }

  Map<String, List<Map<String, Object>>> existingTables() {
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    var tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema=current_schema()"
                + " and table_type='BASE TABLE' order by table_name",
            String.class);
    for (String table : tables) {
      if (table.startsWith("provider_connection") || table.equals("provider_credential_versions"))
        continue;
      if (!table.matches("[a-z_0-9]+")) throw new AssertionError("Unexpected table identifier");
      var rows = new java.util.ArrayList<>(jdbc.queryForList("select * from " + table));
      rows.forEach(
          row ->
              row.replaceAll(
                  (column, value) ->
                      value instanceof byte[] bytes
                          ? java.util.Base64.getEncoder().encodeToString(bytes)
                          : value));
      rows.sort(java.util.Comparator.comparing(Object::toString));
      result.put(table, rows);
    }
    return result;
  }

  void schemaAllowlist() {
    Map<String, List<String>> expected =
        Map.of(
            "provider_connections",
                List.of(
                    "id",
                    "organization_id",
                    "provider",
                    "mode",
                    "name",
                    "workspace",
                    "state",
                    "configuration_revision",
                    "credential_revision",
                    "created_at",
                    "updated_at"),
            "provider_credential_versions",
                List.of(
                    "organization_id",
                    "connection_id",
                    "provider",
                    "credential_revision",
                    "external_locator",
                    "external_key_version",
                    "hmac_key_version",
                    "hmac_domain_version",
                    "verifier",
                    "created_at"),
            "provider_connection_events",
                List.of(
                    "id",
                    "organization_id",
                    "connection_id",
                    "configuration_revision",
                    "credential_revision",
                    "kind",
                    "provider",
                    "mode",
                    "name",
                    "workspace",
                    "state",
                    "actor_id",
                    "created_at"));
    expected.forEach(
        (table, columns) ->
            assertThat(
                    jdbc.queryForList(
                        "select column_name from information_schema.columns where"
                            + " table_schema=current_schema() and table_name=? order by"
                            + " ordinal_position",
                        String.class,
                        table))
                .containsExactlyElementsOf(columns));
  }

  @Test
  void mutationsRetainVersionsAndPreserveImmutableOptions() {
    var original =
        service.create(
            human, org, id, "Claude", "wrkspc_123", prepared(org, id, Provider.ANTHROPIC, 1));
    assertThat(service.rename(human, org, id, 1, "Renamed").revision()).isEqualTo(2);
    service.setEnabled(human, org, id, 2, false);
    var retained =
        jdbc.queryForList(
            "select * from provider_credential_versions where organization_id=? and"
                + " credential_revision=1",
            org);
    Metadata replacement = prepared(org, id, Provider.ANTHROPIC, 2);
    var rotated = service.rotate(human, org, id, 3, replacement);
    assertThat(rotated.name()).isEqualTo("Renamed");
    assertThat(rotated.workspace()).isEqualTo(original.workspace());
    assertThat(rotated.provider()).isEqualTo(original.provider());
    assertThat(rotated.mode()).isEqualTo(original.mode());
    assertThat(rotated.state()).isEqualTo("DISABLED");
    assertThat(rotated.credentialRevision()).isEqualTo(2);
    assertThat(service.credentialVersion(human, org, id, 1)).isEqualTo(original.metadata());
    assertThat(service.credentialVersion(human, org, id, 2)).isEqualTo(replacement);
    assertThat(service.setEnabled(human, org, id, 4, true).revision()).isEqualTo(5);
    assertThat(
            jdbc.queryForList(
                "select * from provider_credential_versions where organization_id=? and"
                    + " credential_revision=1",
                org))
        .isEqualTo(retained);
    assertThat(count("provider_credential_versions")).isEqualTo(2);
    assertThat(count("provider_connection_events")).isEqualTo(5);
    assertThat(
            jdbc.queryForList(
                "select kind from provider_connection_events where organization_id=? order by"
                    + " configuration_revision",
                String.class,
                org))
        .containsExactly("CREATE", "RENAME", "DISABLE", "ROTATE", "ENABLE");
    assertThat(
            jdbc.queryForList(
                "select configuration_revision from provider_connection_events where"
                    + " organization_id=? order by configuration_revision",
                Long.class,
                org))
        .containsExactly(1L, 2L, 3L, 4L, 5L);
    assertThat(
            jdbc.queryForList(
                "select credential_revision from provider_connection_events where organization_id=?"
                    + " order by configuration_revision",
                Long.class,
                org))
        .containsExactly(1L, 1L, 1L, 2L, 2L);
    assertThat(snapshot().toString())
        .doesNotContain(
            SENTINEL,
            java.util.Base64.getEncoder()
                .encodeToString(SENTINEL.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
            java.util.HexFormat.of()
                .formatHex(SENTINEL.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    assertThat(stores).isEqualTo(2);
  }

  @Test
  void duplicateNoopStaleAndInvalidRevisionHaveNoEffects() {
    create();
    var before = snapshot();
    var collateral = existingTables();
    Metadata duplicate = prepared(org, id, Provider.OPENAI, 1);
    error(
        () -> service.create(human, org, id, "Other", null, duplicate), 409, "CONNECTION_CONFLICT");
    error(() -> service.rename(human, org, id, 1, " Connection "), 409, "NO_CHANGE");
    error(() -> service.setEnabled(human, org, id, 1, true), 409, "NO_CHANGE");
    error(() -> service.rename(human, org, id, 2, "Changed"), 409, "REVISION_CONFLICT");
    error(() -> service.rename(human, org, id, 0, "Changed"), 400, "INVALID_REVISION");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @Test
  void staleRevisionWinsOverNormalizedNoop() {
    create();
    service.rename(human, org, id, 1, "Next");
    var before = snapshot();
    var collateral = existingTables();
    error(() -> service.rename(human, org, id, 1, " Next "), 409, "REVISION_CONFLICT");
    error(() -> service.setEnabled(human, org, id, 1, true), 409, "REVISION_CONFLICT");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "bad\nname",
        "bad\rname",
        "bad\u2028name",
        "bad\u2029name",
        "bad\u0000name",
        "\uD800",
        "\uDC00"
      })
  void invalidUnicodeNamesHaveNoEffects(String name) {
    Metadata metadata = prepared(org, id, Provider.OPENAI, 1);
    error(() -> service.create(human, org, id, name, null, metadata), 400, "INVALID_NAME");
    assertThat(count("provider_credential_versions")).isZero();
    assertThat(count("provider_connections")).isZero();
    assertThat(count("provider_connection_events")).isZero();
  }

  @Test
  void unicodeCodepointLimitAndWhitespaceNormalization() {
    var metadata = prepared(org, id, Provider.OPENAI, 1);
    error(
        () -> service.create(human, org, id, "😀".repeat(101), null, metadata),
        400,
        "INVALID_NAME");
    var created =
        service.create(
            human, org, id, "\ufeff\u00a0" + "😀".repeat(100) + "\u2003\ufeff", null, metadata);
    assertThat(created.name()).isEqualTo("😀".repeat(100));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrkspc_", "wrkspc_has-dash", "wrkspc_é", "wrkspc_ab\n", "other_abc"})
  void strictWorkspaceRejectsHeaderAndNonAsciiValues(String workspace) {
    var metadata = prepared(org, id, Provider.ANTHROPIC, 1);
    error(
        () -> service.create(human, org, id, "Name", workspace, metadata), 400, "INVALID_OPTIONS");
    assertThat(count("provider_credential_versions")).isZero();
  }

  @Test
  void workspaceRejectedForOtherProvidersAndLengthBounded() {
    for (Provider provider : List.of(Provider.OPENAI, Provider.GOOGLE_GEMINI)) {
      var metadata = prepared(org, id, provider, 1);
      error(
          () -> service.create(human, org, id, "Name", "wrkspc_A", metadata),
          400,
          "INVALID_OPTIONS");
    }
    var metadata = prepared(org, id, Provider.ANTHROPIC, 1);
    error(
        () -> service.create(human, org, id, "Name", "wrkspc_" + "a".repeat(129), metadata),
        400,
        "INVALID_OPTIONS");
    service.create(human, org, id, "Name", "wrkspc_" + "a".repeat(128), metadata);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORG", "ID", "PROVIDER", "REVISION"})
  void rotatedMetadataMustMatchExactScopeAndNextRevision(String wrong) {
    create();
    var before = snapshot();
    var collateral = existingTables();
    var metadata =
        prepared(
            wrong.equals("ORG") ? UUID.randomUUID() : org,
            wrong.equals("ID") ? UUID.randomUUID() : id,
            wrong.equals("PROVIDER") ? Provider.ANTHROPIC : Provider.OPENAI,
            wrong.equals("REVISION") ? 3 : 2);
    error(() -> service.rotate(human, org, id, 1, metadata), 400, "INVALID_METADATA");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @Test
  void creationRequiresRevisionOneAndExactScope() {
    for (Metadata metadata :
        List.of(
            prepared(org, id, Provider.OPENAI, 2),
            prepared(UUID.randomUUID(), id, Provider.OPENAI, 1),
            prepared(org, UUID.randomUUID(), Provider.OPENAI, 1)))
      error(() -> service.create(human, org, id, "Name", null, metadata), 400, "INVALID_METADATA");
    error(() -> service.create(human, org, id, "Name", null, null), 400, "INVALID_METADATA");
    assertThat(count("provider_credential_versions")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE", "MEMBERSHIP", "DISABLED", "CREDENTIAL"})
  void everyReadAndWriteRechecksCurrentAuthority(String revocation) {
    create();
    var before = snapshot();
    var collateral = existingTables();
    var metadata = prepared(org, id, Provider.OPENAI, 2);
    revoke(jdbc, revocation);
    collateral = existingTables();
    error(() -> service.get(human, org, id), 403, "FORBIDDEN");
    error(() -> service.list(human, org, 0), 403, "FORBIDDEN");
    error(() -> service.credentialVersion(human, org, id, 1), 403, "FORBIDDEN");
    error(() -> service.rename(human, org, id, 1, "Next"), 403, "FORBIDDEN");
    error(() -> service.rotate(human, org, id, 1, metadata), 403, "FORBIDDEN");
    error(() -> service.setEnabled(human, org, id, 1, false), 403, "FORBIDDEN");
    var fresh = prepared(org, UUID.randomUUID(), Provider.OPENAI, 1);
    error(
        () -> service.create(human, org, fresh.scope().connection(), "Name", null, fresh),
        403,
        "FORBIDDEN");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  void revoke(JdbcTemplate db, String revocation) {
    switch (revocation) {
      case "ROLE" ->
          db.update(
              "update organization_memberships set role='MEMBER' where organization_id=? and"
                  + " account_id=?",
              org,
              actor);
      case "MEMBERSHIP" ->
          db.update(
              "delete from organization_memberships where organization_id=? and account_id=?",
              org,
              actor);
      case "DISABLED" -> db.update("update human_accounts set enabled=false where id=?", actor);
      default ->
          db.update(
              "update human_accounts set credential_version=credential_version+1 where id=?",
              actor);
    }
  }

  @Test
  void unauthenticatedForeignScopeMissingConnectionAndWrongUserCredentials() {
    create();
    error(() -> service.get(null, org, id), 401, "UNAUTHORIZED");
    error(() -> service.list(null, org, 0), 401, "UNAUTHORIZED");
    error(() -> service.rename(null, org, id, 1, "Next"), 401, "UNAUTHORIZED");
    error(() -> service.get(human, UUID.randomUUID(), id), 403, "FORBIDDEN");
    error(() -> service.get(human, org, UUID.randomUUID()), 404, "NOT_FOUND");
    var wrong =
        new HumanIdentity(
            UUID.randomUUID(), human.email(), human.displayName(), human.credentialVersion());
    error(() -> service.get(wrong, org, id), 403, "FORBIDDEN");
    error(
        () -> service.get(new HumanIdentity(actor, human.email(), "QC", 7), org, id),
        403,
        "FORBIDDEN");
    error(() -> service.credentialVersion(human, org, id, 2), 404, "NOT_FOUND");
  }

  @Test
  void mixedTenantsAndDuplicateForeignIdentityDoNotDiscloseOrMutateForeignRows() {
    create();
    UUID foreign = UUID.randomUUID(), foreignId = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Foreign',?,'ACTIVE',current_timestamp,current_timestamp)",
        foreign,
        foreign.toString());
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        foreign);
    try {
      var other =
          service.create(
              human,
              foreign,
              foreignId,
              "Foreign connection",
              null,
              prepared(foreign, foreignId, Provider.GOOGLE_GEMINI, 1));
      jdbc.update(
          "delete from organization_memberships where organization_id=? and account_id=?",
          foreign,
          actor);
      var before = snapshot();
      var collateral = existingTables();
      error(() -> service.get(human, foreign, foreignId), 403, "FORBIDDEN");
      error(() -> service.get(human, org, foreignId), 404, "NOT_FOUND");
      error(() -> service.rename(human, org, foreignId, 1, "Never"), 404, "NOT_FOUND");
      assertThat(service.list(human, org, 0).total()).isEqualTo(1);
      assertThat(service.list(human, org, 0).items())
          .extracting(ProviderConnectionService.Connection::id)
          .containsExactly(id);
      var duplicate = prepared(org, foreignId, Provider.OPENAI, 1);
      error(
          () -> service.create(human, org, foreignId, "Duplicate", null, duplicate),
          409,
          "CONNECTION_CONFLICT");
      assertThat(snapshot()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(collateral);
      jdbc.update(
          "insert into organization_memberships(account_id,organization_id,role) values"
              + " (?,?,'ADMIN')",
          actor,
          foreign);
      assertThat(service.get(human, foreign, foreignId)).isEqualTo(other);
    } finally {
      jdbc.update("delete from provider_connection_events where organization_id=?", foreign);
      jdbc.update("delete from provider_connections where organization_id=?", foreign);
      jdbc.update("delete from provider_credential_versions where organization_id=?", foreign);
      jdbc.update("delete from organization_memberships where organization_id=?", foreign);
      jdbc.update("delete from organizations where id=?", foreign);
    }
  }

  @Test
  void listHasFixedTwentyStableOrderActualCountAndBoundedPages() {
    for (int i = 0; i < 21; i++) {
      UUID next = UUID.randomUUID();
      service.create(human, org, next, "Name " + i, null, prepared(org, next, Provider.OPENAI, 1));
    }
    jdbc.update(
        "update provider_connections set created_at=(select min(created_at) from"
            + " provider_connections where organization_id=?) where organization_id=?",
        org,
        org);
    var expected =
        jdbc.queryForList(
            "select id from provider_connections where organization_id=? order by created_at"
                + " desc,id desc",
            UUID.class,
            org);
    var first = service.list(human, org, 0);
    var second = service.list(human, org, 1);
    assertThat(first.total()).isEqualTo(21);
    assertThat(first.items().stream().map(ProviderConnectionService.Connection::id))
        .containsExactlyElementsOf(expected.subList(0, 20));
    assertThat(second.items().stream().map(ProviderConnectionService.Connection::id))
        .containsExactly(expected.get(20));
    assertThat(service.list(human, org, 100000).items()).isEmpty();
    assertThat(service.list(human, org, 100000).total()).isEqualTo(21);
    error(() -> service.list(human, org, -1), 400, "INVALID_PAGE");
    error(() -> service.list(human, org, 100001), 400, "INVALID_PAGE");
  }

  @Test
  void exhaustedRevisionCannotWrapOrAddEvents() {
    create();
    jdbc.update(
        "update provider_connections set configuration_revision=? where organization_id=? and id=?",
        Long.MAX_VALUE,
        org,
        id);
    var before = snapshot();
    var collateral = existingTables();
    error(() -> service.rename(human, org, id, Long.MAX_VALUE, "Next"), 409, "REVISION_EXHAUSTED");
    error(
        () -> service.setEnabled(human, org, id, Long.MAX_VALUE, false), 409, "REVISION_EXHAUSTED");
    var metadata = prepared(org, id, Provider.OPENAI, 2);
    error(
        () -> service.rotate(human, org, id, Long.MAX_VALUE, metadata), 409, "REVISION_EXHAUSTED");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @Test
  void exhaustedCredentialRevisionCannotWrapOrInsertVersion() {
    create();
    jdbc.update(
        "insert into"
            + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
            + " select"
            + " organization_id,connection_id,provider,?,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at"
            + " from provider_credential_versions where organization_id=? and connection_id=?",
        Long.MAX_VALUE,
        org,
        id);
    jdbc.update(
        "update provider_connections set configuration_revision=?,credential_revision=? where"
            + " organization_id=? and id=?",
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        org,
        id);
    var before = snapshot();
    var collateral = existingTables();
    var metadata = prepared(org, id, Provider.OPENAI, 2);
    error(
        () -> service.rotate(human, org, id, Long.MAX_VALUE, metadata), 409, "REVISION_EXHAUSTED");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NAME", "WORKSPACE", "VERIFIER", "LOCATOR"})
  void selectedCorruptionFailsClosedForGetListAndMutation(String field) {
    create();
    switch (field) {
      case "NAME" ->
          jdbc.update(
              "update provider_connections set name=? where organization_id=?", "bad\nname", org);
      case "WORKSPACE" -> {
        // A valid Anthropic row can contain an invalid workspace despite SQL's type constraints.
        UUID next = UUID.randomUUID();
        service.create(
            human, org, next, "Claude", null, prepared(org, next, Provider.ANTHROPIC, 1));
        id = next;
        jdbc.update(
            "update provider_connections set workspace='invalid' where organization_id=? and id=?",
            org,
            id);
      }
      case "VERIFIER" ->
          jdbc.update(
              "update provider_credential_versions set verifier=? where organization_id=?",
              "!".repeat(44),
              org);
      default ->
          jdbc.update(
              "update provider_credential_versions set external_locator='invalid locator' where"
                  + " organization_id=?",
              org);
    }
    var before = snapshot();
    var collateral = existingTables();
    error(() -> service.get(human, org, id), 500, "PERSISTENCE_UNAVAILABLE");
    error(() -> service.list(human, org, 0), 500, "PERSISTENCE_UNAVAILABLE");
    error(() -> service.rename(human, org, id, 1, "Next"), 500, "PERSISTENCE_UNAVAILABLE");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @ParameterizedTest
  @CsvSource({
    "true,MISSING",
    "false,MISSING",
    "true,ORG",
    "false,ORG",
    "true,CONNECTION",
    "false,CONNECTION",
    "true,PROVIDER",
    "false,PROVIDER"
  })
  void exactCurrentFkRejectsEveryForeignTupleWithChecksSatisfied(boolean enabled, String mismatch) {
    create();
    if (!enabled) service.setEnabled(human, org, id, 1, false);
    UUID foreignOrg = null;
    if (mismatch.equals("ORG")) {
      foreignOrg = UUID.randomUUID();
      jdbc.update(
          "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Foreign"
              + " FK',?,'ACTIVE',current_timestamp,current_timestamp)",
          foreignOrg,
          foreignOrg.toString());
    }
    try {
      if (!mismatch.equals("MISSING")) {
        Metadata wrongTuple =
            prepared(
                foreignOrg == null ? org : foreignOrg,
                mismatch.equals("CONNECTION") ? UUID.randomUUID() : id,
                mismatch.equals("PROVIDER") ? Provider.GOOGLE_GEMINI : Provider.OPENAI,
                2);
        // Deliberately unreferenced fixture version; this test isolates the immediate current FK.
        jdbc.update(
            "insert into"
                + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
                + " values (?,?,?,?,?,?,?,'v2',?,current_timestamp)",
            wrongTuple.scope().organization(),
            wrongTuple.scope().connection(),
            wrongTuple.scope().provider().name(),
            2,
            wrongTuple.reference().locator(),
            wrongTuple.reference().keyVersion(),
            wrongTuple.macVersion(),
            wrongTuple.digest());
      }
      var before = allNewRows();
      var existing = existingTables();
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update provider_connections set"
                          + " configuration_revision=3,credential_revision=2 where"
                          + " organization_id=? and id=?",
                      org,
                      id))
          .isInstanceOfSatisfying(
              org.springframework.dao.DataIntegrityViolationException.class,
              ex -> {
                Throwable cause = ex;
                java.sql.SQLException sql = null;
                while (cause != null) {
                  if (cause instanceof java.sql.SQLException candidate) sql = candidate;
                  cause = cause.getCause();
                }
                assertThat(sql != null).isTrue();
                assertThat(sql.getMessage().toLowerCase(java.util.Locale.ROOT))
                    .contains("provider_connections_current_credential_fk");
                String product =
                    jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>)
                            c -> c.getMetaData().getDatabaseProductName());
                if ("PostgreSQL".equals(product)) assertThat(sql.getSQLState()).isEqualTo("23503");
                else assertThat(sql.getSQLState()).startsWith("2350");
              });
      assertThat(allNewRows()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(existing);
    } finally {
      if (foreignOrg != null) {
        jdbc.update("delete from provider_credential_versions where organization_id=?", foreignOrg);
        jdbc.update("delete from organizations where id=?", foreignOrg);
      }
    }
  }

  Map<String, List<Map<String, Object>>> allNewRows() {
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "provider_connections", "provider_credential_versions", "provider_connection_events")) {
      var rows = new java.util.ArrayList<>(jdbc.queryForList("select * from " + table));
      rows.sort(java.util.Comparator.comparing(Object::toString));
      result.put(table, rows);
    }
    return result;
  }

  @Test
  void selectedMissingVersionInNonFirstRowCannotDisappearThroughJoin() {
    create();
    UUID healthy = UUID.randomUUID();
    service.create(
        human, org, healthy, "Healthy newer row", null, prepared(org, healthy, Provider.OPENAI, 1));
    assertThat(service.list(human, org, 0).items().get(0).id()).isEqualTo(healthy);
    jdbc.execute(
        "alter table provider_connections drop constraint"
            + " provider_connections_current_credential_fk");
    try {
      jdbc.update(
          "update provider_connections set credential_revision=2,configuration_revision=2 where"
              + " organization_id=? and id=?",
          org,
          id);
      var before = snapshot();
      var collateral = existingTables();
      error(() -> service.get(human, org, id), 500, "PERSISTENCE_UNAVAILABLE");
      error(() -> service.list(human, org, 0), 500, "PERSISTENCE_UNAVAILABLE");
      assertThat(snapshot()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(collateral);
    } finally {
      jdbc.update(
          "update provider_connections set credential_revision=1,configuration_revision=1 where"
              + " organization_id=? and id=?",
          org,
          id);
      jdbc.execute(
          "alter table provider_connections add constraint"
              + " provider_connections_current_credential_fk foreign key"
              + " (organization_id,id,provider,credential_revision) references"
              + " provider_credential_versions(organization_id,connection_id,provider,credential_revision)");
    }
  }

  @Test
  void requiredPropagationJoinsOuterRollbackIncludingEventAndVersion() {
    var metadata = prepared(org, id, Provider.OPENAI, 1);
    String marker = "provider_caller_qc_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("create table " + marker + " (id uuid primary key)");
    var before = snapshot();
    var collateral = existingTables();
    try {
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.update("insert into " + marker + " values (?)", id);
                            service.create(human, org, id, "Name", null, metadata);
                            assertThat(count("provider_connection_events")).isEqualTo(1);
                            throw new IllegalStateException(
                                "Caller failed after provisional success");
                          }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(jdbc.queryForObject("select count(*) from " + marker, Long.class)).isZero();
      assertThat(snapshot()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(collateral);
    } finally {
      jdbc.execute("drop table " + marker);
    }
  }

  @Test
  void incompatibleJoinedTransactionsRejectBeforeMutationAndWeakerReadsFailClosed() {
    create();
    var before = snapshot();
    var collateral = existingTables();
    var repeatableWrite = new TransactionTemplate(transactions);
    repeatableWrite.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                repeatableWrite.executeWithoutResult(
                    status -> service.rename(human, org, id, 1, "Must not commit")))
        .isInstanceOfSatisfying(
            ProviderConnectionFailure.class,
            ex -> assertThat(ex.code()).isEqualTo("PERSISTENCE_UNAVAILABLE"));
    var weakRead = new TransactionTemplate(transactions);
    weakRead.setReadOnly(true);
    weakRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThatThrownBy(() -> weakRead.executeWithoutResult(status -> service.list(human, org, 0)))
        .isInstanceOfSatisfying(
            ProviderConnectionFailure.class,
            ex -> assertThat(ex.code()).isEqualTo("PERSISTENCE_UNAVAILABLE"));
    var strongRead = new TransactionTemplate(transactions);
    strongRead.setReadOnly(true);
    strongRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    strongRead.executeWithoutResult(
        status -> assertThat(service.get(human, org, id).revision()).isEqualTo(1));
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @Test
  void caughtDomainFailureMarksOuterWorkRollbackOnly() {
    create();
    var before = snapshot();
    var collateral = existingTables();
    var outer = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                outer.executeWithoutResult(
                    status -> {
                      service.rename(human, org, id, 1, "Provisional");
                      error(
                          () -> service.rename(human, org, id, 1, "Stale"),
                          409,
                          "REVISION_CONFLICT");
                    }))
        .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @Test
  void maximumRevisionNoopStillHasNoChangePrecedence() {
    create();
    jdbc.update(
        "update provider_connections set configuration_revision=? where organization_id=? and id=?",
        Long.MAX_VALUE,
        org,
        id);
    var before = snapshot();
    var collateral = existingTables();
    error(() -> service.rename(human, org, id, Long.MAX_VALUE, " Connection "), 409, "NO_CHANGE");
    error(() -> service.setEnabled(human, org, id, Long.MAX_VALUE, true), 409, "NO_CHANGE");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(existingTables()).isEqualTo(collateral);
  }

  @Test
  void forcedEventInsertionFailureRollsBackCreateAndRotation() {
    String constraint = "provider_qc_" + UUID.randomUUID().toString().replace("-", "");
    var metadata = prepared(org, id, Provider.OPENAI, 1);
    var emptyRows = snapshot();
    var initialCollateral = existingTables();
    jdbc.execute(
        "alter table provider_connection_events add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "')");
    try {
      error(
          () -> service.create(human, org, id, "Name", null, metadata),
          500,
          "PERSISTENCE_UNAVAILABLE");
      assertThat(count("provider_connections")).isZero();
      assertThat(count("provider_credential_versions")).isZero();
      assertThat(count("provider_connection_events")).isZero();
      assertThat(snapshot()).isEqualTo(emptyRows);
      assertThat(existingTables()).isEqualTo(initialCollateral);
    } finally {
      jdbc.execute("alter table provider_connection_events drop constraint " + constraint);
    }
    var expected = new ProviderConnectionExpectedRows(jdbc, org, id, actor);
    expected.accept(
        service.create(human, org, id, "Name", null, metadata),
        metadata,
        Provider.OPENAI,
        "Name",
        null,
        "ENABLED",
        1,
        1,
        "CREATE");
    var before = snapshot();
    var collateral = existingTables();
    var replacement = prepared(org, id, Provider.OPENAI, 2);
    jdbc.execute(
        "alter table provider_connection_events add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "' or configuration_revision=1)");
    try {
      error(() -> service.rotate(human, org, id, 1, replacement), 500, "PERSISTENCE_UNAVAILABLE");
      assertThat(snapshot()).isEqualTo(before);
      assertThat(existingTables()).isEqualTo(collateral);
    } finally {
      jdbc.execute("alter table provider_connection_events drop constraint " + constraint);
    }
    expected.accept(
        service.rotate(human, org, id, 1, replacement),
        replacement,
        Provider.OPENAI,
        "Name",
        null,
        "ENABLED",
        2,
        2,
        "ROTATE");
    assertThat(existingTables()).isEqualTo(collateral);
  }

  static java.util.stream.Stream<Arguments> completeRowScenarios() {
    return java.util.stream.Stream.of(
        Arguments.of(Provider.OPENAI, null),
        Arguments.of(Provider.GOOGLE_GEMINI, null),
        Arguments.of(Provider.ANTHROPIC, null),
        Arguments.of(Provider.ANTHROPIC, "wrkspc_Complete123"));
  }

  @ParameterizedTest
  @MethodSource("completeRowScenarios")
  void completeRowsAndCollateralAcrossEveryOperationRejectionAndOuterRotateRollback(
      Provider provider, String workspace) {
    Metadata first = prepared(org, id, provider, 1);
    Metadata second = prepared(org, id, provider, 2);
    Metadata third = prepared(org, id, provider, 3);
    String marker = "provider_matrix_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("create table " + marker + " (id uuid primary key)");
    var collateral = existingTables();
    var expected = new ProviderConnectionExpectedRows(jdbc, org, id, actor);
    try {
      expected.accept(
          service.create(human, org, id, "  Initial  ", workspace, first),
          first,
          provider,
          "Initial",
          workspace,
          "ENABLED",
          1,
          1,
          "CREATE");
      assertThat(existingTables()).isEqualTo(collateral);
      expected.accept(
          service.rename(human, org, id, 1, "  Renamed  "),
          first,
          provider,
          "Renamed",
          workspace,
          "ENABLED",
          2,
          1,
          "RENAME");
      assertThat(existingTables()).isEqualTo(collateral);
      expected.accept(
          service.setEnabled(human, org, id, 2, false),
          first,
          provider,
          "Renamed",
          workspace,
          "DISABLED",
          3,
          1,
          "DISABLE");
      assertThat(existingTables()).isEqualTo(collateral);
      expected.accept(
          service.rotate(human, org, id, 3, second),
          second,
          provider,
          "Renamed",
          workspace,
          "DISABLED",
          4,
          2,
          "ROTATE");
      assertThat(existingTables()).isEqualTo(collateral);
      expected.accept(
          service.setEnabled(human, org, id, 4, true),
          second,
          provider,
          "Renamed",
          workspace,
          "ENABLED",
          5,
          2,
          "ENABLE");
      assertThat(existingTables()).isEqualTo(collateral);
      error(
          () -> service.create(human, org, id, "Duplicate", workspace, first),
          409,
          "CONNECTION_CONFLICT");
      expected.assertMatches();
      assertThat(existingTables()).isEqualTo(collateral);
      error(() -> service.rename(human, org, id, 5, " Renamed "), 409, "NO_CHANGE");
      expected.assertMatches();
      assertThat(existingTables()).isEqualTo(collateral);
      error(() -> service.setEnabled(human, org, id, 4, true), 409, "REVISION_CONFLICT");
      expected.assertMatches();
      assertThat(existingTables()).isEqualTo(collateral);
      error(() -> service.rotate(human, org, id, 5, first), 400, "INVALID_METADATA");
      expected.assertMatches();
      assertThat(existingTables()).isEqualTo(collateral);
      var provisional = expected.fork();
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.update("insert into " + marker + " values (?)", id);
                            provisional.accept(
                                service.rotate(human, org, id, 5, third),
                                third,
                                provider,
                                "Renamed",
                                workspace,
                                "ENABLED",
                                6,
                                3,
                                "ROTATE");
                            throw new IllegalStateException(
                                "Caller failure after provisional rotation");
                          }))
          .isInstanceOf(IllegalStateException.class);
      expected.assertMatches();
      assertThat(existingTables()).isEqualTo(collateral);
      String constraint = "provider_event_matrix_" + UUID.randomUUID().toString().replace("-", "");
      jdbc.execute(
          "alter table provider_connection_events add constraint "
              + constraint
              + " check (organization_id <> '"
              + org
              + "' or configuration_revision <= 5)");
      try {
        error(() -> service.rotate(human, org, id, 5, third), 500, "PERSISTENCE_UNAVAILABLE");
        expected.assertMatches();
        assertThat(existingTables()).isEqualTo(collateral);
        assertThatThrownBy(
                () ->
                    new TransactionTemplate(transactions)
                        .executeWithoutResult(
                            status -> {
                              jdbc.update("insert into " + marker + " values (?)", id);
                              error(
                                  () -> service.rotate(human, org, id, 5, third),
                                  500,
                                  "PERSISTENCE_UNAVAILABLE");
                            }))
            .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        expected.assertMatches();
        assertThat(existingTables()).isEqualTo(collateral);
      } finally {
        jdbc.execute("alter table provider_connection_events drop constraint " + constraint);
      }
    } finally {
      jdbc.execute("drop table " + marker);
    }
  }
}

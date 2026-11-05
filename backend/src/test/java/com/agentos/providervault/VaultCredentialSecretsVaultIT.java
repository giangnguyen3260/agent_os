package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit real Vault integration, invoked only by the disposable Vault CI harness. */
@EnabledIfEnvironmentVariable(named = "AGENTOS_TEST_VAULT_URL", matches = "https://.*")
class VaultCredentialSecretsVaultIT {
  @Test
  void realVaultScopedVersionedRoundTripAndRestartReadback() throws Exception {
    var keys = KeyStore.getInstance(KeyStore.getDefaultType());
    keys.load(null, null);
    try (var input = Files.newInputStream(Path.of(required("AGENTOS_TEST_VAULT_CA")))) {
      keys.setCertificateEntry(
          "vault", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keys);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trust.getTrustManagers(), null);
    Path token = Path.of(required("AGENTOS_TEST_VAULT_TOKEN_FILE"));
    var adapter =
        new VaultCredentialSecrets(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            () -> {
              try {
                return Files.readString(token, StandardCharsets.UTF_8).strip().toCharArray();
              } catch (Exception failure) {
                throw new Failure();
              }
            },
            (scope, reference) -> {
              throw new Failure();
            },
            tls.getSocketFactory());
    Path hmacToken = Path.of(required("AGENTOS_TEST_HMAC_TOKEN_FILE"));
    java.util.function.Supplier<char[]> hmacTokens =
        () -> {
          try {
            return Files.readString(hmacToken, StandardCharsets.UTF_8).strip().toCharArray();
          } catch (Exception failure) {
            throw new Failure();
          }
        };
    var credentialKeys =
        new VaultHmacKeys(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            hmacTokens,
            VaultHmacKeys.Purpose.CREDENTIAL_INTEGRITY,
            2,
            tls.getSocketFactory());
    var commandKeys =
        new VaultHmacKeys(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            hmacTokens,
            VaultHmacKeys.Purpose.COMMAND_RECEIPTS,
            1,
            tls.getSocketFactory());
    assertThat(credentialKeys.currentVersion()).isEqualTo("2");
    for (String version : new String[] {"1", "2"}) {
      byte[] expectedKey =
          java.util.Base64.getDecoder()
              .decode(
                  Files.readString(
                          Path.of(
                              required("AGENTOS_TEST_HMAC_FIXTURE_DIR"), "credential-v" + version))
                      .strip());
      byte[] fetched = credentialKeys.key(version).getEncoded();
      try {
        assertThat(Arrays.equals(fetched, expectedKey))
            .as("exact historical credential HMAC key")
            .isTrue();
      } finally {
        Arrays.fill(expectedKey, (byte) 0);
        Arrays.fill(fetched, (byte) 0);
      }
    }
    byte[] expectedCommand =
        java.util.Base64.getDecoder()
            .decode(
                Files.readString(Path.of(required("AGENTOS_TEST_HMAC_FIXTURE_DIR"), "command-v1"))
                    .strip());
    byte[] fetchedCommand = commandKeys.key("1").getEncoded();
    try {
      assertThat(Arrays.equals(fetchedCommand, expectedCommand))
          .as("separate command HMAC key")
          .isTrue();
    } finally {
      Arrays.fill(expectedCommand, (byte) 0);
      Arrays.fill(fetchedCommand, (byte) 0);
    }
    assertThatThrownBy(() -> credentialKeys.key("3")).isInstanceOf(Failure.class).hasNoCause();
    assertThatThrownBy(() -> credentialKeys.key("01")).isInstanceOf(Failure.class).hasNoCause();
    var datasource =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            required("AGENTOS_TEST_JOURNAL_URL"),
            "vault_journal_test",
            Files.readString(Path.of(required("AGENTOS_TEST_PG_PASSWORD_FILE"))).strip());
    org.flywaydb.core.Flyway.configure().dataSource(datasource).load().migrate();
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(datasource);
    var journal = new com.agentos.providerpreparation.ProviderPreparationJournal(datasource);
    var composed = new JournaledVaultSecrets(journal, adapter);
    Path evidence = Path.of(required("AGENTOS_TEST_VAULT_REFERENCE_FILE"));
    String phase = required("AGENTOS_TEST_VAULT_PHASE");
    var json = new ObjectMapper();
    byte[] expected = "java-vault-integration-synthetic-only".getBytes(StandardCharsets.US_ASCII);
    byte[] actual = null;
    var boundary = new com.agentos.providercredentials.CredentialBoundary(credentialKeys, composed);
    com.agentos.providercredentials.CredentialBoundary.Metadata persisted;
    try {
      Reference reference;
      if (phase.equals("prepare")) {
        var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), Provider.OPENAI, 1);
        jdbc.update(
            "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Vault"
                + " journal fixture',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            scope.organization(),
            scope.organization().toString());
        char[] submitted = new String(expected, StandardCharsets.US_ASCII).toCharArray();
        persisted = boundary.prepare(scope, submitted);
        assertThat(submitted).containsOnly('\0');
        reference = persisted.reference();
        UUID preparationId =
            jdbc.queryForObject(
                "select id from provider_secret_preparations where external_locator=?",
                UUID.class,
                reference.locator());
        var preparation =
            new com.agentos.providerpreparation.ProviderPreparationJournal.Preparation(
                preparationId, scope, reference.locator());
        assertThat(journal.observe(preparation))
            .isEqualTo(
                com.agentos.providerpreparation.ProviderPreparationJournal.Observation.UNRESOLVED);
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(datasource))
            .executeWithoutResult(
                status -> {
                  jdbc.update(
                      "insert into provider_credential_versions"
                          + "(organization_id,connection_id,provider,credential_revision,external_locator,"
                          + "external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
                          + " values (?,?,'OPENAI',1,?,'1',?,'v2',?,CURRENT_TIMESTAMP)",
                      scope.organization(),
                      scope.connection(),
                      persisted.reference().locator(),
                      persisted.macVersion(),
                      persisted.digest());
                  jdbc.update(
                      "insert into provider_connections"
                          + "(id,organization_id,provider,mode,name,state,configuration_revision,"
                          + "credential_revision,created_at,updated_at) values"
                          + " (?,?,'OPENAI','OPENAI_API_KEY','Synthetic"
                          + " fixture','ENABLED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                      scope.connection(),
                      scope.organization());
                });
        var safe = json.createObjectNode();
        safe.put("organization", scope.organization().toString());
        safe.put("connection", scope.connection().toString());
        safe.put("locator", reference.locator());
        Files.write(evidence, json.writeValueAsBytes(safe));
      } else {
        assertThat(phase).isEqualTo("restart");
        var safe = json.readTree(Files.readAllBytes(evidence));
        var scope =
            new Scope(
                UUID.fromString(safe.path("organization").asText()),
                UUID.fromString(safe.path("connection").asText()),
                Provider.OPENAI,
                1);
        reference = new Reference(scope, safe.path("locator").asText(), "1");
        var stored =
            jdbc.queryForMap(
                "select hmac_key_version,verifier from provider_credential_versions where"
                    + " organization_id=? and connection_id=? and provider='OPENAI' and"
                    + " credential_revision=1",
                scope.organization(),
                scope.connection());
        persisted =
            new com.agentos.providercredentials.CredentialBoundary.Metadata(
                scope,
                (String) stored.get("hmac_key_version"),
                reference,
                (String) stored.get("verifier"));
      }
      var verified = new java.util.concurrent.atomic.AtomicBoolean();
      boundary.use(
          reference.scope(),
          persisted,
          borrowed -> verified.set(Arrays.equals(borrowed, expected)));
      assertThat(verified.get()).as("HMAC verified before credential callback").isTrue();
      actual = composed.read(reference);
      assertThat(Arrays.equals(actual, expected))
          .as("exact synthetic credential readback")
          .isTrue();
      var row =
          jdbc.queryForMap(
              "select * from provider_secret_preparations where external_locator=?",
              reference.locator());
      assertThat(row.get("organization_id")).isEqualTo(reference.scope().organization());
      assertThat(row.get("connection_id")).isEqualTo(reference.scope().connection());
      assertThat(row.get("state")).isEqualTo("WRITTEN");
      assertThat(row.get("external_version")).isEqualTo("1");
      String beforeObservation =
          jdbc.queryForObject(
              "select row_to_json(p)::text from provider_secret_preparations p where id=?",
              String.class,
              row.get("id"));
      var preparation =
          new com.agentos.providerpreparation.ProviderPreparationJournal.Preparation(
              (UUID) row.get("id"), reference.scope(), reference.locator());
      assertThat(journal.observe(preparation))
          .isEqualTo(
              com.agentos.providerpreparation.ProviderPreparationJournal.Observation
                  .RETAINED_REFERENCE_FOUND);
      assertThat(
              jdbc.queryForObject(
                  "select row_to_json(p)::text from provider_secret_preparations p where id=?",
                  String.class,
                  preparation.id()))
          .isEqualTo(beforeObservation);
      assertThat(
              jdbc.queryForObject("select count(*) from provider_secret_preparations", Long.class))
          .isEqualTo(1L);
      var foreignPreparation =
          new com.agentos.providerpreparation.ProviderPreparationJournal.Preparation(
              preparation.id(),
              new Scope(reference.scope().organization(), UUID.randomUUID(), Provider.OPENAI, 1),
              reference.locator());
      assertThatThrownBy(() -> journal.observe(foreignPreparation))
          .hasMessage("PREPARATION_JOURNAL_UNAVAILABLE")
          .hasNoCause();
      jdbc.update(
          "update provider_credential_versions set external_key_version='2' where organization_id=?"
              + " and connection_id=?",
          reference.scope().organization(),
          reference.scope().connection());
      try {
        assertThat(journal.observe(preparation))
            .isEqualTo(
                com.agentos.providerpreparation.ProviderPreparationJournal.Observation.UNRESOLVED);
      } finally {
        jdbc.update(
            "update provider_credential_versions set external_key_version='1' where"
                + " organization_id=? and connection_id=?",
            reference.scope().organization(),
            reference.scope().connection());
      }
      var forbidden = new java.util.ArrayList<String>();
      forbidden.add(new String(expected, StandardCharsets.US_ASCII));
      forbidden.add(java.util.Base64.getEncoder().encodeToString(expected));
      for (String keyFile : new String[] {"credential-v1", "credential-v2", "command-v1"}) {
        forbidden.add(
            Files.readString(Path.of(required("AGENTOS_TEST_HMAC_FIXTURE_DIR"), keyFile)).strip());
      }
      for (String table :
          jdbc.queryForList(
              "select tablename from pg_catalog.pg_tables where schemaname='public'",
              String.class)) {
        if (!table.matches("[a-z_][a-z0-9_]*"))
          throw new IllegalStateException("Unexpected test table");
        for (String stored :
            jdbc.queryForList(
                "select row_to_json(t)::text from public." + table + " t", String.class)) {
          for (String sentinel : forbidden) {
            assertThat(stored.contains(sentinel))
                .as("no synthetic secret in public DB rows")
                .isFalse();
          }
        }
      }
      Scope foreign =
          new Scope(UUID.randomUUID(), reference.scope().connection(), Provider.OPENAI, 1);
      assertThatThrownBy(() -> adapter.read(new Reference(foreign, reference.locator(), "1")))
          .isInstanceOf(Failure.class)
          .hasNoCause();
      assertThatThrownBy(
              () -> adapter.read(new Reference(reference.scope(), reference.locator(), "2")))
          .isInstanceOf(Failure.class)
          .hasNoCause();
    } finally {
      Arrays.fill(expected, (byte) 0);
      if (actual != null) Arrays.fill(actual, (byte) 0);
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing test configuration");
    return value;
  }
}

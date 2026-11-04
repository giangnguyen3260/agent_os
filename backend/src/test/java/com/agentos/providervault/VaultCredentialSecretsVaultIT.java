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
    Path evidence = Path.of(required("AGENTOS_TEST_VAULT_REFERENCE_FILE"));
    String phase = required("AGENTOS_TEST_VAULT_PHASE");
    var json = new ObjectMapper();
    byte[] expected = "java-vault-integration-synthetic-only".getBytes(StandardCharsets.US_ASCII);
    byte[] actual = null;
    try {
      Reference reference;
      if (phase.equals("prepare")) {
        var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), Provider.OPENAI, 1);
        reference = adapter.store(scope, expected);
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
      }
      actual = adapter.read(reference);
      assertThat(Arrays.equals(actual, expected))
          .as("exact synthetic credential readback")
          .isTrue();
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

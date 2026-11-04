package com.agentos.providervault;

import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.agentos.providercredentials.CredentialBoundary.Secrets;
import com.agentos.providerpreparation.ProviderPreparationJournal.Preparation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit external Vault KV-v2 adapter; never automatically installed as a production bean. */
public final class VaultCredentialSecrets implements Secrets {
  @FunctionalInterface
  public interface Reconciliation {
    void required(Scope scope, Reference possibleReference) throws Exception;
  }

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final Reconciliation reconciliation;
  private final VaultHttp http;

  /** Token supplier returns a fresh owned buffer; configure CA trust in the JVM truststore. */
  public VaultCredentialSecrets(
      URI origin, String mount, Supplier<char[]> tokens, Reconciliation reconciliation) {
    this(origin, mount, tokens, reconciliation, (SSLSocketFactory) SSLSocketFactory.getDefault());
  }

  VaultCredentialSecrets(
      URI origin,
      String mount,
      Supplier<char[]> tokens,
      Reconciliation reconciliation,
      SSLSocketFactory tls) {
    if (reconciliation == null) throw new Failure();
    this.http = new VaultHttp(origin, mount, tokens, tls);
    this.reconciliation = reconciliation;
  }

  @Override
  public Reference store(Scope scope, byte[] borrowedCredential) {
    return storeAt(scope, UUID.randomUUID().toString(), borrowedCredential);
  }

  Reference storePrepared(Preparation preparation, byte[] borrowedCredential) {
    if (preparation == null) throw new Failure();
    return storeAt(preparation.scope(), preparation.locator(), borrowedCredential);
  }

  private Reference storeAt(Scope scope, String locator, byte[] borrowedCredential) {
    outsideTransaction();
    byte[] payload = null;
    byte[] response = null;
    try {
      if (scope == null
          || borrowedCredential == null
          || borrowedCredential.length < 1
          || borrowedCredential.length > 4096) throw new Failure();
      for (byte value : borrowedCredential) {
        if (value < 0x21 || value > 0x7e) throw new Failure();
      }
      var root = JSON.createObjectNode();
      root.putObject("options").put("cas", 0);
      var data = root.putObject("data");
      data.put("organization", scope.organization().toString());
      data.put("connection", scope.connection().toString());
      data.put("provider", scope.provider().name());
      data.put("revision", Long.toString(scope.credentialRevision()));
      data.put("credential", borrowedCredential);
      payload = JSON.writeValueAsBytes(root);
      response = http.exchange("POST", path(locator), payload);
      JsonNode version = JSON.readTree(response).path("data").path("version");
      if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() != 1)
        throw new Failure();
      return new Reference(scope, locator, "1");
    } catch (Exception failure) {
      throw new Failure();
    } finally {
      clear(payload);
      clear(response);
    }
  }

  @Override
  public byte[] read(Reference reference) {
    outsideTransaction();
    byte[] response = null;
    byte[] credential = null;
    try {
      if (reference == null
          || !reference.locator().matches("[0-9a-f-]{36}")
          || !UUID.fromString(reference.locator()).toString().equals(reference.locator())
          || !"1".equals(reference.keyVersion())) throw new Failure();
      response = http.exchange("GET", path(reference.locator()) + "?version=1", null);
      JsonNode root = JSON.readTree(response).path("data");
      JsonNode metadata = root.path("metadata");
      JsonNode data = root.path("data");
      Scope scope = reference.scope();
      if (!metadata.path("version").isIntegralNumber()
          || !metadata.path("version").canConvertToLong()
          || metadata.path("version").longValue() != 1
          || !metadata.path("destroyed").isBoolean()
          || metadata.path("destroyed").booleanValue()
          || !metadata.path("deletion_time").isTextual()
          || !metadata.path("deletion_time").textValue().isEmpty()
          || !text(data, "organization", scope.organization().toString())
          || !text(data, "connection", scope.connection().toString())
          || !text(data, "provider", scope.provider().name())
          || !text(data, "revision", Long.toString(scope.credentialRevision()))
          || !data.path("credential").isTextual()) throw new Failure();
      credential = data.path("credential").binaryValue();
      if (credential == null || credential.length < 1 || credential.length > 4096)
        throw new Failure();
      for (byte value : credential) {
        if (value < 0x21 || value > 0x7e) throw new Failure();
      }
      byte[] result = credential;
      credential = null;
      return result;
    } catch (Exception failure) {
      throw new Failure();
    } finally {
      clear(response);
      clear(credential);
    }
  }

  @Override
  public void preparationFailed(Scope scope, Reference possibleReference) {
    outsideTransaction();
    try {
      reconciliation.required(scope, possibleReference);
    } catch (Exception failure) {
      throw new Failure();
    }
  }

  private String path(String locator) {
    return "data/provider-credentials/" + locator;
  }

  private static boolean text(JsonNode data, String field, String expected) {
    return data.path(field).isTextual() && expected.equals(data.path(field).textValue());
  }

  private static void outsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw new Failure();
  }

  private static void clear(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  @Override
  public String toString() {
    return "VaultCredentialSecrets[redacted]";
  }
}

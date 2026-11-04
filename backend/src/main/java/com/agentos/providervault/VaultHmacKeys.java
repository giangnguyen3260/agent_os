package com.agentos.providervault;

import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Version-pinned external key reader. No default keys, alias fallback, writes or cache. */
public final class VaultHmacKeys implements CredentialBoundary.Keys, CommandBoundary.Keys {
  public enum Purpose {
    CREDENTIAL_INTEGRITY("credential-integrity"),
    COMMAND_RECEIPTS("command-receipts");

    private final String path;

    Purpose(String path) {
      this.path = path;
    }
  }

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final VaultHttp http;
  private final Purpose purpose;
  private final String current;

  public VaultHmacKeys(
      URI origin, String mount, Supplier<char[]> tokens, Purpose purpose, int currentVersion) {
    this(
        origin,
        mount,
        tokens,
        purpose,
        currentVersion,
        (SSLSocketFactory) SSLSocketFactory.getDefault());
  }

  VaultHmacKeys(
      URI origin,
      String mount,
      Supplier<char[]> tokens,
      Purpose purpose,
      int currentVersion,
      SSLSocketFactory tls) {
    if (purpose == null || currentVersion < 1) throw new Failure();
    this.http = new VaultHttp(origin, mount, tokens, tls);
    this.purpose = purpose;
    this.current = Integer.toString(currentVersion);
  }

  @Override
  public String currentVersion() {
    return current;
  }

  @Override
  public SecretKey key(String version) {
    byte[] response = null;
    byte[] material = null;
    try {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()
          || version == null
          || !version.matches("[1-9][0-9]{0,9}")
          || Integer.parseInt(version) < 1) throw new Failure();
      response =
          http.exchange("GET", "data/hmac-keys/" + purpose.path + "?version=" + version, null);
      var root = JSON.readTree(response).path("data");
      var meta = root.path("metadata");
      var data = root.path("data");
      if (!meta.path("version").isIntegralNumber()
          || !meta.path("version").canConvertToInt()
          || meta.path("version").intValue() != Integer.parseInt(version)
          || !meta.path("destroyed").isBoolean()
          || meta.path("destroyed").booleanValue()
          || !meta.path("deletion_time").isTextual()
          || !meta.path("deletion_time").textValue().isEmpty()
          || !data.path("purpose").isTextual()
          || !purpose.path.equals(data.path("purpose").textValue())
          || !"HmacSHA256".equals(data.path("algorithm").textValue())
          || !data.path("key").isTextual()) throw new Failure();
      material = data.path("key").binaryValue();
      if (material == null || material.length != 32) throw new Failure();
      return new SecretKeySpec(material, "HmacSHA256");
    } catch (Exception failure) {
      throw new Failure();
    } finally {
      if (material != null) Arrays.fill(material, (byte) 0);
      if (response != null) Arrays.fill(response, (byte) 0);
    }
  }

  @Override
  public String toString() {
    return "VaultHmacKeys[redacted]";
  }
}

package com.agentos.providercredentials;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Explicitly wired credential boundary; no database, provider transport, or default secret store.
 * Preparation must run outside DB transactions. Persist only Metadata after preparation succeeds.
 */
public final class CredentialBoundary {
  public enum Provider {
    OPENAI,
    ANTHROPIC,
    GOOGLE_GEMINI
  }

  public record Scope(
      UUID organization, UUID connection, Provider provider, long credentialRevision) {
    public Scope {
      Objects.requireNonNull(organization);
      Objects.requireNonNull(connection);
      Objects.requireNonNull(provider);
      if (credentialRevision < 1) throw new IllegalArgumentException("Invalid credential revision");
    }
  }

  /** Opaque locator and immutable secret version, bound by the adapter to exactly this scope. */
  public record Reference(Scope scope, String locator, String keyVersion) {
    public Reference {
      Objects.requireNonNull(scope);
      requireToken(locator);
      requireToken(keyVersion);
    }

    @Override
    public String toString() {
      return "Reference[redacted]";
    }
  }

  /** Storage allowlist. Digest is a canonical base64 HMAC, never a usable provider credential. */
  public record Metadata(Scope scope, String macVersion, Reference reference, String digest) {
    public Metadata {
      Objects.requireNonNull(scope);
      requireToken(macVersion);
      Objects.requireNonNull(reference);
      if (!scope.equals(reference.scope())) throw new Failure();
      if (digest == null || digest.length() != 44) throw new Failure();
      byte[] decoded = null;
      try {
        decoded = Base64.getDecoder().decode(digest);
        if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(digest)) {
          throw new Failure();
        }
      } catch (RuntimeException failure) {
        throw new Failure();
      } finally {
        if (decoded != null) Arrays.fill(decoded, (byte) 0);
      }
    }

    @Override
    public String toString() {
      return "Metadata[redacted]";
    }
  }

  /**
   * Managed HMAC-SHA256 keys with at least 256 bits of entropy, including historical versions. A
   * version identifies immutable key material. Never substitute a key on lookup failure.
   */
  public interface Keys {
    String currentVersion() throws Exception;

    SecretKey key(String version) throws Exception;
  }

  public interface Secrets {
    /**
     * Synchronously copy bytes to an external manager; do not retain this borrowed array. Return an
     * immutable version reference bound to scope. No credential material (including reversible
     * encodings) may be returned in the reference.
     */
    Reference store(Scope scope, byte[] borrowedCredential) throws Exception;

    /**
     * Enforce scope binding and immutable version lookup in the external manager. Return a fresh
     * owned buffer; caller erases it. On failure before returning, erase adapter-owned buffers.
     */
    byte[] read(Reference reference) throws Exception;

    /**
     * Record a possibly orphaned preparation for reconciliation (including ambiguous store
     * failure). Reference may be null or invalid. This is not permission to delete a committed
     * secret. Must not log credential bytes. Failure here still fails closed; cross-system
     * atomicity is absent.
     */
    void preparationFailed(Scope scope, Reference possibleReference) throws Exception;
  }

  @FunctionalInterface
  public interface CredentialUse {
    /** Server-side synchronous use only; never retain, return, or log this borrowed buffer. */
    void accept(byte[] borrowedCredential) throws Exception;
  }

  /** Fixed safe message, no dependency cause, credential, digest, or reference. */
  public static final class Failure extends RuntimeException {
    public Failure() {
      super("Credential operation failed", null, false, false);
    }
  }

  private final Keys keys;
  private final Secrets secrets;

  public CredentialBoundary(Keys keys, Secrets secrets) {
    this.keys = Objects.requireNonNull(keys);
    this.secrets = Objects.requireNonNull(secrets);
  }

  /** Consumes and clears caller-owned characters on every exit, including validation failure. */
  public Metadata prepare(Scope scope, char[] credential) {
    byte[] bytes = null;
    Reference reference = null;
    boolean storeAttempted = false;
    try {
      Objects.requireNonNull(scope);
      if (credential == null || credential.length < 1 || credential.length > 4096)
        throw new Failure();
      bytes = new byte[credential.length];
      for (int i = 0; i < credential.length; i++) {
        if (credential[i] < 0x21 || credential[i] > 0x7e) throw new Failure();
        bytes[i] = (byte) credential[i];
      }
      String version = keys.currentVersion();
      requireToken(version);
      Mac mac = prepareMac(scope, version);
      storeAttempted = true;
      reference = secrets.store(scope, bytes);
      if (reference == null || !scope.equals(reference.scope())) throw new Failure();
      return new Metadata(scope, version, reference, finishMac(mac, reference, bytes));
    } catch (Exception failure) {
      if (storeAttempted) {
        try {
          secrets.preparationFailed(scope, reference);
        } catch (Exception ignored) {
          // Operation remains failed; reconciliation is an explicit adapter responsibility.
        }
      }
      throw new Failure();
    } finally {
      if (credential != null) Arrays.fill(credential, '\0');
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
  }

  /** Expected scope must come from trusted authorization context, not stored metadata alone. */
  public void use(Scope expected, Metadata metadata, CredentialUse consumer) {
    byte[] bytes = null;
    byte[] actual = null;
    byte[] stored = null;
    try {
      Objects.requireNonNull(consumer);
      if (expected == null
          || metadata == null
          || !expected.equals(metadata.scope())
          || !expected.equals(metadata.reference().scope())) throw new Failure();
      bytes = secrets.read(metadata.reference());
      validate(bytes);
      actual =
          Base64.getDecoder()
              .decode(
                  finishMac(
                      prepareMac(expected, metadata.macVersion()), metadata.reference(), bytes));
      stored = Base64.getDecoder().decode(metadata.digest());
      if (!MessageDigest.isEqual(actual, stored)) throw new Failure();
      consumer.accept(bytes);
    } catch (Exception failure) {
      throw new Failure();
    } finally {
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
      if (actual != null) Arrays.fill(actual, (byte) 0);
      if (stored != null) Arrays.fill(stored, (byte) 0);
    }
  }

  private Mac prepareMac(Scope scope, String version) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(Objects.requireNonNull(keys.key(version)));
    field(mac, "agentos/provider-credential/verifier/v2");
    field(mac, version);
    field(mac, scope.organization().toString());
    field(mac, scope.connection().toString());
    field(mac, scope.provider().name());
    field(mac, Long.toString(scope.credentialRevision()));
    return mac;
  }

  private static String finishMac(Mac mac, Reference reference, byte[] credential) {
    field(mac, reference.locator());
    field(mac, reference.keyVersion());
    mac.update(ByteBuffer.allocate(4).putInt(credential.length).array());
    byte[] result = mac.doFinal(credential);
    try {
      return Base64.getEncoder().encodeToString(result);
    } finally {
      Arrays.fill(result, (byte) 0);
    }
  }

  private static void field(Mac mac, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    mac.update(bytes);
  }

  private static void validate(byte[] bytes) {
    if (bytes == null || bytes.length < 1 || bytes.length > 4096) throw new Failure();
    for (byte value : bytes) if (value < 0x21 || value > 0x7e) throw new Failure();
  }

  private static void requireToken(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_./:@-]{1,256}")) throw new Failure();
  }
}

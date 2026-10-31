package com.agentos.providercredentials;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.agentos.providercredentials.CredentialBoundary.Keys;
import com.agentos.providercredentials.CredentialBoundary.Metadata;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.agentos.providercredentials.CredentialBoundary.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CredentialBoundaryTest {
  private final Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), Provider.OPENAI, 1);
  private final FakeKeys keys = new FakeKeys();
  private final FakeSecrets secrets = new FakeSecrets();
  private final CredentialBoundary boundary = new CredentialBoundary(keys, secrets);

  @Test
  void independentLengthPrefixedHmacVector() {
    Scope fixed =
        new Scope(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Provider.OPENAI,
            1);
    // Independently calculated with Python hmac/hashlib and unsigned big-endian 32-bit lengths.
    assertEquals(
        "aL1EZ6NLMerhRLp2lqSL/RaqMxsiCYQGVRAZKFdtFQI=",
        boundary.prepare(fixed, "sentinel".toCharArray()).digest());
  }

  @Test
  void sameScopeSameBytesLocatorSubstitutionNeverConsumes() {
    Metadata metadata = prepare();
    Reference substituted =
        new Reference(scope, "different-locator", metadata.reference().keyVersion());
    Metadata tampered = new Metadata(scope, metadata.macVersion(), substituted, metadata.digest());
    AtomicBoolean consumed = new AtomicBoolean();
    assertThrows(Failure.class, () -> boundary.use(scope, tampered, bytes -> consumed.set(true)));
    assertFalse(consumed.get());
    assertEquals(substituted, secrets.lastReadReference);
    assertEquals(1, secrets.reads);
    assertArrayEquals("sentinel".getBytes(StandardCharsets.US_ASCII), secrets.saved);
    assertZero(secrets.readBuffer);
  }

  @Test
  void sameScopeSameBytesExternalKeyVersionSubstitutionNeverConsumes() {
    Metadata metadata = prepare();
    Reference substituted = new Reference(scope, metadata.reference().locator(), "secret-v2");
    Metadata tampered = new Metadata(scope, metadata.macVersion(), substituted, metadata.digest());
    AtomicBoolean consumed = new AtomicBoolean();
    assertThrows(Failure.class, () -> boundary.use(scope, tampered, bytes -> consumed.set(true)));
    assertFalse(consumed.get());
    assertEquals(substituted, secrets.lastReadReference);
    assertEquals(1, secrets.reads);
    assertArrayEquals("sentinel".getBytes(StandardCharsets.US_ASCII), secrets.saved);
    assertZero(secrets.readBuffer);
  }

  @Test
  void keyLookupPreparationFailureNeverStores() {
    keys.values.remove("v1");
    char[] caller = "sentinel".toCharArray();
    assertSafe(assertThrows(Failure.class, () -> boundary.prepare(scope, caller)));
    assertEquals(1, keys.lookups);
    assertEquals(0, secrets.stores);
    assertEquals(0, secrets.failures);
    assertZero(caller);
  }

  @Test
  void macInitializationFailureNeverStores() {
    keys.values.put(
        "v1",
        new SecretKey() {
          public String getAlgorithm() {
            return "HmacSHA256";
          }

          public String getFormat() {
            return "RAW";
          }

          public byte[] getEncoded() {
            return null;
          }
        });
    char[] caller = "sentinel".toCharArray();
    assertSafe(assertThrows(Failure.class, () -> boundary.prepare(scope, caller)));
    assertEquals(1, keys.lookups);
    assertEquals(0, secrets.stores);
    assertEquals(0, secrets.failures);
    assertZero(caller);
  }

  @Test
  void preparationUsesSameInitializedHandleWithoutPostStoreLookup() {
    secrets.afterStore =
        () -> {
          assertEquals(1, keys.lookups);
          assertEquals("v1", keys.lastLookup);
          keys.outage = true;
          keys.current = "v2";
        };
    char[] caller = "sentinel".toCharArray();
    Metadata metadata = boundary.prepare(scope, caller);
    assertEquals("v1", metadata.macVersion());
    assertEquals(1, keys.lookups);
    assertEquals(1, secrets.stores);
    assertEquals(0, secrets.failures);
    assertZero(caller);
    assertZero(secrets.storeBuffer);
    keys.outage = false;
    boundary.use(
        scope,
        metadata,
        bytes -> assertArrayEquals("sentinel".getBytes(StandardCharsets.US_ASCII), bytes));
    assertZero(secrets.readBuffer);
  }

  @Test
  void canonicalButWrongDigestNeverConsumes() {
    Metadata metadata = prepare();
    Metadata tampered =
        new Metadata(
            scope,
            "v1",
            metadata.reference(),
            java.util.Base64.getEncoder().encodeToString(new byte[32]));
    assertThrows(Failure.class, () -> boundary.use(scope, tampered, bytes -> fail()));
    assertZero(secrets.readBuffer);
  }

  @Test
  void invalidManagedVersionNeverStoresAndClearsCaller() {
    keys.current = "bad version";
    char[] caller = "sentinel".toCharArray();
    assertThrows(Failure.class, () -> boundary.prepare(scope, caller));
    assertZero(caller);
    assertEquals(0, secrets.stores);
  }

  @Test
  void consumerErrorStillClearsReadBuffer() {
    Metadata metadata = prepare();
    assertThrows(
        AssertionError.class,
        () ->
            boundary.use(
                scope,
                metadata,
                bytes -> {
                  throw new AssertionError();
                }));
    assertZero(secrets.readBuffer);
  }

  @Test
  void metadataContainsOnlyAllowlistedComponentsAndNoSentinel() {
    Metadata metadata = prepare();
    assertEquals(4, Metadata.class.getRecordComponents().length);
    assertTrue(
        Arrays.stream(Metadata.class.getRecordComponents())
            .noneMatch(component -> component.getType().isArray()));
    assertFalse(metadata.toString().contains("sentinel"));
    assertFalse(metadata.toString().contains(metadata.digest()));
    assertFalse(metadata.reference().toString().contains("opaque"));
    assertFalse(metadata.digest().contains("sentinel"));
    assertEquals("opaque", metadata.reference().locator());
    assertEquals("secret-v1", metadata.reference().keyVersion());
    assertEquals(scope, metadata.scope());
  }

  @Test
  void supportsAllThreeProviders() {
    for (Provider provider : Provider.values()) {
      Scope next = new Scope(scope.organization(), scope.connection(), provider, 1);
      Metadata metadata = boundary.prepare(next, "sentinel".toCharArray());
      AtomicBoolean called = new AtomicBoolean();
      boundary.use(next, metadata, bytes -> called.set(true));
      assertTrue(called.get());
    }
    assertEquals(3, Provider.values().length);
  }

  @Test
  void deterministicMatchingAndCredentialChange() {
    Metadata first = prepare();
    assertEquals(first.digest(), prepare().digest());
    assertNotEquals(first.digest(), boundary.prepare(scope, "different".toCharArray()).digest());
  }

  @Test
  void everyScopeFieldChangesMac() {
    String digest = prepare().digest();
    for (Scope other : otherScopes()) {
      assertNotEquals(digest, boundary.prepare(other, "sentinel".toCharArray()).digest());
    }
  }

  @Test
  void expectedScopeMismatchNeverReadsOrConsumes() {
    Metadata metadata = prepare();
    for (Scope other : otherScopes()) {
      assertThrows(Failure.class, () -> boundary.use(other, metadata, bytes -> fail()));
    }
    assertEquals(0, secrets.reads);
  }

  @Test
  void storedScopeTamperFailsVerification() {
    Metadata metadata = prepare();
    for (Scope other : otherScopes()) {
      Metadata tampered =
          new Metadata(
              other,
              metadata.macVersion(),
              new Reference(other, "opaque", "secret-v1"),
              metadata.digest());
      assertThrows(Failure.class, () -> boundary.use(other, tampered, bytes -> fail()));
      assertZero(secrets.readBuffer);
    }
  }

  @Test
  void historicalVersionSurvivesRotation() {
    Metadata metadata = prepare();
    keys.current = "v2";
    assertNotEquals(metadata.digest(), prepare().digest());
    boundary.use(
        scope,
        metadata,
        bytes -> assertEquals("sentinel", new String(bytes, StandardCharsets.US_ASCII)));
    assertEquals("v1", keys.lastLookup);
    assertZero(secrets.readBuffer);
  }

  @Test
  void tamperedMacVersionFailsEvenWhenVersionExists() {
    Metadata metadata = prepare();
    Metadata tampered = new Metadata(scope, "v2", metadata.reference(), metadata.digest());
    assertThrows(Failure.class, () -> boundary.use(scope, tampered, bytes -> fail()));
    assertZero(secrets.readBuffer);
  }

  @Test
  void unknownHistoricalVersionNeverFallsBack() {
    Metadata metadata = prepare();
    keys.values.remove("v1");
    keys.current = "v2";
    assertThrows(Failure.class, () -> boundary.use(scope, metadata, bytes -> fail()));
    assertEquals("v1", keys.lastLookup);
    assertZero(secrets.readBuffer);
  }

  @Test
  void keyOutageClearsCallerAndNeverStores() {
    keys.outage = true;
    char[] caller = "sentinel".toCharArray();
    assertSafe(assertThrows(Failure.class, () -> boundary.prepare(scope, caller)));
    assertZero(caller);
    assertEquals(0, secrets.stores);
  }

  @Test
  void invalidCallerInputAlwaysCleared() {
    for (String invalid :
        new String[] {"", "has space", "tab\t", "unicodeé", "\u007f", "a".repeat(4097)}) {
      char[] caller = invalid.toCharArray();
      assertThrows(Failure.class, () -> boundary.prepare(scope, caller));
      assertZero(caller);
    }
    assertThrows(Failure.class, () -> boundary.prepare(scope, null));
    assertEquals(0, secrets.stores);
  }

  @Test
  void nullScopeStillClearsCaller() {
    char[] caller = "sentinel".toCharArray();
    assertThrows(Failure.class, () -> boundary.prepare(null, caller));
    assertZero(caller);
  }

  @Test
  void successfulPreparationClearsCallerAndBorrowedStoreBuffer() {
    char[] caller = "sentinel".toCharArray();
    boundary.prepare(scope, caller);
    assertZero(caller);
    assertZero(secrets.storeBuffer);
  }

  @Test
  void ambiguousStoreFailureIsReportedWithoutLeakingCause() {
    secrets.storeFailure = true;
    char[] caller = "sentinel".toCharArray();
    assertSafe(assertThrows(Failure.class, () -> boundary.prepare(scope, caller)));
    assertEquals(1, secrets.failures);
    assertZero(caller);
    assertZero(secrets.storeBuffer);
  }

  @Test
  void failureReporterFailureRemainsSafeAndClearsBuffers() {
    secrets.storeFailure = true;
    secrets.reportFailure = true;
    char[] caller = "sentinel".toCharArray();
    assertSafe(assertThrows(Failure.class, () -> boundary.prepare(scope, caller)));
    assertZero(caller);
    assertZero(secrets.storeBuffer);
  }

  @Test
  void invalidReturnedReferenceFailsAndReportsPreparation() {
    for (boolean nullReference : new boolean[] {true, false}) {
      secrets.nullReference = nullReference;
      secrets.wrongScope = !nullReference;
      char[] caller = "sentinel".toCharArray();
      assertThrows(Failure.class, () -> boundary.prepare(scope, caller));
      assertZero(caller);
      assertZero(secrets.storeBuffer);
    }
    assertEquals(2, secrets.failures);
  }

  @Test
  void changedExternalCredentialNeverReachesConsumer() {
    Metadata metadata = prepare();
    secrets.saved = "different".getBytes(StandardCharsets.US_ASCII);
    assertThrows(Failure.class, () -> boundary.use(scope, metadata, bytes -> fail()));
    assertZero(secrets.readBuffer);
  }

  @Test
  void malformedExternalBuffersAreRejectedAndCleared() {
    Metadata metadata = prepare();
    for (byte[] invalid : new byte[][] {null, {}, {0}, {32}, {127}, {(byte) 128}, new byte[4097]}) {
      secrets.saved = invalid;
      assertThrows(Failure.class, () -> boundary.use(scope, metadata, bytes -> fail()));
      if (secrets.readBuffer != null) assertZero(secrets.readBuffer);
    }
  }

  @Test
  void consumerFailureStillClearsReadBufferAndHidesCause() {
    Metadata metadata = prepare();
    assertSafe(
        assertThrows(
            Failure.class,
            () ->
                boundary.use(
                    scope,
                    metadata,
                    bytes -> {
                      throw new Exception("sentinel");
                    })));
    assertZero(secrets.readBuffer);
  }

  @Test
  void successfulUseClearsBorrowedReadBuffer() {
    Metadata metadata = prepare();
    AtomicBoolean called = new AtomicBoolean();
    boundary.use(
        scope,
        metadata,
        bytes -> {
          assertArrayEquals("sentinel".getBytes(StandardCharsets.US_ASCII), bytes);
          called.set(true);
        });
    assertTrue(called.get());
    assertZero(secrets.readBuffer);
  }

  @Test
  void readOutageIsSafeAndNeverConsumes() {
    Metadata metadata = prepare();
    secrets.readFailure = true;
    assertSafe(assertThrows(Failure.class, () -> boundary.use(scope, metadata, bytes -> fail())));
  }

  @Test
  void malformedMetadataAndReferenceRejected() {
    Metadata metadata = prepare();
    for (String invalid : new String[] {"", "bad", "a".repeat(44)}) {
      assertThrows(Failure.class, () -> new Metadata(scope, "v1", metadata.reference(), invalid));
    }
    assertThrows(Failure.class, () -> new Reference(scope, "has space", "v1"));
    assertThrows(Failure.class, () -> new Reference(scope, "opaque", ""));
    assertThrows(
        Failure.class,
        () -> new Metadata(otherScopes()[0], "v1", metadata.reference(), metadata.digest()));
  }

  private Metadata prepare() {
    return boundary.prepare(scope, "sentinel".toCharArray());
  }

  private Scope[] otherScopes() {
    return new Scope[] {
      new Scope(UUID.randomUUID(), scope.connection(), scope.provider(), 1),
      new Scope(scope.organization(), UUID.randomUUID(), scope.provider(), 1),
      new Scope(scope.organization(), scope.connection(), Provider.ANTHROPIC, 1),
      new Scope(scope.organization(), scope.connection(), scope.provider(), 2)
    };
  }

  private static void assertSafe(Failure failure) {
    assertNull(failure.getCause());
    assertEquals("Credential operation failed", failure.getMessage());
    assertFalse(failure.toString().contains("sentinel"));
    assertEquals(0, failure.getSuppressed().length);
  }

  private static void assertZero(byte[] bytes) {
    assertArrayEquals(new byte[bytes.length], bytes);
  }

  private static void assertZero(char[] chars) {
    assertArrayEquals(new char[chars.length], chars);
  }

  private static final class FakeKeys implements Keys {
    final Map<String, SecretKey> values = new HashMap<>();
    String current = "v1";
    String lastLookup;
    int lookups;
    boolean outage;

    FakeKeys() {
      byte[] one = new byte[32];
      byte[] two = new byte[32];
      Arrays.fill(one, (byte) 1);
      Arrays.fill(two, (byte) 2);
      values.put("v1", new SecretKeySpec(one, "HmacSHA256"));
      values.put("v2", new SecretKeySpec(two, "HmacSHA256"));
    }

    public String currentVersion() throws Exception {
      if (outage) throw new Exception("sentinel");
      return current;
    }

    public SecretKey key(String version) throws Exception {
      lookups++;
      lastLookup = version;
      if (outage || !values.containsKey(version)) throw new Exception("sentinel");
      return values.get(version);
    }
  }

  private static final class FakeSecrets implements Secrets {
    byte[] saved;
    byte[] storeBuffer;
    byte[] readBuffer;
    Reference lastReadReference;
    Runnable afterStore = () -> {};
    int stores;
    int reads;
    int failures;
    boolean storeFailure;
    boolean readFailure;
    boolean reportFailure;
    boolean nullReference;
    boolean wrongScope;

    public Reference store(Scope scope, byte[] credential) throws Exception {
      stores++;
      storeBuffer = credential;
      saved = credential.clone();
      if (storeFailure) throw new Exception("sentinel");
      if (nullReference) return null;
      Scope returned =
          wrongScope
              ? new Scope(UUID.randomUUID(), scope.connection(), scope.provider(), 1)
              : scope;
      Reference reference = new Reference(returned, "opaque", "secret-v1");
      afterStore.run();
      return reference;
    }

    public byte[] read(Reference reference) throws Exception {
      reads++;
      lastReadReference = reference;
      // Deliberately allow alternate same-scope references to return identical bytes: the MAC
      // must authenticate reference identity independently of credential-content equality.
      if (readFailure) throw new Exception("sentinel");
      readBuffer = saved == null ? null : saved.clone();
      return readBuffer;
    }

    public void preparationFailed(Scope scope, Reference reference) throws Exception {
      failures++;
      if (reportFailure) throw new Exception("sentinel");
    }
  }
}

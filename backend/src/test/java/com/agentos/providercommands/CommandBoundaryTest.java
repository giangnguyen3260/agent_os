package com.agentos.providercommands;

import static com.agentos.providercommands.CommandBoundary.DIGEST_VERSION;
import static com.agentos.providercommands.CommandBoundary.compare;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentos.providercommands.CommandBoundary.Code;
import com.agentos.providercommands.CommandBoundary.Command;
import com.agentos.providercommands.CommandBoundary.Comparison;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialCommand;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Failure;
import com.agentos.providercommands.CommandBoundary.Keys;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercommands.CommandBoundary.Rotate;
import com.agentos.providercommands.CommandBoundary.Scope;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CommandBoundaryTest {
  private static final UUID ORG = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TARGET = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final Scope SCOPE = new Scope(ORG, ACTOR);
  private static final Key KEY = new SecretKeySpec(new byte[32], "HmacSHA256");
  private static final String ZERO = Base64.getEncoder().encodeToString(new byte[32]);

  private static final class KeyPort implements Keys {
    String current = "v1";
    boolean currentFails;
    boolean keyFails;
    Key key = KEY;
    int currentCalls;
    int keyCalls;
    String requested;

    public String currentVersion() {
      currentCalls++;
      if (currentFails) throw new IllegalStateException("DEPENDENCY_SECRET");
      return current;
    }

    public Key key(String version) {
      keyCalls++;
      requested = version;
      if (keyFails) throw new IllegalStateException("DEPENDENCY_SECRET");
      return key;
    }
  }

  private static Create create(String name) {
    return new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, name, null);
  }

  private static void failure(Code code, org.junit.jupiter.api.function.Executable action) {
    Failure error = assertThrows(Failure.class, action);
    assertEquals(code, error.code());
    assertEquals(code.name(), error.getMessage());
    assertNull(error.getCause());
    assertEquals(0, error.getStackTrace().length);
    assertFalse(error.toString().contains("DEPENDENCY_SECRET"));
  }

  @Test
  void allOperationsRoundTripAndDoNotMutateBorrowedSecret() {
    KeyPort port = new KeyPort();
    CommandBoundary boundary = new CommandBoundary(port);
    List<Command> commands =
        List.of(
            create("Name"),
            new Rename(TARGET, 0, "Name"),
            new Rotate(TARGET, Long.MAX_VALUE),
            new RevisionCommand(Operation.DISABLE, TARGET, 1),
            new RevisionCommand(Operation.ENABLE, TARGET, 1),
            new CredentialCommand(Operation.ACCESS_CHECK, TARGET, 1, 2),
            new CredentialCommand(Operation.MODEL_DISCOVERY, TARGET, 1, 2));
    for (Command command : commands) {
      char[] secret =
          command instanceof Create || command instanceof Rotate ? "Exact!".toCharArray() : null;
      char[] before = secret == null ? null : secret.clone();
      Metadata metadata = boundary.prepareCurrent(SCOPE, command, secret);
      assertEquals(Comparison.MATCH, boundary.verify(SCOPE, command, secret, metadata));
      assertArrayEquals(before, secret);
      assertEquals(DIGEST_VERSION, metadata.digestVersion());
      assertEquals(44, metadata.digest().length());
    }
    assertEquals(7, port.currentCalls);
  }

  @Test
  void historicalVerificationNeverConsultsCurrentVersionOrFallsBack() {
    KeyPort port = new KeyPort();
    CommandBoundary boundary = new CommandBoundary(port);
    char[] secret = {'!'};
    Metadata stored = boundary.prepareCurrent(SCOPE, create("A"), secret);
    port.current = "v2";
    port.currentFails = true;
    assertEquals(Comparison.MATCH, boundary.verify(SCOPE, create("A"), secret, stored));
    assertEquals("v1", port.requested);
    assertEquals(1, port.currentCalls);
    port.keyFails = true;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.verify(SCOPE, create("A"), secret, stored));
    assertEquals(1, port.currentCalls);
    assertArrayEquals(new char[] {'!'}, secret);
  }

  @Test
  void everyIncludedFieldChangesDigest() {
    CommandBoundary boundary = new CommandBoundary(new KeyPort());
    Create original = create("Name");
    char[] secret = {'!'};
    Metadata expected = boundary.prepareVersion(SCOPE, original, secret, "v1");
    assertEquals(
        Comparison.MISMATCH, boundary.verify(new Scope(OTHER, ACTOR), original, secret, expected));
    assertEquals(
        Comparison.MISMATCH, boundary.verify(new Scope(ORG, OTHER), original, secret, expected));
    assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, create("name"), secret, expected));
    assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, original, new char[] {'"'}, expected));
    for (Create changed :
        List.of(
            new Create(Provider.ANTHROPIC, CredentialMode.ANTHROPIC_API_KEY, "Name", null),
            new Create(Provider.GOOGLE_GEMINI, CredentialMode.GEMINI_API_KEY, "Name", null))) {
      assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, changed, secret, expected));
    }
    Metadata aliasedKey = boundary.prepareVersion(SCOPE, original, secret, "v2");
    assertNotEquals(expected.digest(), aliasedKey.digest());
    failure(Code.INVALID_METADATA, () -> compare(expected, aliasedKey));
    Create anthropic =
        new Create(Provider.ANTHROPIC, CredentialMode.ANTHROPIC_API_KEY, "Name", null);
    Metadata workspaceNull = boundary.prepareCurrent(SCOPE, anthropic, secret);
    assertEquals(
        Comparison.MISMATCH,
        boundary.verify(
            SCOPE,
            new Create(Provider.ANTHROPIC, CredentialMode.ANTHROPIC_API_KEY, "Name", "wrkspc_A"),
            secret,
            workspaceNull));
    for (Operation operation : List.of(Operation.DISABLE, Operation.ENABLE)) {
      Metadata m = boundary.prepareCurrent(SCOPE, new RevisionCommand(operation, TARGET, 1), null);
      assertEquals(
          Comparison.MISMATCH,
          boundary.verify(SCOPE, new RevisionCommand(operation, OTHER, 1), null, m));
      assertEquals(
          Comparison.MISMATCH,
          boundary.verify(SCOPE, new RevisionCommand(operation, TARGET, 2), null, m));
      Operation other = operation == Operation.DISABLE ? Operation.ENABLE : Operation.DISABLE;
      assertEquals(
          Comparison.MISMATCH,
          boundary.verify(SCOPE, new RevisionCommand(other, TARGET, 1), null, m));
    }
    for (Operation operation : List.of(Operation.ACCESS_CHECK, Operation.MODEL_DISCOVERY)) {
      Metadata m =
          boundary.prepareCurrent(SCOPE, new CredentialCommand(operation, TARGET, 1, 2), null);
      for (Command changed :
          List.of(
              new CredentialCommand(operation, OTHER, 1, 2),
              new CredentialCommand(operation, TARGET, 2, 2),
              new CredentialCommand(operation, TARGET, 1, 3),
              new CredentialCommand(
                  operation == Operation.ACCESS_CHECK
                      ? Operation.MODEL_DISCOVERY
                      : Operation.ACCESS_CHECK,
                  TARGET,
                  1,
                  2))) {
        assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, changed, null, m));
      }
    }
    Metadata rename = boundary.prepareCurrent(SCOPE, new Rename(TARGET, 1, "A"), null);
    for (Rename changed :
        List.of(new Rename(OTHER, 1, "A"), new Rename(TARGET, 2, "A"), new Rename(TARGET, 1, "B")))
      assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, changed, null, rename));
    Metadata rotate = boundary.prepareCurrent(SCOPE, new Rotate(TARGET, 1), secret);
    assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, new Rotate(OTHER, 1), secret, rotate));
    assertEquals(
        Comparison.MISMATCH, boundary.verify(SCOPE, new Rotate(TARGET, 2), secret, rotate));
    assertEquals(
        Comparison.MISMATCH,
        boundary.verify(SCOPE, new Rotate(TARGET, 1), new char[] {'?'}, rotate));
  }

  @Test
  void namesNormalizeOnlyEdgesAndPreserveCaseCompositionAndSupplementaryCharacters() {
    CommandBoundary boundary = new CommandBoundary(new KeyPort());
    char[] secret = {'!'};
    Metadata original = boundary.prepareCurrent(SCOPE, create("é😀"), secret);
    assertEquals(
        Comparison.MATCH,
        boundary.verify(SCOPE, create("\ufeff\u00a0\t é😀 \u2003\ufeff"), secret, original));
    assertEquals(
        Comparison.MISMATCH, boundary.verify(SCOPE, create("e\u0301😀"), secret, original));
    assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, create("É😀"), secret, original));
    assertEquals("😀".repeat(100), create("😀".repeat(100)).name());
    failure(Code.INVALID_COMMAND, () -> create("😀".repeat(101)));
    for (String invalid :
        List.of(
            "",
            " \ufeff",
            "x\nY",
            "x\u0000Y",
            "x\u2028Y",
            "x\u2029Y",
            "\ud800",
            "\udc00",
            "x\ud800Y")) failure(Code.INVALID_COMMAND, () -> create(invalid));
    failure(Code.INVALID_COMMAND, () -> create(null));
  }

  @Test
  void invalidShapesFailBeforeKeyLookup() {
    KeyPort port = new KeyPort();
    CommandBoundary boundary = new CommandBoundary(port);
    for (char[] secret :
        List.of(
            new char[0],
            new char[] {' '},
            new char[] {'\u007f'},
            new char[] {'é'},
            new char[4097])) {
      char[] before = secret.clone();
      failure(Code.INVALID_COMMAND, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
      assertArrayEquals(before, secret);
    }
    failure(Code.INVALID_COMMAND, () -> boundary.prepareCurrent(SCOPE, create("A"), null));
    failure(
        Code.INVALID_COMMAND, () -> boundary.prepareCurrent(null, create("A"), new char[] {'!'}));
    failure(Code.INVALID_COMMAND, () -> boundary.prepareCurrent(SCOPE, null, null));
    for (Command c :
        List.of(
            new Rename(TARGET, 1, "A"),
            new RevisionCommand(Operation.ENABLE, TARGET, 1),
            new CredentialCommand(Operation.ACCESS_CHECK, TARGET, 1, 1)))
      failure(Code.INVALID_COMMAND, () -> boundary.prepareCurrent(SCOPE, c, new char[0]));
    assertEquals(0, port.currentCalls);
    assertEquals(0, port.keyCalls);
    char[] longest = new char[4096];
    Arrays.fill(longest, '~');
    boundary.prepareCurrent(SCOPE, create("A"), longest);
    assertEquals('~', longest[4095]);
  }

  @Test
  void constructorsRejectInvalidUuidRevisionProviderAndWorkspaceShapes() {
    for (UUID invalid :
        List.of(
            new UUID(0, 0),
            UUID.fromString("11111111-1111-6111-8111-111111111111"),
            UUID.fromString("11111111-1111-4111-0111-111111111111"))) {
      failure(Code.INVALID_COMMAND, () -> new Scope(invalid, ACTOR));
      failure(Code.INVALID_COMMAND, () -> new Scope(ORG, invalid));
      failure(Code.INVALID_COMMAND, () -> new Rotate(invalid, 0));
    }
    failure(Code.INVALID_COMMAND, () -> new Scope(null, ACTOR));
    failure(Code.INVALID_COMMAND, () -> new Rename(null, 0, "A"));
    failure(Code.INVALID_COMMAND, () -> new Rotate(TARGET, -1));
    failure(Code.INVALID_COMMAND, () -> new Rename(TARGET, -1, "A"));
    failure(Code.INVALID_COMMAND, () -> new RevisionCommand(Operation.CREATE, TARGET, 0));
    failure(Code.INVALID_COMMAND, () -> new RevisionCommand(null, TARGET, 0));
    failure(Code.INVALID_COMMAND, () -> new RevisionCommand(Operation.ENABLE, TARGET, -1));
    failure(Code.INVALID_COMMAND, () -> new CredentialCommand(Operation.ROTATE, TARGET, 0, 0));
    failure(
        Code.INVALID_COMMAND, () -> new CredentialCommand(Operation.ACCESS_CHECK, TARGET, -1, 0));
    failure(
        Code.INVALID_COMMAND, () -> new CredentialCommand(Operation.ACCESS_CHECK, TARGET, 0, -1));
    for (Provider provider : Provider.values())
      for (CredentialMode mode : CredentialMode.values()) {
        boolean valid = provider.ordinal() == mode.ordinal();
        if (valid) assertNotNull(new Create(provider, mode, "A", null));
        else failure(Code.INVALID_COMMAND, () -> new Create(provider, mode, "A", null));
      }
    failure(Code.INVALID_COMMAND, () -> new Create(null, CredentialMode.OPENAI_API_KEY, "A", null));
    failure(Code.INVALID_COMMAND, () -> new Create(Provider.OPENAI, null, "A", null));
    failure(
        Code.INVALID_COMMAND,
        () -> new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "A", "wrkspc_A"));
    for (String workspace :
        List.of("", "wrkspc_", "wrkspc_a-", "wrkspc_é", "wrkspc_" + "a".repeat(129)))
      failure(
          Code.INVALID_COMMAND,
          () -> new Create(Provider.ANTHROPIC, CredentialMode.ANTHROPIC_API_KEY, "A", workspace));
    assertNotNull(
        new Create(
            Provider.ANTHROPIC,
            CredentialMode.ANTHROPIC_API_KEY,
            "A",
            "wrkspc_" + "Z".repeat(128)));
  }

  @Test
  void metadataIsStrictAndComparisonRequiresMatchingVersions() {
    for (String version : List.of("", "v 1", "é", "a".repeat(257))) {
      failure(Code.INVALID_METADATA, () -> new Metadata(DIGEST_VERSION, version, ZERO));
    }
    failure(Code.INVALID_METADATA, () -> new Metadata(DIGEST_VERSION, null, ZERO));
    failure(Code.INVALID_METADATA, () -> new Metadata(null, "v1", ZERO));
    failure(Code.INVALID_METADATA, () -> new Metadata("PROVIDER_COMMAND_V2", "v1", ZERO));
    failure(Code.INVALID_METADATA, () -> new Metadata(DIGEST_VERSION, "v1", null));
    for (String digest :
        List.of(
            "",
            ZERO.substring(0, 43),
            "!".repeat(44),
            ZERO.substring(0, 42) + "B=",
            Base64.getEncoder().encodeToString(new byte[31]),
            Base64.getEncoder().encodeToString(new byte[33])))
      failure(Code.INVALID_METADATA, () -> new Metadata(DIGEST_VERSION, "v1", digest));
    Metadata m = new Metadata(DIGEST_VERSION, "v1", ZERO);
    assertEquals(Comparison.MATCH, compare(m, m));
    failure(Code.INVALID_METADATA, () -> compare(null, m));
    failure(Code.INVALID_METADATA, () -> compare(m, null));
    failure(Code.INVALID_METADATA, () -> compare(m, new Metadata(DIGEST_VERSION, "v2", ZERO)));
    KeyPort port = new KeyPort();
    failure(
        Code.INVALID_METADATA,
        () -> new CommandBoundary(port).verify(SCOPE, create("A"), new char[] {'!'}, null));
    assertEquals(0, port.keyCalls);
  }

  @Test
  void unavailableInvalidAndWeakKeysProduceCauseFreeFailuresAndLeaveSecretUnchanged() {
    failure(Code.KEY_UNAVAILABLE, () -> new CommandBoundary(null));
    KeyPort port = new KeyPort();
    CommandBoundary boundary = new CommandBoundary(port);
    char[] secret = "Sensitive!".toCharArray();
    char[] before = secret.clone();
    port.currentFails = true;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    port.currentFails = false;
    for (String version : List.of("", "bad version", "a".repeat(257))) {
      port.current = version;
      failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    }
    port.current = null;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    port.current = "v1";
    port.keyFails = true;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    port.keyFails = false;
    port.key = null;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    port.key = new SecretKeySpec(new byte[31], "HmacSHA256");
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    port.key =
        new Key() {
          public String getAlgorithm() {
            return "HmacSHA256";
          }

          public String getFormat() {
            return null;
          }

          public byte[] getEncoded() {
            return null;
          }
        };
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
    failure(
        Code.INVALID_METADATA,
        () -> boundary.prepareVersion(SCOPE, create("A"), secret, "bad version"));
    assertArrayEquals(before, secret);
  }

  @Test
  void commandScopeAndMetadataRenderingIsRedacted() {
    assertEquals("Scope[REDACTED]", SCOPE.toString());
    for (Object value :
        List.of(
            create("PRIVATE_NAME"),
            new Rename(TARGET, 1, "PRIVATE_NAME"),
            new Rotate(TARGET, 1),
            new RevisionCommand(Operation.ENABLE, TARGET, 1),
            new CredentialCommand(Operation.ACCESS_CHECK, TARGET, 1, 1),
            new Metadata(DIGEST_VERSION, "v1", ZERO))) {
      assertTrue(value.toString().endsWith("[REDACTED]"));
      assertFalse(value.toString().contains(TARGET.toString()));
      assertFalse(value.toString().contains("PRIVATE_NAME"));
    }
  }

  // Literal expectations copied from the independently generated committed v1-vectors.json.
  // No production encoder or MAC produces the expected bytes or digest.
  private record Vector(
      String id,
      String keyHex,
      String keyVersion,
      String organization,
      String actor,
      String operation,
      String target,
      String provider,
      String mode,
      String name,
      String normalizedName,
      String workspace,
      String revision,
      String credentialRevision,
      String secret,
      String canonicalHex,
      String digest) {
    @Override
    public String toString() {
      return id;
    }
  }

  static Stream<Vector> knownAnswers() {
    return Stream.of(
        new Vector(
            "v1-01",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "CREATE",
            null,
            "OPENAI",
            "OPENAI_API_KEY",
            "﻿ Café 🌱 ",
            "Café 🌱",
            null,
            null,
            null,
            "synthetic-!~",
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d32323232323232323232323201000000064352454154450001000000064f50454e4149010000000e4f50454e41495f4150495f4b4559010000000a436166c3a920f09f8cb10200000000010000000c73796e7468657469632d217e",
            "ODMJ2F0JxQ4KVhS4o2NHiJvxPKWJVnmXAtUbqBJ9D5c="),
        new Vector(
            "v1-02",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "CREATE",
            null,
            "ANTHROPIC",
            "ANTHROPIC_API_KEY",
            "﻿ Café 🌱 ",
            "Café 🌱",
            null,
            null,
            null,
            "synthetic-!~",
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d3232323232323232323232320100000006435245415445000100000009414e5448524f5049430100000011414e5448524f5049435f4150495f4b4559010000000a436166c3a920f09f8cb10200000001010000000b776f726b7370616365496400010000000c73796e7468657469632d217e",
            "EXOOrqXbk7tY06JCNTVWzURfm8Fu4QCI9AZpmONKkgk="),
        new Vector(
            "v1-03",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "CREATE",
            null,
            "ANTHROPIC",
            "ANTHROPIC_API_KEY",
            "﻿ Café 🌱 ",
            "Café 🌱",
            "wrkspc_A12",
            null,
            null,
            "synthetic-!~",
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d3232323232323232323232320100000006435245415445000100000009414e5448524f5049430100000011414e5448524f5049435f4150495f4b4559010000000a436166c3a920f09f8cb10200000001010000000b776f726b73706163654964010000000a77726b7370635f413132010000000c73796e7468657469632d217e",
            "ZosTpC8I3IYSJNPMATrY8GpSpUR24DOD3ZkSTB/FeQs="),
        new Vector(
            "v1-04",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "CREATE",
            null,
            "GOOGLE_GEMINI",
            "GEMINI_API_KEY",
            "﻿ Café 🌱 ",
            "Café 🌱",
            null,
            null,
            null,
            "synthetic-!~",
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d323232323232323232323232010000000643524541544500010000000d474f4f474c455f47454d494e49010000000e47454d494e495f4150495f4b4559010000000a436166c3a920f09f8cb10200000000010000000c73796e7468657469632d217e",
            "C8k0MTcFlUUfdMlKOpIxVEgShtP3qBPqVGJJimuMxGg="),
        new Vector(
            "v1-05",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "RENAME",
            "33333333-3333-4333-8333-333333333333",
            null,
            null,
            "  Á 🌱  ",
            "Á 🌱",
            null,
            "0",
            null,
            null,
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d323232323232323232323232010000000652454e414d45010000002433333333333333332d333333332d343333332d383333332d333333333333333333333333010000000130010000000841cc8120f09f8cb1",
            "KthEb/dToi3OcBuF1Dsj9mOfERgBJWaiNpmcsHFivE0="),
        new Vector(
            "v1-06",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "ROTATE",
            "33333333-3333-4333-8333-333333333333",
            null,
            null,
            null,
            null,
            null,
            "9223372036854775807",
            null,
            "synthetic-rotate",
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d3232323232323232323232320100000006524f54415445010000002433333333333333332d333333332d343333332d383333332d333333333333333333333333010000001339323233333732303336383534373735383037010000001073796e7468657469632d726f74617465",
            "16xpFCEZVqGGrnQ7sZIpH+xCm2rCGiB0ArS1LrEhFfs="),
        new Vector(
            "v1-07",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "DISABLE",
            "33333333-3333-4333-8333-333333333333",
            null,
            null,
            null,
            null,
            null,
            "17",
            null,
            null,
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d323232323232323232323232010000000744495341424c45010000002433333333333333332d333333332d343333332d383333332d33333333333333333333333301000000023137",
            "pIqyroRQaeP8R2SOFpHk2Zb7QGp8mEaDPRjrAVJoPkw="),
        new Vector(
            "v1-08",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "ENABLE",
            "33333333-3333-4333-8333-333333333333",
            null,
            null,
            null,
            null,
            null,
            "17",
            null,
            null,
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d3232323232323232323232320100000006454e41424c45010000002433333333333333332d333333332d343333332d383333332d33333333333333333333333301000000023137",
            "UQ5UwAr/NOPgtY91B+9THxz5ydlNqlnpnIIq+zEUkHQ="),
        new Vector(
            "v1-09",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "ACCESS_CHECK",
            "33333333-3333-4333-8333-333333333333",
            null,
            null,
            null,
            null,
            null,
            "17",
            "3",
            null,
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d323232323232323232323232010000000c4143434553535f434845434b010000002433333333333333332d333333332d343333332d383333332d33333333333333333333333301000000023137010000000133",
            "myI1zf4yi0zHx6YyySyH1RsGQKyzpaBJyS9dSY1lCMk="),
        new Vector(
            "v1-10",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            "command/test-key:v1",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "MODEL_DISCOVERY",
            "33333333-3333-4333-8333-333333333333",
            null,
            null,
            null,
            null,
            null,
            "17",
            "3",
            null,
            "010000001350524f56494445525f434f4d4d414e445f56310100000013636f6d6d616e642f746573742d6b65793a7631010000002431313131313131312d313131312d343131312d383131312d313131313131313131313131010000002432323232323232322d323232322d343232322d383232322d323232323232323232323232010000000f4d4f44454c5f444953434f56455259010000002433333333333333332d333333332d343333332d383333332d33333333333333333333333301000000023137010000000133",
            "Fqn+JjBL3Azw7ekssWnnX9+hB0RJKQxoOMaswl1zyhg="));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("knownAnswers")
  void independentKnownAnswersAndCanonicalByteGrammar(Vector v) throws Exception {
    Operation op = Operation.valueOf(v.operation());
    UUID target = v.target() == null ? null : UUID.fromString(v.target());
    Command command =
        switch (op) {
          case CREATE ->
              new Create(
                  Provider.valueOf(v.provider()),
                  CredentialMode.valueOf(v.mode()),
                  v.name(),
                  v.workspace());
          case RENAME -> new Rename(target, Long.parseLong(v.revision()), v.name());
          case ROTATE -> new Rotate(target, Long.parseLong(v.revision()));
          case ENABLE, DISABLE -> new RevisionCommand(op, target, Long.parseLong(v.revision()));
          case ACCESS_CHECK, MODEL_DISCOVERY ->
              new CredentialCommand(
                  op, target, Long.parseLong(v.revision()), Long.parseLong(v.credentialRevision()));
        };
    KeyPort port = new KeyPort();
    port.current = v.keyVersion();
    port.key = new SecretKeySpec(HexFormat.of().parseHex(v.keyHex()), "HmacSHA256");
    Scope scope = new Scope(UUID.fromString(v.organization()), UUID.fromString(v.actor()));
    char[] secret = v.secret() == null ? null : v.secret().toCharArray();
    char[] before = secret == null ? null : secret.clone();
    CommandBoundary boundary = new CommandBoundary(port);
    Metadata actual = boundary.prepareCurrent(scope, command, secret);
    assertEquals(v.digest(), actual.digest());
    assertEquals(v.keyVersion(), actual.keyVersion());
    assertEquals(
        Comparison.MATCH,
        boundary.verify(
            scope,
            command,
            secret,
            new Metadata("PROVIDER_COMMAND_V1", v.keyVersion(), v.digest())));
    assertArrayEquals(before, secret);
    assertEquals(
        Comparison.MISMATCH,
        boundary.verify(new Scope(OTHER, scope.actor()), command, secret, actual));
    assertEquals(
        Comparison.MISMATCH,
        boundary.verify(new Scope(scope.organization(), OTHER), command, secret, actual));
    assertNotEquals(
        actual.digest(),
        boundary.prepareVersion(scope, command, secret, v.keyVersion() + "/alias").digest());
    assertArrayEquals(before, secret);
    failure(Code.INVALID_METADATA, () -> boundary.verify(scope, command, secret, null));
    assertArrayEquals(before, secret);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    for (String field :
        new String[] {
          "PROVIDER_COMMAND_V1",
          v.keyVersion(),
          v.organization(),
          v.actor(),
          v.operation(),
          v.target()
        }) oracleValue(out, field);
    switch (op) {
      case CREATE -> {
        oracleValue(out, v.provider());
        oracleValue(out, v.mode());
        oracleValue(out, v.normalizedName());
        Map<String, Object> options = new LinkedHashMap<>();
        if (v.provider().equals("ANTHROPIC")) options.put("workspaceId", v.workspace());
        oracleValue(out, options);
        oracleValue(out, v.secret());
      }
      case RENAME -> {
        oracleValue(out, v.revision());
        oracleValue(out, v.normalizedName());
      }
      case ROTATE -> {
        oracleValue(out, v.revision());
        oracleValue(out, v.secret());
      }
      case ENABLE, DISABLE -> oracleValue(out, v.revision());
      case ACCESS_CHECK, MODEL_DISCOVERY -> {
        oracleValue(out, v.revision());
        oracleValue(out, v.credentialRevision());
      }
    }
    assertEquals(v.canonicalHex(), HexFormat.of().formatHex(bytes.toByteArray()));
    // Independently cross-check the literal digest against the literal byte stream as well.
    javax.crypto.Mac independent = javax.crypto.Mac.getInstance("HmacSHA256");
    independent.init(port.key);
    assertEquals(
        v.digest(),
        Base64.getEncoder()
            .encodeToString(independent.doFinal(HexFormat.of().parseHex(v.canonicalHex()))));
  }

  @Test
  void framedBoundariesCannotShiftBetweenNameWorkspaceOrSecret() {
    CommandBoundary boundary = new CommandBoundary(new KeyPort());
    Metadata first = boundary.prepareCurrent(SCOPE, create("ab"), "c".toCharArray());
    assertEquals(
        Comparison.MISMATCH, boundary.verify(SCOPE, create("a"), "bc".toCharArray(), first));
    Create left =
        new Create(Provider.ANTHROPIC, CredentialMode.ANTHROPIC_API_KEY, "A", "wrkspc_AB");
    Create right =
        new Create(Provider.ANTHROPIC, CredentialMode.ANTHROPIC_API_KEY, "A", "wrkspc_A");
    Metadata second = boundary.prepareCurrent(SCOPE, left, "C".toCharArray());
    assertEquals(Comparison.MISMATCH, boundary.verify(SCOPE, right, "BC".toCharArray(), second));
  }

  @Test
  void allNonsecretOperationsRejectBothEmptyAndNonemptyExtraSecrets() {
    KeyPort port = new KeyPort();
    CommandBoundary boundary = new CommandBoundary(port);
    for (Command command :
        List.of(
            new Rename(TARGET, 0, "A"),
            new RevisionCommand(Operation.DISABLE, TARGET, 0),
            new RevisionCommand(Operation.ENABLE, TARGET, 0),
            new CredentialCommand(Operation.ACCESS_CHECK, TARGET, 0, 0),
            new CredentialCommand(Operation.MODEL_DISCOVERY, TARGET, 0, 0))) {
      for (char[] secret : List.of(new char[0], new char[] {'!'})) {
        char[] before = secret.clone();
        failure(Code.INVALID_COMMAND, () -> boundary.prepareCurrent(SCOPE, command, secret));
        assertArrayEquals(before, secret);
      }
    }
    assertEquals(0, port.currentCalls);
    assertEquals(0, port.keyCalls);
  }

  /** Test-only JCA provider: accepts opaque keys and can fail after successful initialization. */
  public static final class ProbeMac extends javax.crypto.MacSpi {
    private boolean fail;

    @Override
    protected int engineGetMacLength() {
      return 32;
    }

    @Override
    protected void engineInit(Key key, java.security.spec.AlgorithmParameterSpec parameters)
        throws java.security.InvalidKeyException {
      if (!key.getAlgorithm().equals("ProbeOpaque")) throw new java.security.InvalidKeyException();
      fail = key.getFormat() != null;
    }

    @Override
    protected void engineUpdate(byte value) {
      if (fail) throw new IllegalStateException("DEPENDENCY_SECRET");
    }

    @Override
    protected void engineUpdate(byte[] value, int offset, int length) {
      if (fail) throw new IllegalStateException("DEPENDENCY_SECRET");
    }

    @Override
    protected byte[] engineDoFinal() {
      return new byte[32];
    }

    @Override
    protected void engineReset() {}
  }

  @Test
  void opaqueKeysCanWorkAndUnexpectedComputationFailuresAreInternalAndRedacted() {
    java.security.Provider provider =
        new java.security.Provider("CommandBoundaryTestProbe", "1.0", "test only") {};
    provider.put("Mac.HmacSHA256", ProbeMac.class.getName());
    assertEquals(1, java.security.Security.insertProviderAt(provider, 1));
    try {
      KeyPort port = new KeyPort();
      CommandBoundary boundary = new CommandBoundary(port);
      char[] secret = "Sensitive!".toCharArray();
      char[] before = secret.clone();
      port.key = opaqueKey(false);
      assertNotNull(boundary.prepareCurrent(SCOPE, create("A"), secret));
      port.key = opaqueKey(true);
      failure(Code.INTERNAL_FAILURE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
      assertArrayEquals(before, secret);
      port.key =
          new Key() {
            public String getAlgorithm() {
              return "ProbeOpaque";
            }

            public String getFormat() {
              return null;
            }

            public byte[] getEncoded() {
              throw new IllegalStateException("DEPENDENCY_SECRET");
            }
          };
      failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareCurrent(SCOPE, create("A"), secret));
      assertArrayEquals(before, secret);
    } finally {
      java.security.Security.removeProvider(provider.getName());
    }
  }

  private static javax.crypto.SecretKey opaqueKey(boolean fail) {
    return new javax.crypto.SecretKey() {
      public String getAlgorithm() {
        return "ProbeOpaque";
      }

      public String getFormat() {
        return fail ? "probe-failure" : null;
      }

      public byte[] getEncoded() {
        return null;
      }
    };
  }

  @Test
  void versionIdentityAndUuidBoundariesAndOwnedKeyCopyErasure() {
    KeyPort port = new KeyPort();
    CommandBoundary boundary = new CommandBoundary(port);
    for (String version : List.of("a", "A0_./:@-", "x".repeat(256))) {
      assertEquals(
          version,
          boundary.prepareVersion(SCOPE, create("A"), new char[] {'!'}, version).keyVersion());
    }
    for (int version = 1; version <= 5; version++) {
      UUID value = UUID.fromString("11111111-1111-" + version + "111-8111-111111111111");
      assertNotNull(
          boundary.prepareCurrent(new Scope(value, value), new Rotate(value, 0), new char[] {'!'}));
    }
    ExportingSecretKey key = new ExportingSecretKey(32);
    port.key = key;
    byte[] original = key.backing.clone();
    char[] secret = "borrowed!".toCharArray();
    Metadata first = boundary.prepareCurrent(SCOPE, create("A"), secret);
    assertArrayEquals(new byte[32], key.exports.get(0));
    assertArrayEquals(original, key.backing);
    int nextExport = key.exports.size();
    assertEquals(first, boundary.prepareVersion(SCOPE, create("A"), secret, "v1"));
    assertArrayEquals(new byte[32], key.exports.get(nextExport));
    assertArrayEquals(original, key.backing);
    nextExport = key.exports.size();
    port.currentFails = true;
    assertEquals(Comparison.MATCH, boundary.verify(SCOPE, create("A"), secret, first));
    assertArrayEquals(new byte[32], key.exports.get(nextExport));
    assertArrayEquals(original, key.backing);
    assertArrayEquals("borrowed!".toCharArray(), secret);

    ExportingSecretKey weak = new ExportingSecretKey(31);
    port.key = weak;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareVersion(SCOPE, create("A"), secret, "v1"));
    assertArrayEquals(new byte[31], weak.exports.get(0));
    byte[] weakOriginal = new byte[31];
    Arrays.fill(weakOriginal, (byte) 7);
    assertArrayEquals(weakOriginal, weak.backing);

    // A non-SecretKey is unusable by the JDK HMAC provider, after our export check.
    ExportingKey invalid = new ExportingKey(32);
    port.key = invalid;
    failure(Code.KEY_UNAVAILABLE, () -> boundary.prepareVersion(SCOPE, create("A"), secret, "v1"));
    assertArrayEquals(new byte[32], invalid.exports.get(0));
    assertArrayEquals(original, invalid.backing);
    assertArrayEquals("borrowed!".toCharArray(), secret);
  }

  private static class ExportingKey implements Key {
    final byte[] backing;
    final List<byte[]> exports = new ArrayList<>();

    ExportingKey(int size) {
      backing = new byte[size];
      Arrays.fill(backing, (byte) 7);
    }

    public String getAlgorithm() {
      return "HmacSHA256";
    }

    public String getFormat() {
      return "RAW";
    }

    public byte[] getEncoded() {
      byte[] owned = backing.clone();
      exports.add(owned);
      return owned;
    }
  }

  private static final class ExportingSecretKey extends ExportingKey
      implements javax.crypto.SecretKey {
    ExportingSecretKey(int size) {
      super(size);
    }
  }

  private static void oracleValue(DataOutputStream out, Object value) throws Exception {
    if (value == null) {
      out.writeByte(0);
    } else if (value instanceof Map<?, ?> fields) {
      out.writeByte(2);
      out.writeInt(fields.size());
      for (Map.Entry<?, ?> field : fields.entrySet()) {
        oracleValue(out, field.getKey());
        oracleValue(out, field.getValue());
      }
    } else {
      byte[] encoded = ((String) value).getBytes(StandardCharsets.UTF_8);
      out.writeByte(1);
      out.writeInt(encoded.length);
      out.write(encoded);
    }
  }
}

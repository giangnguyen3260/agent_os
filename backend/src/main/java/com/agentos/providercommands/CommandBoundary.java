package com.agentos.providercommands;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;

/** Internal, synchronous command equality primitive. The caller owns every borrowed secret. */
public final class CommandBoundary {
  public static final String DIGEST_VERSION = "PROVIDER_COMMAND_V1";
  private final Keys keys;

  /**
   * Command-specific managed keys. Handles remain provider-owned and immutable per version. Every
   * non-null Key.getEncoded() result must be a fresh, exclusively owned disposable copy; its caller
   * may erase it immediately after initialization or on failure. Neither the key nor its provider
   * may retain or share that copy. Null encodings remain valid for usable opaque keys. Shared
   * backing arrays are unsupported and cannot be detected by this boundary.
   */
  public interface Keys {
    String currentVersion();

    Key key(String version);
  }

  public enum Code {
    INVALID_COMMAND,
    INVALID_METADATA,
    KEY_UNAVAILABLE,
    INTERNAL_FAILURE
  }

  public enum Comparison {
    MATCH,
    MISMATCH
  }

  public enum Provider {
    OPENAI,
    ANTHROPIC,
    GOOGLE_GEMINI
  }

  public enum CredentialMode {
    OPENAI_API_KEY,
    ANTHROPIC_API_KEY,
    GEMINI_API_KEY
  }

  public enum Operation {
    CREATE,
    RENAME,
    ROTATE,
    DISABLE,
    ENABLE,
    ACCESS_CHECK,
    MODEL_DISCOVERY
  }

  public static final class Failure extends RuntimeException {
    private final Code code;

    private Failure(Code code) {
      super(code.name(), null, false, false);
      this.code = code;
    }

    public Code code() {
      return code;
    }
  }

  public record Scope(UUID organization, UUID actor) {
    public Scope {
      uuid(organization);
      uuid(actor);
    }

    @Override
    public String toString() {
      return "Scope[REDACTED]";
    }
  }

  public sealed interface Command
      permits Create, Rename, Rotate, RevisionCommand, CredentialCommand {
    Operation operation();

    UUID target();
  }

  public record Create(
      Provider provider, CredentialMode credentialMode, String name, String workspaceId)
      implements Command {
    public Create {
      require(provider != null && credentialMode != null);
      require(
          (provider == Provider.OPENAI && credentialMode == CredentialMode.OPENAI_API_KEY)
              || (provider == Provider.ANTHROPIC
                  && credentialMode == CredentialMode.ANTHROPIC_API_KEY)
              || (provider == Provider.GOOGLE_GEMINI
                  && credentialMode == CredentialMode.GEMINI_API_KEY));
      name = normalizeName(name);
      require(
          workspaceId == null
              || (provider == Provider.ANTHROPIC
                  && workspaceId.matches("wrkspc_[A-Za-z0-9]{1,128}")));
    }

    public Operation operation() {
      return Operation.CREATE;
    }

    public UUID target() {
      return null;
    }

    @Override
    public String toString() {
      return "Create[REDACTED]";
    }
  }

  public record Rename(UUID target, long expectedRevision, String name) implements Command {
    public Rename {
      uuid(target);
      revision(expectedRevision);
      name = normalizeName(name);
    }

    public Operation operation() {
      return Operation.RENAME;
    }

    @Override
    public String toString() {
      return "Rename[REDACTED]";
    }
  }

  public record Rotate(UUID target, long expectedRevision) implements Command {
    public Rotate {
      uuid(target);
      revision(expectedRevision);
    }

    public Operation operation() {
      return Operation.ROTATE;
    }

    @Override
    public String toString() {
      return "Rotate[REDACTED]";
    }
  }

  public record RevisionCommand(Operation operation, UUID target, long expectedRevision)
      implements Command {
    public RevisionCommand {
      require(operation == Operation.DISABLE || operation == Operation.ENABLE);
      uuid(target);
      revision(expectedRevision);
    }

    @Override
    public String toString() {
      return "RevisionCommand[REDACTED]";
    }
  }

  public record CredentialCommand(
      Operation operation, UUID target, long expectedRevision, long expectedCredentialRevision)
      implements Command {
    public CredentialCommand {
      require(operation == Operation.ACCESS_CHECK || operation == Operation.MODEL_DISCOVERY);
      uuid(target);
      revision(expectedRevision);
      revision(expectedCredentialRevision);
    }

    @Override
    public String toString() {
      return "CredentialCommand[REDACTED]";
    }
  }

  public record Metadata(String digestVersion, String keyVersion, String digest) {
    public Metadata {
      if (!DIGEST_VERSION.equals(digestVersion) || !validVersion(keyVersion))
        throw fail(Code.INVALID_METADATA);
      byte[] decoded = null;
      try {
        if (digest == null || digest.length() != 44) throw fail(Code.INVALID_METADATA);
        decoded = Base64.getDecoder().decode(digest);
        if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(digest))
          throw fail(Code.INVALID_METADATA);
      } catch (IllegalArgumentException e) {
        throw fail(Code.INVALID_METADATA);
      } finally {
        clear(decoded);
      }
    }

    @Override
    public String toString() {
      return "Metadata[REDACTED]";
    }
  }

  public CommandBoundary(Keys keys) {
    if (keys == null) throw fail(Code.KEY_UNAVAILABLE);
    this.keys = keys;
  }

  public Metadata prepareCurrent(Scope scope, Command command, char[] borrowedSecret) {
    validate(scope, command, borrowedSecret);
    String version;
    try {
      version = keys.currentVersion();
    } catch (RuntimeException e) {
      throw fail(Code.KEY_UNAVAILABLE);
    }
    if (!validVersion(version)) throw fail(Code.KEY_UNAVAILABLE);
    return prepareVersion(scope, command, borrowedSecret, version);
  }

  public Metadata prepareVersion(
      Scope scope, Command command, char[] borrowedSecret, String keyVersion) {
    validate(scope, command, borrowedSecret);
    if (!validVersion(keyVersion)) throw fail(Code.INVALID_METADATA);
    Mac mac;
    byte[] encoded = null;
    try {
      Key key = keys.key(keyVersion);
      if (key == null) throw fail(Code.KEY_UNAVAILABLE);
      encoded = key.getEncoded();
      if (encoded != null && encoded.length < 32) throw fail(Code.KEY_UNAVAILABLE);
      mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
    } catch (Exception e) {
      throw fail(Code.KEY_UNAVAILABLE);
    } finally {
      clear(encoded);
    }
    byte[] digest = null;
    try {
      string(mac, DIGEST_VERSION);
      string(mac, keyVersion);
      string(mac, scope.organization().toString());
      string(mac, scope.actor().toString());
      string(mac, command.operation().name());
      string(mac, command.target() == null ? null : command.target().toString());
      if (command instanceof Create c) {
        string(mac, c.provider().name());
        string(mac, c.credentialMode().name());
        string(mac, c.name());
        mac.update((byte) 2);
        length(mac, c.provider() == Provider.ANTHROPIC ? 1 : 0);
        if (c.provider() == Provider.ANTHROPIC) {
          string(mac, "workspaceId");
          string(mac, c.workspaceId());
        }
        secret(mac, borrowedSecret);
      } else if (command instanceof Rename c) {
        string(mac, Long.toString(c.expectedRevision()));
        string(mac, c.name());
      } else if (command instanceof Rotate c) {
        string(mac, Long.toString(c.expectedRevision()));
        secret(mac, borrowedSecret);
      } else if (command instanceof RevisionCommand c) {
        string(mac, Long.toString(c.expectedRevision()));
      } else if (command instanceof CredentialCommand c) {
        string(mac, Long.toString(c.expectedRevision()));
        string(mac, Long.toString(c.expectedCredentialRevision()));
      }
      digest = mac.doFinal();
      return new Metadata(DIGEST_VERSION, keyVersion, Base64.getEncoder().encodeToString(digest));
    } catch (RuntimeException e) {
      throw fail(Code.INTERNAL_FAILURE);
    } finally {
      clear(digest);
    }
  }

  public Comparison verify(Scope scope, Command command, char[] borrowedSecret, Metadata stored) {
    if (stored == null) throw fail(Code.INVALID_METADATA);
    return compare(prepareVersion(scope, command, borrowedSecret, stored.keyVersion()), stored);
  }

  public static Comparison compare(Metadata prepared, Metadata stored) {
    if (prepared == null
        || stored == null
        || !prepared.digestVersion().equals(stored.digestVersion())
        || !prepared.keyVersion().equals(stored.keyVersion())) throw fail(Code.INVALID_METADATA);
    byte[] left = null;
    byte[] right = null;
    try {
      left = Base64.getDecoder().decode(prepared.digest());
      right = Base64.getDecoder().decode(stored.digest());
      return MessageDigest.isEqual(left, right) ? Comparison.MATCH : Comparison.MISMATCH;
    } finally {
      clear(left);
      clear(right);
    }
  }

  private static void validate(Scope scope, Command command, char[] secret) {
    require(scope != null && command != null);
    if (command.operation() == Operation.CREATE || command.operation() == Operation.ROTATE) {
      require(secret != null && secret.length >= 1 && secret.length <= 4096);
      for (char c : secret) require(c >= 0x21 && c <= 0x7e);
    } else require(secret == null);
  }

  private static void uuid(UUID value) {
    require(value != null && value.variant() == 2 && value.version() >= 1 && value.version() <= 5);
  }

  private static void revision(long value) {
    require(value >= 0);
  }

  private static boolean validVersion(String value) {
    return value != null && value.matches("[A-Za-z0-9_./:@-]{1,256}");
  }

  private static String normalizeName(String value) {
    require(value != null);
    int start = 0, end = value.length();
    while (start < end && space(value.codePointAt(start)))
      start += Character.charCount(value.codePointAt(start));
    while (end > start && space(value.codePointBefore(end)))
      end -= Character.charCount(value.codePointBefore(end));
    String name = value.substring(start, end);
    require(
        name.codePointCount(0, name.length()) >= 1 && name.codePointCount(0, name.length()) <= 100);
    for (int i = 0; i < name.length(); ) {
      int cp = name.codePointAt(i);
      require(
          !Character.isISOControl(cp)
              && cp != 0x2028
              && cp != 0x2029
              && (cp < 0xd800 || cp > 0xdfff));
      i += Character.charCount(cp);
    }
    return name;
  }

  private static boolean space(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xfeff;
  }

  private static void string(Mac mac, String value) {
    if (value == null) {
      mac.update((byte) 0);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    try {
      mac.update((byte) 1);
      length(mac, bytes.length);
      mac.update(bytes);
    } finally {
      clear(bytes);
    }
  }

  private static void secret(Mac mac, char[] value) {
    mac.update((byte) 1);
    length(mac, value.length);
    for (char c : value) mac.update((byte) c);
  }

  private static void length(Mac mac, int value) {
    mac.update((byte) (value >>> 24));
    mac.update((byte) (value >>> 16));
    mac.update((byte) (value >>> 8));
    mac.update((byte) value);
  }

  private static void require(boolean valid) {
    if (!valid) throw fail(Code.INVALID_COMMAND);
  }

  private static Failure fail(Code code) {
    return new Failure(code);
  }

  private static void clear(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }
}

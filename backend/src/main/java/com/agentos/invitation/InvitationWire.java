package com.agentos.invitation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class InvitationWire {
  private InvitationWire() {}

  static UUID uuid(String s) {
    if (s == null
        || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      bad();
    return UUID.fromString(s);
  }

  static void bad() {
    throw new IllegalArgumentException("Invalid request");
  }

  static JsonNode body(ObjectMapper mapper, String raw, String... fields) {
    if (raw == null || raw.length() > 8192 || raw.getBytes(StandardCharsets.UTF_8).length > 8192)
      bad();
    try (JsonParser p = mapper.getFactory().createParser(raw)) {
      p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode n = mapper.readTree(p);
      if (n == null || !n.isObject() || n.size() != fields.length || p.nextToken() != null) bad();
      Set<String> allowed = Set.of(fields);
      n.fieldNames()
          .forEachRemaining(
              f -> {
                if (!allowed.contains(f)) bad();
              });
      return n;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Invalid request");
    }
  }

  static String text(JsonNode n, String field) {
    if (n.get(field) == null || !n.get(field).isTextual()) bad();
    return n.get(field).textValue();
  }

  static void ack(JsonNode n) {
    if (!n.path("acknowledgement").isBoolean() || !n.path("acknowledgement").booleanValue()) bad();
  }

  static int revision(JsonNode n) {
    JsonNode v = n.path("expectedRevision");
    if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 1) bad();
    return v.intValue();
  }

  static String email(String s) {
    if (s == null) bad();
    s = s.strip();
    if (s.chars().anyMatch(c -> c > 127)) bad();
    s = s.toLowerCase(Locale.ROOT);
    int at = s.indexOf('@');
    if (s.length() > 254 || at < 1 || at > 64 || at != s.lastIndexOf('@')) bad();
    String local = s.substring(0, at);
    if (!local.matches("[a-z0-9!#$%&'*+/=?^_`{|}~.-]+")
        || local.startsWith(".")
        || local.endsWith(".")
        || local.contains("..")) bad();
    String domain = s.substring(at + 1);
    if (!domain.contains(".")) bad();
    for (String label : domain.split("\\.", -1))
      if (label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) bad();
    return s;
  }

  static String name(String s) {
    if (s == null) bad();
    s = s.strip();
    if (s.codePointCount(0, s.length()) < 1
        || s.codePointCount(0, s.length()) > 255
        || s.codePoints().anyMatch(c -> c < 32 || c == 127 || c >= 0xD800 && c <= 0xDFFF)) bad();
    return s;
  }

  static String password(String s) {
    if (s == null
        || s.codePointCount(0, s.length()) < 14
        || s.getBytes(StandardCharsets.UTF_8).length > 72
        || s.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) bad();
    return s;
  }

  static String digest(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}

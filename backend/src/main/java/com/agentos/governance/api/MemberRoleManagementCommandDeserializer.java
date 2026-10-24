package com.agentos.governance.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class MemberRoleManagementCommandDeserializer
    extends StdDeserializer<MemberRoleManagementResponse.Command> {
  public MemberRoleManagementCommandDeserializer() {
    super(MemberRoleManagementResponse.Command.class);
  }

  public static UUID uuid(String value) {
    if (value == null || !value.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
      throw new IllegalArgumentException("Invalid identity");
    return UUID.fromString(value);
  }

  @Override
  public MemberRoleManagementResponse.Command deserialize(JsonParser p, DeserializationContext c)
      throws IOException {
    p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode n = p.getCodec().readTree(p);
    try {
      Set<String> fields =
          Set.of("expectedRevision", "expectedCurrentRole", "newRole", "reason", "acknowledgement");
      if (!n.isObject() || n.size() != fields.size()) throw new IllegalArgumentException();
      var names = n.fieldNames();
      while (names.hasNext())
        if (!fields.contains(names.next())) throw new IllegalArgumentException();
      for (String f : fields)
        if (n.get(f) == null || n.get(f).isNull()) throw new IllegalArgumentException();
      int revision = integer(n.get("expectedRevision"), 0, Integer.MAX_VALUE);
      if (!n.get("acknowledgement").isBoolean() || !n.get("acknowledgement").booleanValue())
        throw new IllegalArgumentException();
      String reason = text(n, "reason").strip();
      if (reason.codePointCount(0, reason.length()) < 1
          || reason.codePointCount(0, reason.length()) > 1000
          || reason
              .codePoints()
              .anyMatch(
                  x ->
                      (x < 32 && x != 9 && x != 10 && x != 13)
                          || x == 127
                          || (x >= 0xD800 && x <= 0xDFFF))) throw new IllegalArgumentException();
      String before = role(text(n, "expectedCurrentRole"));
      String after = role(text(n, "newRole"));
      return new MemberRoleManagementResponse.Command(revision, before, after, reason, true);
    } catch (IllegalArgumentException ex) {
      throw JsonMappingException.from(p, "Invalid role management command");
    }
  }

  public static String role(String value) {
    if (!"ADMIN".equals(value) && !"MEMBER".equals(value))
      throw new IllegalArgumentException("Invalid role");
    return value;
  }

  private static String text(JsonNode n, String key) {
    if (!n.get(key).isTextual()) throw new IllegalArgumentException();
    return n.get(key).textValue();
  }

  private static int integer(JsonNode n, int min, int max) {
    if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < min || n.intValue() > max)
      throw new IllegalArgumentException();
    return n.intValue();
  }
}

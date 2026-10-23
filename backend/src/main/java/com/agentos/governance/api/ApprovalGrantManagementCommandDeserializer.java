package com.agentos.governance.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class ApprovalGrantManagementCommandDeserializer
    extends StdDeserializer<ApprovalGrantManagementResponse.Command> {
  public ApprovalGrantManagementCommandDeserializer() {
    super(ApprovalGrantManagementResponse.Command.class);
  }

  public static UUID uuid(String value) {
    if (value == null || !value.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
      throw new IllegalArgumentException("Invalid identity");
    return UUID.fromString(value);
  }

  @Override
  public ApprovalGrantManagementResponse.Command deserialize(JsonParser p, DeserializationContext c)
      throws IOException {
    p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode n = p.getCodec().readTree(p);
    try {
      boolean revoke = n.has("grantFingerprint");
      Set<String> fields =
          revoke
              ? Set.of("expectedRevision", "grantFingerprint", "reason", "acknowledgement")
              : Set.of(
                  "expectedRevision",
                  "teamId",
                  "actionType",
                  "riskLevel",
                  "durationHours",
                  "reason",
                  "acknowledgement");
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
              .anyMatch(x -> (x < 32 && x != 9 && x != 10 && x != 13) || x == 127))
        throw new IllegalArgumentException();
      if (revoke) {
        String hash = text(n, "grantFingerprint");
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
        return new ApprovalGrantManagementResponse.Command(
            revision, null, null, null, null, hash, reason, true);
      }
      String action = text(n, "actionType"), risk = text(n, "riskLevel");
      if (!action.equals("PUBLISH_PREVIEW") || !Set.of("LOW", "MEDIUM", "HIGH").contains(risk))
        throw new IllegalArgumentException();
      return new ApprovalGrantManagementResponse.Command(
          revision,
          uuid(text(n, "teamId")),
          action,
          risk,
          integer(n.get("durationHours"), 1, 168),
          null,
          reason,
          true);
    } catch (IllegalArgumentException ex) {
      throw JsonMappingException.from(p, "Invalid grant management command");
    }
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

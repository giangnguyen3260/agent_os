package com.agentos.governance.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;

/** Reject coercions that would silently change the snapshot acknowledged by a human. */
public final class ApprovalDecisionCommandDeserializer
    extends StdDeserializer<ApprovalDetailResponse.Command> {
  public ApprovalDecisionCommandDeserializer() {
    super(ApprovalDetailResponse.Command.class);
  }

  @Override
  public ApprovalDetailResponse.Command deserialize(
      JsonParser parser, DeserializationContext context) throws IOException {
    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode node = parser.getCodec().readTree(parser);
    if (!node.isObject()) throw JsonMappingException.from(parser, "Invalid decision");
    var names = node.fieldNames();
    Set<String> allowed = Set.of("snapshotVersion", "snapshotSha256", "acknowledgement", "reason");
    while (names.hasNext())
      if (!allowed.contains(names.next()))
        throw JsonMappingException.from(parser, "Invalid decision");
    JsonNode version = node.get("snapshotVersion"),
        hash = node.get("snapshotSha256"),
        ack = node.get("acknowledgement"),
        reason = node.get("reason");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToLong()
        || hash == null
        || !hash.isTextual()
        || ack == null
        || !ack.isBoolean()
        || reason != null && !reason.isNull() && !reason.isTextual())
      throw JsonMappingException.from(parser, "Invalid decision");
    return new ApprovalDetailResponse.Command(
        version.longValue(),
        hash.textValue(),
        ack.booleanValue(),
        reason == null || reason.isNull() ? null : reason.textValue());
  }
}

package com.agentos.governance.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class ApprovalInformationCommandDeserializer
    extends StdDeserializer<ApprovalInformationResponse.Command> {
  public ApprovalInformationCommandDeserializer() {
    super(ApprovalInformationResponse.Command.class);
  }

  @Override
  public ApprovalInformationResponse.Command deserialize(
      JsonParser parser, DeserializationContext context) throws IOException {
    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode n = parser.getCodec().readTree(parser);
    if (!n.isObject()) throw JsonMappingException.from(parser, "Invalid information command");
    boolean reply = n.has("answer") || n.has("questionId") || n.has("evidence");
    Set<String> allowed =
        reply
            ? Set.of("revision", "questionId", "answer", "evidence")
            : Set.of("revision", "question");
    var fields = n.fieldNames();
    while (fields.hasNext())
      if (!allowed.contains(fields.next()))
        throw JsonMappingException.from(parser, "Invalid information command");
    var revision = n.get("revision");
    if (revision == null
        || !revision.isIntegralNumber()
        || !revision.canConvertToLong()
        || revision.longValue() < 0
        || revision.longValue() > 9007199254740991L)
      throw JsonMappingException.from(parser, "Invalid revision");
    var text = n.get(reply ? "answer" : "question");
    if (text == null || !text.isTextual()) throw JsonMappingException.from(parser, "Invalid text");
    UUID questionId = null;
    if (reply) {
      var q = n.get("questionId");
      try {
        if (q == null
            || !q.isTextual()
            || !q.textValue().matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))
          throw new IllegalArgumentException();
        questionId = UUID.fromString(q.textValue());
      } catch (IllegalArgumentException ex) {
        throw JsonMappingException.from(parser, "Invalid question identity");
      }
    }
    var evidence = n.get("evidence");
    if (evidence != null && !evidence.isNull() && !evidence.isTextual())
      throw JsonMappingException.from(parser, "Invalid evidence");
    return new ApprovalInformationResponse.Command(
        revision.longValue(),
        reply ? null : text.textValue(),
        questionId,
        reply ? text.textValue() : null,
        evidence == null || evidence.isNull() ? null : evidence.textValue(),
        reply);
  }
}

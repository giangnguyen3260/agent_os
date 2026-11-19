package com.agentos.orchestration.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class OpenAiStartCommandDeserializer
    extends StdDeserializer<OpenAiExecutionController.Command> {
  public OpenAiStartCommandDeserializer() {
    super(OpenAiExecutionController.Command.class);
  }

  @Override
  public OpenAiExecutionController.Command deserialize(
      JsonParser parser, DeserializationContext context) throws IOException {
    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode body = parser.getCodec().readTree(parser);
    try {
      Set<String> fields = Set.of("expectedRevision", "idempotencyKey", "fingerprint");
      if (!body.isObject() || body.size() != 3) throw new IllegalArgumentException();
      var names = body.fieldNames();
      while (names.hasNext())
        if (!fields.contains(names.next())) throw new IllegalArgumentException();
      for (String field : fields)
        if (!body.path(field).isTextual()) throw new IllegalArgumentException();
      String revision = body.path("expectedRevision").textValue(),
          key = body.path("idempotencyKey").textValue(),
          fingerprint = body.path("fingerprint").textValue();
      if (!revision.matches("0|[1-9][0-9]{0,18}")
          || Long.parseLong(revision) == Long.MAX_VALUE
          || !key.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
          || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      return new OpenAiExecutionController.Command(revision, UUID.fromString(key), fingerprint);
    } catch (IllegalArgumentException failure) {
      throw JsonMappingException.from(parser, "Invalid execution Start command");
    }
  }
}

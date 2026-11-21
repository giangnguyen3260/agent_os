package com.agentos.mission.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class MissionPlanAcceptanceCommandDeserializer
    extends StdDeserializer<MissionPlanAcceptanceController.Body> {
  public MissionPlanAcceptanceCommandDeserializer() {
    super(MissionPlanAcceptanceController.Body.class);
  }

  @Override
  public MissionPlanAcceptanceController.Body deserialize(
      JsonParser parser, DeserializationContext context) throws IOException {
    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    JsonNode body = parser.getCodec().readTree(parser);
    try {
      var fields = Set.of("idempotencyKey", "expectedFingerprint");
      if (!body.isObject() || body.size() != 2) throw new IllegalArgumentException();
      var names = body.fieldNames();
      while (names.hasNext())
        if (!fields.contains(names.next())) throw new IllegalArgumentException();
      for (String name : fields)
        if (!body.path(name).isTextual()) throw new IllegalArgumentException();
      String key = body.path("idempotencyKey").textValue(),
          fingerprint = body.path("expectedFingerprint").textValue();
      if (!key.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
          || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      if (parser.nextToken() != null) throw new IllegalArgumentException();
      return new MissionPlanAcceptanceController.Body(UUID.fromString(key), fingerprint);
    } catch (IllegalArgumentException failure) {
      throw JsonMappingException.from(parser, "Invalid plan acceptance command");
    }
  }
}

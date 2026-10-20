package com.agentos.agent.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Reject scalar coercion: configuration fields are text, never numbers or booleans. */
public class StrictTextDeserializer extends JsonDeserializer<String> {
  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (!parser.hasToken(JsonToken.VALUE_STRING))
      return (String) context.handleUnexpectedToken(String.class, parser);
    return parser.getText();
  }
}

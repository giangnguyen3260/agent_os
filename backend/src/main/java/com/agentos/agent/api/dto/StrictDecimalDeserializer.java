package com.agentos.agent.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/** Requires a JSON number without scalar coercion. */
public class StrictDecimalDeserializer extends JsonDeserializer<BigDecimal> {
  @Override
  public BigDecimal deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    if (!(parser.hasToken(JsonToken.VALUE_NUMBER_INT)
        || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)))
      return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
    return parser.getDecimalValue();
  }
}

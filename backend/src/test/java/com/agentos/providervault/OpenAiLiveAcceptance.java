package com.agentos.providervault;

import java.util.Map;

/** Explicit, bounded operator settings. Never includes key bytes in diagnostics. */
record OpenAiLiveAcceptance(
    String keyFile, String model, long inputRate, long outputRate, long maximumMicrousd) {
  static OpenAiLiveAcceptance from(Map<String, String> env) {
    if (!"true".equals(env.get("AGENTOS_OPENAI_EXECUTION_LIVE")))
      throw new IllegalArgumentException("Real OpenAI execution requires explicit opt-in");
    String key = required(env, "AGENTOS_OPENAI_EXECUTION_KEY_FILE");
    if (!java.nio.file.Path.of(key).isAbsolute())
      throw new IllegalArgumentException(
          "Real OpenAI execution requires an absolute key-file path");
    String model = required(env, "AGENTOS_OPENAI_EXECUTION_MODEL");
    if (!model.matches("[A-Za-z0-9._:-]{1,256}"))
      throw new IllegalArgumentException("Invalid real OpenAI model setting");
    long input = positive(env, "AGENTOS_OPENAI_EXECUTION_INPUT_RATE"),
        output = positive(env, "AGENTOS_OPENAI_EXECUTION_OUTPUT_RATE"),
        cap = positive(env, "AGENTOS_OPENAI_EXECUTION_MAXIMUM_MICROUSD");
    if (cap > 100000)
      throw new IllegalArgumentException("Real OpenAI acceptance cap exceeds 100000 micro-USD");
    try {
      long reservation =
          Math.floorDiv(
              Math.addExact(
                  Math.addExact(
                      Math.multiplyExact(17408L, input), Math.multiplyExact(512L, output)),
                  999999L),
              1000000L);
      if (reservation > cap)
        throw new IllegalArgumentException("Real OpenAI acceptance reservation exceeds the cap");
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("Real OpenAI acceptance rate overflow");
    }
    return new OpenAiLiveAcceptance(key, model, input, output, cap);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing real OpenAI acceptance setting: " + name);
    return value;
  }

  private static long positive(Map<String, String> env, String name) {
    String value = required(env, name);
    if (!value.matches("[1-9][0-9]{0,17}"))
      throw new IllegalArgumentException("Invalid real OpenAI acceptance numeric setting: " + name);
    return Long.parseLong(value);
  }

  @Override
  public String toString() {
    return "OpenAiLiveAcceptance[redacted]";
  }
}

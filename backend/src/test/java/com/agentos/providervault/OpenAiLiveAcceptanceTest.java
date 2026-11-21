package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiLiveAcceptanceTest {
  private Map<String, String> settings() {
    return new HashMap<>(
        Map.of(
            "AGENTOS_OPENAI_EXECUTION_LIVE", "true",
            "AGENTOS_OPENAI_EXECUTION_KEY_FILE", "/private/operator-key",
            "AGENTOS_OPENAI_EXECUTION_MODEL", "operator-model",
            "AGENTOS_OPENAI_EXECUTION_INPUT_RATE", "1000000",
            "AGENTOS_OPENAI_EXECUTION_OUTPUT_RATE", "1000000",
            "AGENTOS_OPENAI_EXECUTION_MAXIMUM_MICROUSD", "17920"));
  }

  @Test
  void exactReservationIsAcceptedAndDiagnosticsAreRedacted() {
    var value = OpenAiLiveAcceptance.from(settings());
    assertThat(value.maximumMicrousd()).isEqualTo(17920);
    assertThat(value.toString()).isEqualTo("OpenAiLiveAcceptance[redacted]");
  }

  @Test
  void requiresExplicitOptInAndEveryOperatorSetting() {
    for (String name : settings().keySet()) {
      var env = settings();
      env.remove(name);
      assertThatThrownBy(() -> OpenAiLiveAcceptance.from(env))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var env = settings();
    env.put("AGENTOS_OPENAI_EXECUTION_LIVE", "TRUE");
    assertThatThrownBy(() -> OpenAiLiveAcceptance.from(env))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnderfundedExcessiveAndNoncanonicalLimits() {
    for (String cap : new String[] {"17919", "100001", "0", "-1", "01", "+20000", "1e5"}) {
      var env = settings();
      env.put("AGENTOS_OPENAI_EXECUTION_MAXIMUM_MICROUSD", cap);
      assertThatThrownBy(() -> OpenAiLiveAcceptance.from(env))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsInvalidRates() {
    for (String name :
        new String[] {
          "AGENTOS_OPENAI_EXECUTION_INPUT_RATE", "AGENTOS_OPENAI_EXECUTION_OUTPUT_RATE"
        }) {
      for (String value : new String[] {"0", "-1", "1.5", " 1", "01", "9999999999999999999"}) {
        var env = settings();
        env.put(name, value);
        assertThatThrownBy(() -> OpenAiLiveAcceptance.from(env))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void roundsReservationUpAndRejectsArithmeticOverflow() {
    var env = settings();
    env.put("AGENTOS_OPENAI_EXECUTION_INPUT_RATE", "1");
    env.put("AGENTOS_OPENAI_EXECUTION_OUTPUT_RATE", "1");
    env.put("AGENTOS_OPENAI_EXECUTION_MAXIMUM_MICROUSD", "1");
    assertThat(OpenAiLiveAcceptance.from(env).maximumMicrousd()).isEqualTo(1);
    env.put("AGENTOS_OPENAI_EXECUTION_INPUT_RATE", "999999999999999999");
    assertThatThrownBy(() -> OpenAiLiveAcceptance.from(env))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRelativeKeysAndInvalidModelsWithoutEchoingInput() {
    var env = settings();
    env.put("AGENTOS_OPENAI_EXECUTION_KEY_FILE", "private-key");
    assertThatThrownBy(() -> OpenAiLiveAcceptance.from(env))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("private-key");
    var invalid = settings();
    invalid.put("AGENTOS_OPENAI_EXECUTION_MODEL", "private-invalid-model/route");
    assertThatThrownBy(() -> OpenAiLiveAcceptance.from(invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("private-invalid-model");
  }
}

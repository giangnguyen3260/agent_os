package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercommands.CommandBoundary.Operation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderDisableBodyTest {
  private static final UUID TARGET = UUID.randomUUID();
  private static final String BODY = "{\"expectedRevision\":\"1\"}";

  @Test
  void acceptsOnlyTheRevisionWithAuthorizedTargetAndDisableOperation() {
    for (String revision : new String[] {"0", "1", "9223372036854775807"}) {
      var command =
          ProviderDisableBody.read(TARGET, input(BODY.replace("\"1\"", "\"" + revision + "\"")));
      assertThat(command.operation()).isEqualTo(Operation.DISABLE);
      assertThat(command.target()).isEqualTo(TARGET);
      assertThat(command.expectedRevision()).isEqualTo(Long.parseLong(revision));
    }
    assertThat(ProviderDisableBody.read(TARGET, input("\ufeff" + BODY)).expectedRevision())
        .isEqualTo(1);
  }

  @Test
  void malformedShapeAndRevisionFailWithFixedError() {
    for (String body :
        new String[] {
          "",
          "{}",
          "[]",
          "null",
          BODY + "{}",
          BODY + "null",
          BODY + " private-marker",
          "{\"expectedRevision\":1}",
          "{\"expectedRevision\":null}",
          "{\"expectedRevision\":\"1\",\"expectedRevi\\u0073ion\":\"2\"}",
          "{\"expectedRevision\":\"1\",\"name\":\"private-marker\"}",
          "{\"expectedRevision\":\"1\",\"operation\":\"ENABLE\"}"
        }) invalid(input(body));
    for (String revision :
        new String[] {"", "-1", "+1", "01", "1.0", "1e0", " 1", "9223372036854775808"}) {
      invalid(input(BODY.replace("\"1\"", "\"" + revision + "\"")));
    }
    assertThatThrownBy(() -> ProviderDisableBody.read(new UUID(0, 0), input(BODY)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_INPUT")
        .hasNoCause();
  }

  @Test
  void boundedUtf8ReaderRejectsOverflowAndOtherEncodingsWithoutDraining() {
    assertThat(
            ProviderDisableBody.read(TARGET, input(BODY + " ".repeat(8192 - BODY.length())))
                .expectedRevision())
        .isEqualTo(1);
    var oversized = input(BODY + " ".repeat(9000));
    int length = oversized.available();
    invalid(oversized);
    assertThat(oversized.available()).isEqualTo(length - 8193);
    invalid(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_16LE)));
    invalid(new ByteArrayInputStream(new byte[] {(byte) 0xc0, (byte) 0xaf}));
  }

  private static ByteArrayInputStream input(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }

  private static void invalid(ByteArrayInputStream input) {
    assertThatThrownBy(() -> ProviderDisableBody.read(TARGET, input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_INPUT")
        .hasNoCause();
  }
}

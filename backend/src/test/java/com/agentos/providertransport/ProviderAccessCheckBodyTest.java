package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercommands.CommandBoundary.Operation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderAccessCheckBodyTest {
  private static final String VALID =
      "{\"expectedCredentialRevision\":\"9007199254740993\",\"expectedRevision\":\"9223372036854775807\"}";
  private final UUID target = UUID.randomUUID();

  @Test
  void retainsBothExactRevisionsAndBindsTheOperationAndRouteTarget() {
    var command = ProviderAccessCheckBody.read(target, bytes(VALID));
    assertThat(command.operation()).isEqualTo(Operation.ACCESS_CHECK);
    assertThat(command.target()).isEqualTo(target);
    assertThat(command.expectedRevision()).isEqualTo(Long.MAX_VALUE);
    assertThat(command.expectedCredentialRevision()).isEqualTo(9007199254740993L);
    assertThat(
            ProviderAccessCheckBody.read(
                    target,
                    bytes(
                        "\ufeff{\"expectedRevision\":\"0\",\"expectedCredentialRevision\":\"0\"}"))
                .expectedRevision())
        .isZero();
  }

  @Test
  void rejectsNoncanonicalValuesAndNonexactObjectsWithTheFixedError() {
    for (String body :
        new String[] {
          "{}",
          "{\"expectedRevision\":\"1\"}",
          "{\"expectedCredentialRevision\":\"1\"}",
          VALID.replace("\"9007199254740993\"", "1"),
          VALID.replace("\"9007199254740993\"", "null"),
          VALID.replace("9007199254740993", "01"),
          VALID.replace("9007199254740993", "-1"),
          VALID.replace("9223372036854775807", "9223372036854775808"),
          VALID.replace("\"expectedRevision\":", "\"expectedCredentialRevision\":"),
          VALID.replace("{", "{\"operation\":\"MODEL_DISCOVERY\","),
          VALID.replace("{", "{\"apiKey\":\"unexpected\","),
          VALID + " {}"
        }) {
      assertBad(bytes(body));
    }
    assertBad(new ByteArrayInputStream(VALID.getBytes(StandardCharsets.UTF_16LE)));
    assertBad(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28}));
  }

  @Test
  void enforcesTheByteLimitAndLeavesTheStreamOpen() {
    String padded = VALID + " ".repeat(8192 - VALID.length());
    var input =
        new ByteArrayInputStream(padded.getBytes(StandardCharsets.UTF_8)) {
          @Override
          public void close() {
            throw new AssertionError("The stream belongs to the caller");
          }
        };
    assertThat(ProviderAccessCheckBody.read(target, input).target()).isEqualTo(target);
    assertBad(bytes(padded + " "));
  }

  private void assertBad(ByteArrayInputStream input) {
    assertThatThrownBy(() -> ProviderAccessCheckBody.read(target, input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_INPUT")
        .hasNoCause();
  }

  private static ByteArrayInputStream bytes(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }
}

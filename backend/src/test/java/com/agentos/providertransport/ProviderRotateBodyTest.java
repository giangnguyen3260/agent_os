package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderRotateBodyTest {
  private final UUID target = UUID.randomUUID();

  @Test
  void preservesTheKeyAndRevisionAndClearsOwnedBuffers() {
    var input =
        utf8(
            "\ufeff{\"apiKey\":\"Example-~\\u0021\",\"expectedRevision\":\"9223372036854775807\"}");
    var body = ProviderRotateBody.consume(target, input);
    byte[] borrowed;
    try (body) {
      assertThat(input).containsOnly((byte) 0);
      assertThat(body.command().target()).isEqualTo(target);
      assertThat(body.command().expectedRevision()).isEqualTo(Long.MAX_VALUE);
      borrowed = body.credential();
      assertThat(borrowed).isEqualTo(utf8("Example-~!"));
      assertThat(body.toString()).doesNotContain("Example");
    }
    assertThat(borrowed).containsOnly((byte) 0);
    assertThatThrownBy(body::credential).hasMessage("INVALID_INPUT");
    assertThatThrownBy(body::command).hasMessage("INVALID_INPUT");
  }

  @Test
  void rejectsNonExactBodiesAndClearsInputWithoutEchoingValues() {
    for (String input :
        new String[] {
          "{}",
          "{\"expectedRevision\":\"1\"}",
          "{\"apiKey\":\"fixture\"}",
          "{\"expectedRevision\":1,\"apiKey\":\"fixture\"}",
          "{\"expectedRevision\":\"01\",\"apiKey\":\"fixture\"}",
          "{\"expectedRevision\":\"9223372036854775808\",\"apiKey\":\"fixture\"}",
          "{\"expectedRevision\":\"1\",\"apiKey\":null}",
          "{\"expectedRevision\":\"1\",\"apiKey\":\"\"}",
          "{\"expectedRevision\":\"1\",\"apiKey\":\"key with space\"}",
          "{\"expectedRevision\":\"1\",\"apiKey\":\"clé\"}",
          "{\"expectedRevision\":\"1\",\"apiKey\":\"fixture\",\"apiKey\":\"other\"}",
          "{\"expectedRevision\":\"1\",\"expectedRevision\":\"2\",\"apiKey\":\"fixture\"}",
          "{\"expectedRevision\":\"1\",\"apiKey\":\"fixture\",\"provider\":\"OPENAI\"}",
          valid("fixture") + "{}",
          valid("k".repeat(4097))
        }) {
      assertInvalidAndErased(utf8(input));
    }
    assertInvalidAndErased(valid("fixture").getBytes(StandardCharsets.UTF_16LE));
    assertInvalidAndErased(new byte[] {(byte) 0xc3, 0x28});
  }

  @Test
  void enforcesTheByteLimitWithoutClosingTheInputStream() {
    String json = valid("k".repeat(4096));
    String padded = json + " ".repeat(8192 - json.length());
    var input =
        new ByteArrayInputStream(utf8(padded)) {
          @Override
          public void close() {
            throw new AssertionError("The stream belongs to the caller");
          }
        };
    try (var body = ProviderRotateBody.read(target, input)) {
      assertThat(body.credential()).hasSize(4096);
    }
    assertThatThrownBy(
            () -> ProviderRotateBody.read(target, new ByteArrayInputStream(utf8(padded + " "))))
        .hasMessage("INVALID_INPUT");
  }

  private void assertInvalidAndErased(byte[] input) {
    assertThatThrownBy(() -> ProviderRotateBody.consume(target, input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_INPUT")
        .hasNoCause();
    assertThat(input).containsOnly((byte) 0);
  }

  private static String valid(String key) {
    return "{\"expectedRevision\":\"1\",\"apiKey\":\"" + key + "\"}";
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}

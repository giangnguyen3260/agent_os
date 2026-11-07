package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProviderCreateBodyTest {
  private static final String BODY =
      """
      {"provider":"OPENAI","credentialMode":"OPENAI_API_KEY","name":" Sample ",
       "options":{},"apiKey":"sentinel-key"}
      """;

  @Test
  void consumesBodyAndClearsBorrowedCredentialOnClose() {
    byte[] body = bytes(BODY);
    var parsed = ProviderCreateBody.consume(body);
    byte[] borrowed = parsed.credential();
    assertThat(parsed.command().name()).isEqualTo("Sample");
    assertThat(borrowed).isEqualTo(bytes("sentinel-key"));
    assertThat(body).containsOnly((byte) 0);
    assertThat(parsed.toString()).doesNotContain("sentinel");
    parsed.close();
    parsed.close();
    assertThat(borrowed).containsOnly((byte) 0);
    assertThatThrownBy(parsed::credential).hasMessage("INVALID_INPUT");
    assertThatThrownBy(parsed::command).hasMessage("INVALID_INPUT");
  }

  @Test
  void acceptsAllThreeExactProfiles() {
    try (var ignored = ProviderCreateBody.consume(bytes(BODY))) {
      assertThat(ignored.command().provider().name()).isEqualTo("OPENAI");
    }
    try (var parsed =
        ProviderCreateBody.consume(
            bytes(
                BODY.replace("OPENAI_API_KEY", "GEMINI_API_KEY")
                    .replace("OPENAI", "GOOGLE_GEMINI")))) {
      assertThat(parsed.command().provider().name()).isEqualTo("GOOGLE_GEMINI");
    }
    for (String workspace : new String[] {"null", "\"wrkspc_A123\""}) {
      try (var parsed =
          ProviderCreateBody.consume(
              bytes(
                  BODY.replace("OPENAI", "ANTHROPIC")
                      .replace(
                          "\"options\":{}", "\"options\":{\"workspaceId\":" + workspace + "}")))) {
        assertThat(parsed.command().provider().name()).isEqualTo("ANTHROPIC");
      }
    }
  }

  @Test
  void rejectsCoercionsUnknownFieldsDuplicatesAndTrailingValues() {
    for (String invalid :
        new String[] {
          BODY + "{}",
          BODY.replace("\"options\":{}", "\"options\":{\"extra\":null}"),
          BODY.replace("\"options\":{}", "\"options\":{\"workspaceId\":null}"),
          BODY.replace("\"name\":\" Sample \"", "\"name\":null"),
          BODY.replace("\"name\":\" Sample \"", "\"name\":123"),
          BODY.replace("\"apiKey\":\"sentinel-key\"", "\"apiKey\":false"),
          BODY.replace("\"apiKey\":\"sentinel-key\"", "\"apiKey\":\"x\",\"apiKey\":\"y\""),
          BODY.replace("\"apiKey\":", "\"extra\":null,\"apiKey\":"),
          BODY.replace("OPENAI_API_KEY", "GEMINI_API_KEY"),
          BODY.replace("OPENAI", "ANTHROPIC"),
          BODY.replace("\"options\":{},", ""),
          BODY.replace("sentinel-key", ""),
          BODY.replace("sentinel-key", " "),
          BODY.replace("sentinel-key", "é"),
          BODY.replace("sentinel-key", "\\n"),
          BODY.replace("sentinel-key", "x".repeat(4097)),
          "{}",
          "[]"
        }) {
      byte[] owned = bytes(invalid);
      assertThatThrownBy(() -> ProviderCreateBody.consume(owned))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_INPUT")
          .hasNoCause();
      assertThat(owned).containsOnly((byte) 0);
    }
  }

  @Test
  void boundsBytesAndRejectsMalformedUtf8() {
    for (byte[] body : new byte[][] {new byte[8193], {(byte) 0xc3, (byte) 0x28}}) {
      assertThatThrownBy(() -> ProviderCreateBody.consume(body)).hasMessage("INVALID_INPUT");
      assertThat(body).containsOnly((byte) 0);
    }
    assertThatThrownBy(() -> ProviderCreateBody.consume(null)).hasMessage("INVALID_INPUT");
  }

  @Test
  void maximumVisibleAsciiCredentialIsPreserved() {
    try (var parsed =
        ProviderCreateBody.consume(bytes(BODY.replace("sentinel-key", "!".repeat(4096))))) {
      assertThat(parsed.credential()).hasSize(4096).containsOnly((byte) '!');
    }
  }

  @Test
  void neverAutodetectsUtf16OrUtf32EvenWhenTheirBytesAreValidUtf8() {
    String[] encodings = {"UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"};
    byte[][] marks = {
      {(byte) 0xff, (byte) 0xfe},
      {(byte) 0xfe, (byte) 0xff},
      {(byte) 0xff, (byte) 0xfe, 0, 0},
      {0, 0, (byte) 0xfe, (byte) 0xff}
    };
    for (int index = 0; index < encodings.length; index++) {
      for (boolean marked : new boolean[] {false, true}) {
        byte[] encoded = BODY.getBytes(Charset.forName(encodings[index]));
        byte[] body = marked ? prefixed(marks[index], encoded) : encoded;
        assertThatThrownBy(() -> ProviderCreateBody.consume(body)).hasMessage("INVALID_INPUT");
        assertThat(body).containsOnly((byte) 0);
      }
    }
  }

  @Test
  void preservesOneUtf8BomAndRejectsRepeatedMarks() {
    byte[] mark = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    byte[] body = prefixed(mark, bytes(BODY));
    try (var parsed = ProviderCreateBody.consume(body)) {
      assertThat(parsed.command().name()).isEqualTo("Sample");
      assertThat(parsed.credential()).isEqualTo(bytes("sentinel-key"));
      assertThat(body).containsOnly((byte) 0);
    }
    byte[] repeated = prefixed(mark, prefixed(mark, bytes(BODY)));
    assertThatThrownBy(() -> ProviderCreateBody.consume(repeated)).hasMessage("INVALID_INPUT");
    assertThat(repeated).containsOnly((byte) 0);
  }

  private static byte[] prefixed(byte[] prefix, byte[] value) {
    byte[] result = new byte[prefix.length + value.length];
    System.arraycopy(prefix, 0, result, 0, prefix.length);
    System.arraycopy(value, 0, result, prefix.length, value.length);
    return result;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}

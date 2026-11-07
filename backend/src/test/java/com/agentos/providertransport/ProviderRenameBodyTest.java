package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderRenameBodyTest {
  private static final UUID TARGET = UUID.fromString("71a00000-0000-4000-8000-000000000001");
  private static final String BODY = "{\"expectedRevision\":\"1\",\"name\":\" Sample \"}";

  @Test
  void consumesExactFieldsNormalizesNameAndRedactsRepresentations() {
    byte[] owned = bytes(BODY);
    var parsed = ProviderRenameBody.consume(TARGET, owned);
    assertThat(owned).isEqualTo(new byte[owned.length]);
    assertThat(parsed.command().target()).isEqualTo(TARGET);
    assertThat(parsed.command().expectedRevision()).isEqualTo(1);
    assertThat(parsed.command().name()).isEqualTo("Sample");
    assertThat(parsed.toString()).isEqualTo("ProviderRenameBody[redacted]");
    assertThat(parsed.command().toString()).doesNotContain("Sample", TARGET.toString());
    assertThat(
            ProviderRenameBody.consume(
                    TARGET, bytes("{\"name\":\"Order independent\",\"expectedRevision\":\"2\"}"))
                .command()
                .name())
        .isEqualTo("Order independent");
  }

  @Test
  void acceptsZeroAndSignedLongMaximumAsCanonicalStringRevisions() {
    for (String value : new String[] {"0", "1", "9223372036854775807"}) {
      byte[] owned = bytes(BODY.replace("\"1\"", "\"" + value + "\""));
      assertThat(ProviderRenameBody.consume(TARGET, owned).command().expectedRevision())
          .isEqualTo(Long.parseLong(value));
      assertThat(owned).isEqualTo(new byte[owned.length]);
    }
  }

  @Test
  void rejectsNoncanonicalOverflowAndCoercedRevisions() {
    for (String value :
        new String[] {
          "\"\"",
          "\"00\"",
          "\"01\"",
          "\"+1\"",
          "\"-1\"",
          "\"-0\"",
          "\" 1\"",
          "\"1 \"",
          "\"1.0\"",
          "\"1e0\"",
          "\"١\"",
          "\"9223372036854775808\"",
          "\"18446744073709551615\"",
          "\"9999999999999999999999999999999999999999\"",
          "0",
          "1",
          "1.0",
          "1e0",
          "null",
          "true",
          "[]",
          "{}"
        }) {
      invalid(bytes(BODY.replace("\"1\"", value)));
    }
  }

  @Test
  void rejectsUnknownMissingDuplicateAndWrongShapeBodies() {
    for (String body :
        new String[] {
          "",
          " ",
          "{}",
          "[]",
          "null",
          "true",
          "1",
          "\"text\"",
          "{\"name\":\"A\"}",
          "{\"expectedRevision\":\"1\"}",
          BODY.replace("\"name\":", "\"extra\":\"ignored\",\"name\":"),
          BODY.replace("\"name\":", "\"target\":\"" + TARGET + "\",\"name\":"),
          BODY.replace("\"name\":", "\"name\":\"Duplicate\",\"name\":"),
          BODY.replace("\"name\":", "\"na\\u006de\":\"Duplicate\",\"name\":"),
          BODY.replace("\"expectedRevision\":", "\"expectedRevision\":\"0\",\"expectedRevision\":"),
          BODY + "{}",
          BODY + "null",
          BODY + " trailing",
          BODY.substring(0, BODY.length() - 1),
          BODY.replace(",\"name\"", ",,\"name\""),
          BODY.replace("\" Sample \"", "null"),
          BODY.replace("\" Sample \"", "1"),
          BODY.replace("\" Sample \"", "true"),
          BODY.replace("\" Sample \"", "[]"),
          BODY.replace("\" Sample \"", "{}"),
          "{'expectedRevision':'1','name':'A'}",
          "{expectedRevision:\"1\",name:\"A\"}",
          BODY.replace("}", ",}"),
          "/* comment */" + BODY
        }) {
      invalid(bytes(body));
    }
  }

  @Test
  void appliesExistingUnicodeCodePointNameRulesWithoutUtf16LengthLimits() {
    String emoji = "😀";
    for (String name : new String[] {"é", emoji.repeat(100), "A".repeat(100), "中 文", "e\u0301"}) {
      byte[] body = bytes(BODY.replace(" Sample ", "\u00a0\ufeff\\t" + name + "\u2003"));
      assertThat(ProviderRenameBody.consume(TARGET, body).command().name()).isEqualTo(name);
      assertThat(body).containsOnly((byte) 0);
    }
    for (String name :
        new String[] {
          "",
          " ",
          "\\t",
          "\u00a0\ufeff",
          "A".repeat(101),
          emoji.repeat(101),
          "A\\u0000B",
          "A\\tB",
          "A\\u007fB",
          "A\\u0085B",
          "A\\u2028B",
          "A\\u2029B",
          "\\ud800",
          "\\udc00",
          "\\ud800A",
          "A\\udc00"
        }) {
      invalid(bytes(BODY.replace(" Sample ", name)));
    }
    assertThat(
            ProviderRenameBody.consume(TARGET, bytes(BODY.replace(" Sample ", "\\ud83d\\ude00")))
                .command()
                .name())
        .isEqualTo(emoji);
  }

  @Test
  void rejectsMalformedUtf8AlternateEncodingsAndInvalidTargetsWhileErasingInput() {
    for (byte[] body :
        new byte[][] {
          {(byte) 0xc3, 0x28},
          {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
          {(byte) 0xc0, (byte) 0xaf},
          {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
          BODY.getBytes(StandardCharsets.UTF_16LE),
          BODY.getBytes(StandardCharsets.UTF_16BE)
        }) {
      invalid(body);
    }
    for (UUID target :
        new UUID[] {
          null, new UUID(0, 0), UUID.fromString("71a00000-0000-7000-8000-000000000001")
        }) {
      byte[] owned = bytes(BODY);
      assertThatThrownBy(() -> ProviderRenameBody.consume(target, owned))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_INPUT")
          .hasNoCause();
      assertThat(owned).isEqualTo(new byte[owned.length]);
    }
    assertThatThrownBy(() -> ProviderRenameBody.consume(TARGET, null)).hasMessage("INVALID_INPUT");
  }

  @Test
  void acceptsOneOptionalUtf8BomButRejectsUtf16AndUtf32WithOrWithoutBom() {
    for (byte[] owned :
        new byte[][] {
          bytes(BODY), prefixed(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, bytes(BODY))
        }) {
      assertThat(ProviderRenameBody.consume(TARGET, owned).command().name()).isEqualTo("Sample");
      assertThat(owned).isEqualTo(new byte[owned.length]);
    }
    String[] encodings = {"UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"};
    byte[][] marks = {
      {(byte) 0xff, (byte) 0xfe}, {(byte) 0xfe, (byte) 0xff},
      {(byte) 0xff, (byte) 0xfe, 0, 0}, {0, 0, (byte) 0xfe, (byte) 0xff}
    };
    for (int index = 0; index < encodings.length; index++) {
      byte[] encoded = BODY.getBytes(Charset.forName(encodings[index]));
      invalid(encoded.clone());
      invalid(prefixed(marks[index], encoded));
    }
    invalid(bytes("\ufeff\ufeff" + BODY));
    invalid(bytes(" \ufeff" + BODY));
    byte[] exact = bytes("\ufeff" + BODY + " ".repeat(8192 - 3 - bytes(BODY).length));
    assertThat(exact).hasSize(8192);
    assertThat(ProviderRenameBody.consume(TARGET, exact).command().name()).isEqualTo("Sample");
    assertThat(exact).isEqualTo(new byte[exact.length]);
    invalid(bytes("\ufeff" + BODY + " ".repeat(8193 - 3 - bytes(BODY).length)));
  }

  @Test
  void acceptsExactly8192BytesRejectsSentinelAndNeverReadsBeyondItOrClosesCallerStream() {
    byte[] exact = bytes(BODY + " ".repeat(8192 - bytes(BODY).length));
    assertThat(exact).hasSize(8192);
    assertThat(ProviderRenameBody.consume(TARGET, exact).command().name()).isEqualTo("Sample");
    assertThat(exact).containsOnly((byte) 0);
    var exactStream = new ObservedStream(bytes(BODY + " ".repeat(8192 - bytes(BODY).length)), 7);
    assertThat(ProviderRenameBody.read(TARGET, exactStream).command().name()).isEqualTo("Sample");
    assertThat(exactStream.position).isEqualTo(8192);
    assertThat(exactStream.closed).isFalse();
    assertThat(exactStream.borrowed).containsOnly((byte) 0);
    var excessive = new ObservedStream(bytes(BODY + " ".repeat(20_000)), 8193);
    assertThatThrownBy(() -> ProviderRenameBody.read(TARGET, excessive))
        .hasMessage("INVALID_INPUT");
    assertThat(excessive.position).isEqualTo(8193);
    assertThat(excessive.closed).isFalse();
    assertThat(excessive.borrowed).containsOnly((byte) 0);
    invalid(bytes(BODY + " ".repeat(8193 - bytes(BODY).length)));
  }

  @Test
  void nonprogressAndIoFailuresAreBoundedRedactedAndLeaveStreamsOpen() {
    for (boolean fail : new boolean[] {false, true}) {
      var stream =
          new ObservedStream(bytes(BODY), 0) {
            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
              borrowed = target;
              Arrays.fill(target, (byte) 'x');
              if (fail) throw new IOException("sensitive upstream diagnostic");
              return 0;
            }
          };
      assertThatThrownBy(() -> ProviderRenameBody.read(TARGET, stream))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_INPUT")
          .hasNoCause();
      assertThat(stream.closed).isFalse();
      assertThat(stream.borrowed).containsOnly((byte) 0);
    }
    var malformed = new ObservedStream(bytes("not JSON"), 3);
    assertThatThrownBy(() -> ProviderRenameBody.read(TARGET, malformed))
        .hasMessage("INVALID_INPUT");
    assertThat(malformed.closed).isFalse();
    assertThat(malformed.borrowed).containsOnly((byte) 0);
    assertThatThrownBy(() -> ProviderRenameBody.read(TARGET, null)).hasMessage("INVALID_INPUT");
  }

  private static void invalid(byte[] owned) {
    assertThatThrownBy(() -> ProviderRenameBody.consume(TARGET, owned))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_INPUT")
        .hasNoCause();
    assertThat(owned).isEqualTo(new byte[owned.length]);
  }

  private static byte[] prefixed(byte[] prefix, byte[] content) {
    byte[] result = Arrays.copyOf(prefix, prefix.length + content.length);
    System.arraycopy(content, 0, result, prefix.length, content.length);
    return result;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static class ObservedStream extends InputStream {
    private final ByteArrayInputStream source;
    private final int chunk;
    int position;
    boolean closed;
    byte[] borrowed;

    ObservedStream(byte[] content, int chunk) {
      source = new ByteArrayInputStream(content);
      this.chunk = chunk;
    }

    @Override
    public int read() {
      throw new AssertionError("Decoder must use bounded bulk reads");
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
      borrowed = target;
      int count = source.read(target, offset, Math.min(length, chunk));
      if (count > 0) position += count;
      return count;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}

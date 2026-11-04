package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProviderCreateIngressTest {
  private static final String BODY =
      """
      {"provider":"OPENAI","credentialMode":"OPENAI_API_KEY","name":"Test",
       "options":{},"apiKey":"sentinel"}
      """;

  @Test
  void acceptsFragmentedStreamAndErasesReadBufferWithoutClosingCallerStream() {
    var stream = new Fragmented(BODY.getBytes(StandardCharsets.UTF_8), 3);
    try (var result = ProviderCreateBody.read(stream)) {
      assertThat(result.credential()).isEqualTo("sentinel".getBytes(StandardCharsets.UTF_8));
      assertThat(stream.borrowed).containsOnly((byte) 0);
    }
    assertThat(stream.closed).isFalse();
  }

  @Test
  void acceptsExactly8192BytesButRejectsTheNextByte() {
    String exact = BODY.stripTrailing();
    exact += " ".repeat(8192 - exact.getBytes(StandardCharsets.UTF_8).length);
    try (var result =
        ProviderCreateBody.read(new ByteArrayInputStream(exact.getBytes(StandardCharsets.UTF_8)))) {
      assertThat(result.command().name()).isEqualTo("Test");
    }
    var overflow =
        new Fragmented((exact + " ").getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
    assertThatThrownBy(() -> ProviderCreateBody.read(overflow)).hasMessage("INVALID_INPUT");
    assertThat(overflow.position).isEqualTo(8193);
    assertThat(overflow.borrowed).containsOnly((byte) 0);
  }

  @Test
  void oversizedStreamStopsBeforeReadingTheEntireBody() {
    var stream = new Fragmented(new byte[100_000], 7);
    assertThatThrownBy(() -> ProviderCreateBody.read(stream)).hasMessage("INVALID_INPUT");
    assertThat(stream.position).isEqualTo(8193);
    assertThat(stream.borrowed).containsOnly((byte) 0);
  }

  @Test
  void ioFailureHasNoSecretCauseAndClearsPartiallyReadBody() {
    var stream =
        new Fragmented(BODY.getBytes(StandardCharsets.UTF_8), 10) {
          @Override
          public int read(byte[] destination, int offset, int length) throws IOException {
            if (position > 0) throw new IOException("secret-sentinel");
            return super.read(destination, offset, length);
          }
        };
    assertThatThrownBy(() -> ProviderCreateBody.read(stream))
        .hasMessage("INVALID_INPUT")
        .hasNoCause();
    assertThat(stream.borrowed).containsOnly((byte) 0);
  }

  @Test
  void emptyNullAndNonProgressingStreamsAreRejected() {
    assertThatThrownBy(() -> ProviderCreateBody.read(null)).hasMessage("INVALID_INPUT");
    assertThatThrownBy(() -> ProviderCreateBody.read(InputStream.nullInputStream()))
        .hasMessage("INVALID_INPUT");
    var stalled =
        new InputStream() {
          @Override
          public int read() {
            return 0;
          }

          @Override
          public int read(byte[] destination, int offset, int length) {
            return 0;
          }
        };
    assertThatThrownBy(() -> ProviderCreateBody.read(stalled)).hasMessage("INVALID_INPUT");
  }

  private static class Fragmented extends InputStream {
    private final byte[] source;
    private final int chunk;
    int position;
    byte[] borrowed;
    boolean closed;

    Fragmented(byte[] source, int chunk) {
      this.source = source;
      this.chunk = chunk;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
      borrowed = destination;
      if (position == source.length) return -1;
      int count = Math.min(Math.min(chunk, length), source.length - position);
      System.arraycopy(source, position, destination, offset, count);
      position += count;
      return count;
    }

    @Override
    public int read() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}

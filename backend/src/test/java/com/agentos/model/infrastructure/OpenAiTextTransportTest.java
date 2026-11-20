package com.agentos.model.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.model.application.ModelMessage;
import com.agentos.model.application.ModelRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAiTextTransportTest {
  private static final String SUCCESS =
      """
{"id":"chatcmpl-fixture","object":"chat.completion","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Verified output"}}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
""";

  private ModelRequest request() {
    UUID id = UUID.randomUUID();
    return new ModelRequest(
        id, id, id, id, id, id, "test-model", List.of(new ModelMessage("user", "Input")), 100);
  }

  @Test
  void exactPreparedBodyAndUsagePassThroughOneRealHttpRequest() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    byte[] body = OpenAiTextTransport.prepare(request());
    server.createContext(
        "/completion",
        exchange -> {
          calls.incrementAndGet();
          assertThat(exchange.getRequestMethod()).isEqualTo("POST");
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer disposable-key");
          assertThat(exchange.getRequestBody().readAllBytes()).isEqualTo(body);
          byte[] response = SUCCESS.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    var timer = Executors.newSingleThreadScheduledExecutor();
    try {
      var transport =
          OpenAiTextTransport.loopback(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/completion"),
              Clock.systemUTC(),
              timer);
      var result =
          transport.execute(
              body,
              "disposable-key".getBytes(StandardCharsets.US_ASCII),
              Instant.now().plusSeconds(5),
              100);
      assertThat(result.content()).isEqualTo("Verified output");
      assertThat(result.inputTokens()).isEqualTo(10);
      assertThat(result.outputTokens()).isEqualTo(3);
      assertThat(result.evidenceSha256()).matches("[0-9a-f]{64}");
      assertThat(result.toString()).doesNotContain("Verified output");
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
      timer.shutdownNow();
    }
  }

  @Test
  void refusesUnsupportedOrAmbiguousResponsesWithoutGuessingSuccess() {
    for (String body :
        List.of(
            SUCCESS.replace("\"stop\"", "\"length\""),
            SUCCESS.replace("\"stop\"", "\"tool_calls\""),
            SUCCESS.replace("\"total_tokens\":13", "\"total_tokens\":14"),
            SUCCESS.replace("\"completion_tokens\":3", "\"completion_tokens\":3.0"),
            SUCCESS.replace("\"id\":", "\"id\":\"duplicate\",\"id\":"),
            SUCCESS + "{}",
            SUCCESS.replace("Verified output", ""),
            SUCCESS.replace("\"role\":\"assistant\"", "\"role\":\"user\""))) {
      assertThatThrownBy(
              () -> OpenAiTextTransport.decode(body.getBytes(StandardCharsets.UTF_8), 100))
          .isInstanceOf(OpenAiTextTransport.Unknown.class)
          .hasMessage("OpenAI outcome is unconfirmed");
    }
    assertThatThrownBy(
            () -> OpenAiTextTransport.decode(SUCCESS.getBytes(StandardCharsets.UTF_8), 2))
        .isInstanceOf(OpenAiTextTransport.Unknown.class);
  }

  @Test
  void serializesOnlyBoundedTextAndDoesNotLogPayloads() {
    String body = new String(OpenAiTextTransport.prepare(request()), StandardCharsets.UTF_8);
    assertThat(body)
        .contains("\"max_completion_tokens\":100", "\"store\":false", "\"stream\":false")
        .doesNotContain("tools");
    var r = request();
    assertThatThrownBy(
            () ->
                OpenAiTextTransport.prepare(
                    new ModelRequest(
                        r.organizationId(),
                        r.missionId(),
                        r.taskId(),
                        r.executionRunId(),
                        r.agentId(),
                        r.providerId(),
                        r.model(),
                        List.of(new ModelMessage("tool", "untrusted")),
                        100)))
        .isInstanceOf(OpenAiTextTransport.Unknown.class);
  }

  @Test
  void redirectIsNotFollowedAndNotRetried() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/completion",
        e -> {
          calls.incrementAndGet();
          e.getResponseHeaders().set("Location", "/other");
          e.sendResponseHeaders(307, -1);
          e.close();
        });
    server.createContext(
        "/other",
        e -> {
          calls.addAndGet(100);
          e.sendResponseHeaders(500, -1);
          e.close();
        });
    server.start();
    var timer = Executors.newSingleThreadScheduledExecutor();
    try {
      var transport =
          OpenAiTextTransport.loopback(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/completion"),
              Clock.systemUTC(),
              timer);
      assertThatThrownBy(
              () ->
                  transport.execute(
                      OpenAiTextTransport.prepare(request()),
                      "disposable-key".getBytes(StandardCharsets.US_ASCII),
                      Instant.now().plusSeconds(5),
                      100))
          .isInstanceOf(OpenAiTextTransport.Unknown.class);
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
      timer.shutdownNow();
    }
  }
}

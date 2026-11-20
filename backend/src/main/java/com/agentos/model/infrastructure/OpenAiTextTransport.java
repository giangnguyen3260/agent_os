package com.agentos.model.infrastructure;

import com.agentos.model.application.ModelRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.util.Timeout;

/** Single bounded text request; no redirects, retries, tools, streaming or arbitrary endpoint. */
public final class OpenAiTextTransport {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final int RESPONSE_LIMIT = 1048576;
  private final URI endpoint;
  private final Clock clock;
  private final ScheduledExecutorService cancellations;
  private final boolean test;

  public OpenAiTextTransport(Clock clock, ScheduledExecutorService cancellations) {
    this(URI.create("https://api.openai.com/v1/chat/completions"), clock, cancellations, false);
  }

  private OpenAiTextTransport(
      URI endpoint, Clock clock, ScheduledExecutorService cancellations, boolean test) {
    this.endpoint = endpoint;
    this.clock = clock;
    this.cancellations = cancellations;
    this.test = test;
  }

  static OpenAiTextTransport loopback(
      URI endpoint, Clock clock, ScheduledExecutorService cancellations) {
    if (!"http".equals(endpoint.getScheme())
        || !"127.0.0.1".equals(endpoint.getHost())
        || endpoint.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid test endpoint");
    return new OpenAiTextTransport(endpoint, clock, cancellations, true);
  }

  public record Result(
      String requestId,
      String content,
      long inputTokens,
      long outputTokens,
      String evidenceSha256) {
    @Override
    public String toString() {
      return "OpenAiResult[redacted]";
    }
  }

  public static final class Unknown extends RuntimeException {
    public Unknown() {
      super("OpenAI outcome is unconfirmed", null, false, false);
    }
  }

  public static byte[] prepare(ModelRequest request) {
    try {
      if (request == null
          || request.model() == null
          || !request.model().matches("[A-Za-z0-9._:-]{1,256}")
          || request.maxOutputTokens() == null
          || request.maxOutputTokens() < 1
          || request.maxOutputTokens() > 4096
          || request.messages() == null
          || request.messages().isEmpty()
          || request.messages().size() > 8) throw new Unknown();
      var root =
          JSON.createObjectNode()
              .put("model", request.model())
              .put("max_completion_tokens", request.maxOutputTokens())
              .put("stream", false)
              .put("store", false);
      var messages = root.putArray("messages");
      int bytes = 0;
      for (var message : request.messages()) {
        if (message == null
            || !java.util.List.of("system", "user").contains(message.role())
            || message.content() == null
            || message.content().isBlank()
            || !StandardCharsets.UTF_8.newEncoder().canEncode(message.content()))
          throw new Unknown();
        bytes = Math.addExact(bytes, message.content().getBytes(StandardCharsets.UTF_8).length);
        if (bytes > 16384) throw new Unknown();
        messages.addObject().put("role", message.role()).put("content", message.content());
      }
      byte[] body = JSON.writeValueAsBytes(root);
      if (body.length > 32768) throw new Unknown();
      return body;
    } catch (Exception failure) {
      throw new Unknown();
    }
  }

  public Result execute(byte[] exactBody, byte[] key, Instant deadline, int maxOutputTokens) {
    HttpPost request = null;
    try {
      if (exactBody == null
          || exactBody.length == 0
          || exactBody.length > 32768
          || key == null
          || key.length < 1
          || key.length > 4096
          || maxOutputTokens < 1
          || maxOutputTokens > 4096) throw new Unknown();
      for (byte b : key) if (b < 33 || b > 126) throw new Unknown();
      long remaining = Math.min(30000, Duration.between(clock.instant(), deadline).toMillis());
      if (remaining <= 0) throw new Unknown();
      var dns =
          new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
              if (!endpoint.getHost().equals(host)) throw new UnknownHostException();
              var addresses = InetAddress.getAllByName(host);
              for (var address : addresses)
                if (test ? !address.isLoopbackAddress() : !publicAddress(address))
                  throw new UnknownHostException();
              if (!deadline.isAfter(clock.instant())) throw new UnknownHostException();
              return addresses;
            }

            public String resolveCanonicalHostname(String host) throws UnknownHostException {
              if (!endpoint.getHost().equals(host)) throw new UnknownHostException();
              return host;
            }
          };
      request = new HttpPost(endpoint);
      request.setHeader("Authorization", "Bearer " + new String(key, StandardCharsets.US_ASCII));
      request.setHeader("Accept", "application/json");
      request.setHeader("Accept-Encoding", "identity");
      request.setEntity(new ByteArrayEntity(exactBody.clone(), ContentType.APPLICATION_JSON));
      var pending = request;
      var timeout = cancellations.schedule(pending::cancel, remaining, TimeUnit.MILLISECONDS);
      try (var client =
          HttpClients.custom()
              .setConnectionManager(
                  PoolingHttpClientConnectionManagerBuilder.create()
                      .setDnsResolver(dns)
                      .setDefaultConnectionConfig(
                          ConnectionConfig.custom()
                              .setConnectTimeout(Timeout.ofMilliseconds(Math.min(3000, remaining)))
                              .setSocketTimeout(Timeout.ofMilliseconds(remaining))
                              .build())
                      .build())
              .setDefaultRequestConfig(
                  RequestConfig.custom()
                      .setResponseTimeout(Timeout.ofMilliseconds(remaining))
                      .build())
              .disableAutomaticRetries()
              .disableRedirectHandling()
              .disableContentCompression()
              .disableCookieManagement()
              .disableAuthCaching()
              .build()) {
        try (var response =
            client.executeOpen(
                new HttpHost(
                    endpoint.getScheme(),
                    endpoint.getHost(),
                    endpoint.getPort() < 0 ? 443 : endpoint.getPort()),
                request,
                new BasicHttpContext())) {
          var entity = response.getEntity();
          if (response.getCode() != 200
              || entity == null
              || entity.getContentType() == null
              || !entity.getContentType().matches("(?i)application/json(?:\\s*;.*)?")
              || entity.getContentLength() > RESPONSE_LIMIT
              || entity.getContentEncoding() != null
                  && !"identity".equalsIgnoreCase(entity.getContentEncoding())) throw new Unknown();
          var body = entity.getContent().readNBytes(RESPONSE_LIMIT + 1);
          if (!deadline.isAfter(clock.instant())) throw new Unknown();
          return decode(body, maxOutputTokens);
        } finally {
          pending.cancel();
        }
      } finally {
        timeout.cancel(false);
      }
    } catch (Exception failure) {
      throw new Unknown();
    } finally {
      if (request != null) {
        request.cancel();
        request.removeHeaders("Authorization");
      }
    }
  }

  static Result decode(byte[] body, int maxOutputTokens) {
    try {
      if (body == null || body.length > RESPONSE_LIMIT) throw new Unknown();
      JsonNode root = JSON.readTree(body),
          choices = root.path("choices"),
          usage = root.path("usage");
      if (!root.path("id").isTextual()
          || !root.path("id").asText().matches("[A-Za-z0-9_-]{1,256}")
          || !"chat.completion".equals(root.path("object").asText())
          || !choices.isArray()
          || choices.size() != 1
          || !"stop".equals(choices.get(0).path("finish_reason").asText())) throw new Unknown();
      var message = choices.get(0).path("message");
      if (!"assistant".equals(message.path("role").asText())
          || !message.path("content").isTextual()
          || message.hasNonNull("tool_calls")
          || message.hasNonNull("function_call")
          || message.hasNonNull("refusal")) throw new Unknown();
      String content = message.path("content").asText();
      if (content.isBlank()
          || content.getBytes(StandardCharsets.UTF_8).length > 262144
          || !StandardCharsets.UTF_8.newEncoder().canEncode(content)) throw new Unknown();
      for (String field : java.util.List.of("prompt_tokens", "completion_tokens", "total_tokens"))
        if (!usage.path(field).isIntegralNumber()
            || !usage.path(field).canConvertToLong()
            || usage.path(field).longValue() < 0) throw new Unknown();
      long input = usage.path("prompt_tokens").longValue(),
          output = usage.path("completion_tokens").longValue();
      if (input > 17408
          || output > maxOutputTokens
          || Math.addExact(input, output) != usage.path("total_tokens").longValue())
        throw new Unknown();
      return new Result(
          root.path("id").asText(),
          content,
          input,
          output,
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
    } catch (Exception failure) {
      throw new Unknown();
    }
  }

  private static boolean publicAddress(InetAddress address) {
    byte[] bytes = address.getAddress();
    int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
    if (bytes.length == 16) {
      // Global unicast only. Exclude protocol assignments, documentation and transition prefixes.
      return (a & 0xe0) == 0x20
          && !(a == 0x20 && b == 0x02)
          && !(a == 0x20
              && b == 0x01
              && (Byte.toUnsignedInt(bytes[2]) < 2 || bytes[2] == 0x0d && bytes[3] == (byte) 0xb8))
          && !(a == 0x3f && b == 0xff);
    }
    int c = Byte.toUnsignedInt(bytes[2]);
    return a > 0
        && a < 224
        && a != 10
        && a != 127
        && !(a == 100 && b >= 64 && b <= 127)
        && !(a == 169 && b == 254)
        && !(a == 172 && b >= 16 && b <= 31)
        && !(a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
        && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
        && !(a == 203 && b == 0 && c == 113);
  }
}

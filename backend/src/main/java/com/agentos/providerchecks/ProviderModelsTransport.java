package com.agentos.providerchecks;

import com.agentos.providerchecks.ProviderAccessCheckDispatch.Result;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.util.Timeout;

/** Performs the first models-list request using a fixed HTTPS route for each provider. */
public final class ProviderModelsTransport implements ProviderAccessCheckRunner.Transport {
  private static final int BODY_LIMIT = 1_048_576;
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final Clock clock;
  private final ScheduledExecutorService cancellations;

  public ProviderModelsTransport(Clock clock, ScheduledExecutorService cancellations) {
    this.clock = Objects.requireNonNull(clock);
    this.cancellations = Objects.requireNonNull(cancellations);
  }

  private record Route(String host, String path) {}

  private static Route route(Provider provider) {
    return switch (provider) {
      case OPENAI -> new Route("api.openai.com", "/v1/models");
      case ANTHROPIC -> new Route("api.anthropic.com", "/v1/models?limit=100");
      case GOOGLE_GEMINI ->
          new Route("generativelanguage.googleapis.com", "/v1beta/models?pageSize=100");
    };
  }

  @Override
  public Result request(Provider provider, String workspace, byte[] key, OffsetDateTime deadline) {
    var route = route(provider);
    if (workspace != null
        && (provider != Provider.ANTHROPIC || !workspace.matches("wrkspc_[a-zA-Z0-9]{1,128}")))
      return Result.PROTOCOL_ERROR;
    long remaining =
        Math.min(10_000, Duration.between(clock.instant(), deadline.toInstant()).toMillis());
    if (remaining <= 0) return Result.UNKNOWN;

    // Pass only the validated DNS answer to the actual connector. Never resolve the name twice.
    var dns =
        new DnsResolver() {
          public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!route.host().equals(host)) throw new UnknownHostException();
            InetAddress[] found = InetAddress.getAllByName(host);
            if (!deadline.toInstant().isAfter(clock.instant())) throw new UnknownHostException();
            for (var address : found) if (!publicAddress(address)) throw new UnknownHostException();
            return found;
          }

          public String resolveCanonicalHostname(String host) throws UnknownHostException {
            if (!route.host().equals(host)) throw new UnknownHostException();
            return host;
          }
        };
    var request = new HttpGet("https://" + route.host() + route.path());
    request.setHeader("Accept", "application/json");
    request.setHeader("Accept-Encoding", "identity");
    String credential = new String(key, StandardCharsets.US_ASCII);
    if (provider == Provider.GOOGLE_GEMINI) request.setHeader("x-goog-api-key", credential);
    else request.setHeader("Authorization", "Bearer " + credential);
    if (provider == Provider.ANTHROPIC) {
      request.setHeader("anthropic-version", "2026-10-21");
      if (workspace != null) request.setHeader("anthropic-workspace-id", workspace);
    }

    var timeout = cancellations.schedule(request::cancel, remaining, TimeUnit.MILLISECONDS);
    try (var client =
        HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(dns)
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(Math.min(3_000, remaining)))
                            .setSocketTimeout(Timeout.ofMilliseconds(remaining))
                            .build())
                    .build())
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(remaining))
                    .build())
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableContentCompression()
            .disableAuthCaching()
            .build()) {
      try (var response =
          client.executeOpen(
              new HttpHost("https", route.host(), 443), request, new BasicHttpContext())) {
        try {
          if (response.getCode() != 200) return status(response.getCode());
          var entity = response.getEntity();
          if (entity == null
              || entity.getContentType() == null
              || !entity.getContentType().matches("(?i)application/json(?:\\s*;.*)?")
              || (entity.getContentEncoding() != null
                  && !entity.getContentEncoding().equalsIgnoreCase("identity")))
            return Result.PROTOCOL_ERROR;
          if (entity.getContentLength() > BODY_LIMIT) return Result.RESPONSE_LIMIT;
          byte[] body = entity.getContent().readNBytes(BODY_LIMIT + 1);
          if (body.length > BODY_LIMIT) return Result.RESPONSE_LIMIT;
          return body(provider, body);
        } finally {
          // Abort before closing the response so an unread body is never drained.
          request.cancel();
        }
      }
    } catch (UnknownHostException
        | java.net.ConnectException
        | javax.net.ssl.SSLHandshakeException failure) {
      return deadline.toInstant().isAfter(clock.instant())
          ? Result.NETWORK_UNAVAILABLE
          : Result.UNKNOWN;
    } catch (IOException failure) {
      return Result.UNKNOWN;
    } finally {
      timeout.cancel(false);
      request.removeHeaders("Authorization");
      request.removeHeaders("x-goog-api-key");
    }
  }

  static Result status(int status) {
    return switch (status) {
      case 401 -> Result.UNAUTHENTICATED;
      case 403 -> Result.FORBIDDEN;
      case 429 -> Result.RATE_LIMITED;
      default ->
          status >= 500 && status < 600 ? Result.PROVIDER_UNAVAILABLE : Result.PROTOCOL_ERROR;
    };
  }

  static Result body(Provider provider, byte[] body) {
    try {
      var root = JSON.readTree(body);
      if (root == null || !root.isObject()) return Result.PROTOCOL_ERROR;
      boolean gemini = provider == Provider.GOOGLE_GEMINI;
      var models = root.path(gemini ? "models" : "data");
      if (!models.isArray()) return Result.PROTOCOL_ERROR;
      var seen = new HashMap<String, String>();
      int limit = provider == Provider.OPENAI ? 1000 : 100;
      int count = 0;
      String last = null;
      for (var model : models) {
        if (++count > limit) return Result.RESPONSE_LIMIT;
        var id = model.path(gemini ? "name" : "id");
        var display = model.path(gemini ? "displayName" : "display_name");
        if (!id.isTextual()
            || !safeText(id.textValue(), 256)
            || (gemini && (!id.textValue().startsWith("models/") || id.textValue().length() == 7))
            || (provider != Provider.OPENAI
                && !display.isMissingNode()
                && !display.isNull()
                && (!display.isTextual() || !safeText(display.textValue(), 255))))
          return Result.PROTOCOL_ERROR;
        last = id.textValue();
        String label =
            provider != Provider.OPENAI && display.isTextual() ? display.textValue() : "";
        String previous = seen.putIfAbsent(last, label);
        if (previous != null && !previous.equals(label)) return Result.PROTOCOL_ERROR;
      }
      if (provider == Provider.ANTHROPIC) {
        var more = root.path("has_more");
        var cursor = root.path("last_id");
        if (!more.isBoolean()
            || (!cursor.isNull()
                && !cursor.isMissingNode()
                && (!cursor.isTextual() || !safeText(cursor.textValue(), 256)))
            || (more.booleanValue() && (last == null || !last.equals(cursor.textValue()))))
          return Result.PROTOCOL_ERROR;
      }
      var token = root.path("nextPageToken");
      if (gemini
          && !token.isMissingNode()
          && (!token.isTextual()
              || !safeText(token.textValue(), 2048)
              || token.textValue().getBytes(StandardCharsets.UTF_8).length > 2048))
        return Result.PROTOCOL_ERROR;
      return Result.ACCESSIBLE;
    } catch (IOException failure) {
      return Result.PROTOCOL_ERROR;
    }
  }

  private static boolean safeText(String value, int limit) {
    return !value.isBlank()
        && value.codePointCount(0, value.length()) <= limit
        && value
            .codePoints()
            .noneMatch(
                cp ->
                    Character.isISOControl(cp)
                        || cp == 0x2028
                        || cp == 0x2029
                        || cp >= 0xd800 && cp <= 0xdfff);
  }

  static boolean publicAddress(InetAddress address) {
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

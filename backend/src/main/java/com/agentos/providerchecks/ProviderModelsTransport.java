package com.agentos.providerchecks;

import com.agentos.providerchecks.ProviderAccessCheckDispatch.Result;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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

/** Reads a bounded models-list page using a fixed HTTPS route for each provider. */
public final class ProviderModelsTransport implements ProviderAccessCheckRunner.Transport {
  private static final int BODY_LIMIT = 1_048_576;
  private static final JsonFactory JSON =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
          .build();

  private final Clock clock;
  private final ScheduledExecutorService cancellations;

  public ProviderModelsTransport(Clock clock, ScheduledExecutorService cancellations) {
    this.clock = Objects.requireNonNull(clock);
    this.cancellations = Objects.requireNonNull(cancellations);
  }

  public record Model(String id, String displayName) {}

  public record Page(Result result, List<Model> models, String nextCursor, int bodyBytes) {
    public Page {
      models = List.copyOf(models);
    }

    @Override
    public String toString() {
      return "Page[" + result + "]";
    }
  }

  private record Route(String origin, String path, String cursorParameter) {}

  private static Route route(Provider provider) {
    return switch (provider) {
      case OPENAI -> new Route("api.openai.com", "/v1/models", null);
      case ANTHROPIC -> new Route("api.anthropic.com", "/v1/models?limit=100", "after_id");
      case GOOGLE_GEMINI ->
          new Route(
              "generativelanguage.googleapis.com", "/v1beta/models?pageSize=100", "pageToken");
    };
  }

  static URI requestUri(Provider provider, String cursor) {
    var route = route(provider);
    String url = "https://" + route.origin() + route.path();
    if (cursor == null) return URI.create(url);
    if (route.cursorParameter() == null || !validCursor(provider, cursor))
      throw new IllegalArgumentException("Invalid provider cursor");
    String encoded = URLEncoder.encode(cursor, StandardCharsets.UTF_8).replace("+", "%20");
    return URI.create(url + "&" + route.cursorParameter() + "=" + encoded);
  }

  @Override
  public Result request(Provider provider, String workspace, byte[] key, OffsetDateTime deadline) {
    return requestPage(provider, workspace, key, deadline, null, BODY_LIMIT).result();
  }

  public Page requestPage(
      Provider provider,
      String workspace,
      byte[] key,
      OffsetDateTime deadline,
      String cursor,
      int remainingBytes) {
    if (remainingBytes <= 0) return failure(Result.RESPONSE_LIMIT, 0);
    var route = route(provider);
    if (workspace != null
        && (provider != Provider.ANTHROPIC || !workspace.matches("wrkspc_[a-zA-Z0-9]{1,128}")))
      return failure(Result.PROTOCOL_ERROR, 0);
    if (cursor != null && (route.cursorParameter() == null || !validCursor(provider, cursor)))
      return failure(Result.PROTOCOL_ERROR, 0);
    long remaining =
        Math.min(10_000, Duration.between(clock.instant(), deadline.toInstant()).toMillis());
    if (remaining <= 0) return failure(Result.UNKNOWN, 0);

    // Pass only the validated DNS answer to the actual connector. Never resolve the name twice.
    var dns =
        new DnsResolver() {
          public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!route.origin().equals(host)) throw new UnknownHostException();
            InetAddress[] found = InetAddress.getAllByName(host);
            if (!deadline.toInstant().isAfter(clock.instant())) throw new UnknownHostException();
            for (var address : found) if (!publicAddress(address)) throw new UnknownHostException();
            return found;
          }

          public String resolveCanonicalHostname(String host) throws UnknownHostException {
            if (!route.origin().equals(host)) throw new UnknownHostException();
            return host;
          }
        };
    var request = new HttpGet(requestUri(provider, cursor));
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
      if (!deadline.toInstant().isAfter(clock.instant())) return failure(Result.UNKNOWN, 0);
      try (var response =
          client.executeOpen(
              new HttpHost("https", route.origin(), 443), request, new BasicHttpContext())) {
        try {
          if (response.getCode() != 200) return failure(status(response.getCode()), 0);
          var entity = response.getEntity();
          if (entity == null
              || entity.getContentType() == null
              || !entity.getContentType().matches("(?i)application/json(?:\\s*;.*)?")
              || (entity.getContentEncoding() != null
                  && !entity.getContentEncoding().equalsIgnoreCase("identity")))
            return failure(Result.PROTOCOL_ERROR, 0);
          int limit = Math.min(BODY_LIMIT, remainingBytes);
          if (entity.getContentLength() > limit) return failure(Result.RESPONSE_LIMIT, 0);
          return page(provider, entity.getContent(), cursor, limit);
        } finally {
          // Abort before closing the response so an unread body is never drained.
          request.cancel();
        }
      }
    } catch (UnknownHostException
        | java.net.ConnectException
        | javax.net.ssl.SSLHandshakeException failure) {
      return failure(
          deadline.toInstant().isAfter(clock.instant())
              ? Result.NETWORK_UNAVAILABLE
              : Result.UNKNOWN,
          0);
    } catch (IOException failure) {
      return failure(Result.UNKNOWN, 0);
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
      return page(provider, new ByteArrayInputStream(body), null, BODY_LIMIT).result();
    } catch (IOException failure) {
      return Result.PROTOCOL_ERROR;
    }
  }

  static Page page(Provider provider, InputStream input, String cursor, int limit)
      throws IOException {
    var body = new BoundedBody(input, limit);
    try (var parser = JSON.createParser(body)) {
      return decodePage(provider, parser, cursor, body);
    } catch (BodyLimit failure) {
      return failure(Result.RESPONSE_LIMIT, body.consumed());
    } catch (JsonProcessingException failure) {
      return failure(Result.PROTOCOL_ERROR, body.consumed());
    }
  }

  private static Page decodePage(
      Provider provider, JsonParser parser, String cursor, BoundedBody body) throws IOException {
    if (parser.nextToken() != JsonToken.START_OBJECT)
      return failure(Result.PROTOCOL_ERROR, body.consumed());
    boolean gemini = provider == Provider.GOOGLE_GEMINI;
    String modelField = gemini ? "models" : "data";
    int modelLimit = provider == Provider.OPENAI ? 1000 : 100;
    var models = new LinkedHashMap<String, Model>();
    boolean hasModels = false;
    Boolean hasMore = null;
    String lastId = null;
    String nextCursor = null;
    for (var field = parser.nextToken();
        field != JsonToken.END_OBJECT;
        field = parser.nextToken()) {
      if (field != JsonToken.FIELD_NAME) return failure(Result.PROTOCOL_ERROR, body.consumed());
      String name = parser.currentName();
      var value = parser.nextToken();
      if (name.equals(modelField)) {
        if (value != JsonToken.START_ARRAY) return failure(Result.PROTOCOL_ERROR, body.consumed());
        hasModels = true;
        int count = 0;
        for (var item = parser.nextToken();
            item != JsonToken.END_ARRAY;
            item = parser.nextToken()) {
          if (item == null) return failure(Result.PROTOCOL_ERROR, body.consumed());
          if (++count > modelLimit) return failure(Result.RESPONSE_LIMIT, body.consumed());
          Model model = decodeModel(provider, parser);
          if (model == null) return failure(Result.PROTOCOL_ERROR, body.consumed());
          lastId = model.id();
          Model previous = models.putIfAbsent(lastId, model);
          if (previous != null && !previous.equals(model))
            return failure(Result.PROTOCOL_ERROR, body.consumed());
        }
      } else if (provider == Provider.ANTHROPIC && name.equals("has_more")) {
        if (value != JsonToken.VALUE_TRUE && value != JsonToken.VALUE_FALSE)
          return failure(Result.PROTOCOL_ERROR, body.consumed());
        hasMore = value == JsonToken.VALUE_TRUE;
      } else if (provider == Provider.ANTHROPIC && name.equals("last_id")) {
        if (value != JsonToken.VALUE_NULL) {
          if (value != JsonToken.VALUE_STRING || !validCursor(provider, parser.getText()))
            return failure(Result.PROTOCOL_ERROR, body.consumed());
          nextCursor = parser.getText();
        }
      } else if (gemini && name.equals("nextPageToken")) {
        if (value != JsonToken.VALUE_STRING || !validCursor(provider, parser.getText()))
          return failure(Result.PROTOCOL_ERROR, body.consumed());
        nextCursor = parser.getText();
      } else {
        parser.skipChildren();
      }
    }
    if (!hasModels) return failure(Result.PROTOCOL_ERROR, body.consumed());
    if (provider == Provider.ANTHROPIC) {
      if (hasMore == null || (hasMore && (lastId == null || !lastId.equals(nextCursor))))
        return failure(Result.PROTOCOL_ERROR, body.consumed());
      if (!hasMore) nextCursor = null;
    }
    if (nextCursor != null && nextCursor.equals(cursor))
      return failure(Result.PROTOCOL_ERROR, body.consumed());
    if (parser.nextToken() != null) return failure(Result.PROTOCOL_ERROR, body.consumed());
    return new Page(Result.ACCESSIBLE, List.copyOf(models.values()), nextCursor, body.consumed());
  }

  private static Model decodeModel(Provider provider, JsonParser parser) throws IOException {
    if (parser.currentToken() != JsonToken.START_OBJECT) return null;
    boolean gemini = provider == Provider.GOOGLE_GEMINI;
    String id = null;
    String label = null;
    for (var field = parser.nextToken();
        field != JsonToken.END_OBJECT;
        field = parser.nextToken()) {
      if (field != JsonToken.FIELD_NAME) return null;
      String name = parser.currentName();
      var value = parser.nextToken();
      if (name.equals(gemini ? "name" : "id")) {
        if (value != JsonToken.VALUE_STRING || !safeText(parser.getText(), 256)) return null;
        id = parser.getText();
        if (gemini && (!id.startsWith("models/") || id.length() == 7)) return null;
      } else if (provider != Provider.OPENAI
          && name.equals(gemini ? "displayName" : "display_name")) {
        if (value == JsonToken.VALUE_NULL) continue;
        if (value != JsonToken.VALUE_STRING || !safeText(parser.getText(), 255)) return null;
        label = parser.getText();
      } else {
        parser.skipChildren();
      }
    }
    return id == null ? null : new Model(id, label);
  }

  private static Page failure(Result result, int consumed) {
    return new Page(result, List.of(), null, consumed);
  }

  private static boolean validCursor(Provider provider, String cursor) {
    int limit = provider == Provider.ANTHROPIC ? 256 : 2048;
    return safeText(cursor, limit) && cursor.getBytes(StandardCharsets.UTF_8).length <= 2048;
  }

  private static final class BodyLimit extends IOException {}

  /** Counts parsed response bytes while admitting only a single unretained overflow sentinel. */
  private static final class BoundedBody extends FilterInputStream {
    private final int limit;
    private int consumed;

    private BoundedBody(InputStream source, int limit) {
      super(source);
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      if (consumed == limit) return overflow();
      int next = in.read();
      if (next >= 0) consumed++;
      return next;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) return 0;
      if (consumed == limit) return overflow();
      int next = in.read(buffer, offset, Math.min(length, limit - consumed));
      if (next > 0) consumed += next;
      return next;
    }

    private int overflow() throws IOException {
      if (in.read() < 0) return -1;
      throw new BodyLimit();
    }

    private int consumed() {
      return consumed;
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

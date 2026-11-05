package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providerpolicy.PolicyFailure;
import jakarta.servlet.ServletInputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProviderCreateRequestTest {
  private static final String ORG = UUID.randomUUID().toString();
  private static final String KEY = UUID.randomUUID().toString();

  @Test
  void acceptsJsonUtf8AndChunkedFramingWithoutReadingBody() {
    for (String type :
        List.of(
            "application/json",
            "application/json; charset=UTF-8",
            "Application/JSON; charset=\"UTF-8\"")) {
      var request = request();
      request.removeHeader("Content-Type");
      request.addHeader("Content-Type", type);
      request.addHeader("Transfer-Encoding", "chunked");
      assertThat(ProviderCreateRequest.validate(request, ORG, "X-XSRF-TOKEN"))
          .isEqualTo(UUID.fromString(KEY));
    }
    var bounded = request();
    bounded.addHeader("Content-Length", "8192");
    assertThat(ProviderCreateRequest.validate(bounded, ORG, "X-XSRF-TOKEN"))
        .isEqualTo(UUID.fromString(KEY));
  }

  @Test
  void rejectsAmbiguousHeadersMediaFramingAndNegotiation() {
    List<Consumer<MockHttpServletRequest>> invalid =
        List.of(
            r -> r.setMethod("GET"),
            r -> r.setQueryString(""),
            r -> r.addHeader("Content-Type", "application/json"),
            r -> r.removeHeader("Content-Type"),
            r -> {
              r.removeHeader("Content-Type");
              r.addHeader("Content-Type", "application/json;charset=latin1");
            },
            r -> {
              r.removeHeader("Content-Type");
              r.addHeader("Content-Type", "application/json;charset=UTF-8;charset=UTF-8");
            },
            r -> r.addHeader("Content-Encoding", "gzip"),
            r -> r.removeHeader("X-XSRF-TOKEN"),
            r -> r.addHeader("X-XSRF-TOKEN", "duplicate"),
            r -> r.addHeader("Idempotency-Key", KEY),
            r -> {
              r.removeHeader("Idempotency-Key");
              r.addHeader("Idempotency-Key", KEY + "," + KEY);
            },
            r -> {
              r.removeHeader("Idempotency-Key");
              r.addHeader("Idempotency-Key", " " + KEY);
            },
            r -> r.addHeader("Content-Length", "8193"),
            r -> r.addHeader("Content-Length", "0"),
            r -> r.addHeader("Content-Length", "01"),
            r -> {
              r.addHeader("Content-Length", "12");
              r.addHeader("Content-Length", "12");
            },
            r -> {
              r.addHeader("Content-Length", "12");
              r.addHeader("Transfer-Encoding", "chunked");
            },
            r -> r.addHeader("Transfer-Encoding", "gzip,chunked"),
            r -> r.addHeader("Accept", "application/json;q=0,*/*;q=1"),
            r -> r.addHeader("Accept", "text/html"),
            r -> r.addHeader("Accept", "application/json;q=1.5"),
            r -> {
              r.removeHeader("Content-Type");
              r.addHeader("Content-Type", "application/json\r\nX-Injected:1");
            });
    for (var change : invalid) {
      var request = request();
      change.accept(request);
      assertThatThrownBy(() -> ProviderCreateRequest.validate(request, ORG, "X-XSRF-TOKEN"))
          .isInstanceOf(PolicyFailure.class)
          .hasMessage("BAD_REQUEST")
          .hasNoCause();
    }
  }

  @Test
  void rejectsInvalidScopeAndUntrustedCsrfHeaderConfiguration() {
    assertThatThrownBy(
            () -> ProviderCreateRequest.validate(request(), "not-an-org", "X-XSRF-TOKEN"))
        .hasMessage("BAD_REQUEST");
    assertThatThrownBy(() -> ProviderCreateRequest.validate(request(), ORG, "invalid:header"))
        .hasMessage("BAD_REQUEST");
  }

  private static MockHttpServletRequest request() {
    var request =
        new MockHttpServletRequest() {
          @Override
          public ServletInputStream getInputStream() {
            throw new AssertionError("Header validation must not consume a credential body");
          }
        };
    request.setMethod("POST");
    request.addHeader("Content-Type", "application/json");
    request.addHeader("X-XSRF-TOKEN", "synthetic-session-token-shape-only");
    request.addHeader("Idempotency-Key", KEY);
    return request;
  }
}

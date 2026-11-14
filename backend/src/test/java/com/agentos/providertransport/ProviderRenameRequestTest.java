package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providerpolicy.PolicyFailure;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProviderRenameRequestTest {
  private static final String ORG = "71a00000-0000-4000-8000-000000000001";
  private static final String TARGET = "71a00000-0000-4000-8000-000000000002";
  private static final String KEY = "71a00000-0000-4000-8000-000000000003";
  private static final String CSRF = "X-CSRF-TOKEN";
  private static final String PATH =
      "/api/v1/organizations/" + ORG + "/provider-connections/" + TARGET + "/rename";

  @Test
  void accessCheckUsesOnlyItsExactPostPathAndTheSharedHeaderContract() {
    var request = request();
    String path = PATH.replace("/rename", "/access-checks");
    request.setRequestURI(path);
    assertThat(ProviderRenameRequest.validateAccessCheck(request, ORG, TARGET, CSRF))
        .isEqualTo(UUID.fromString(KEY));
    request.setQueryString("page=0");
    bad(() -> ProviderRenameRequest.validateAccessCheck(request, ORG, TARGET, CSRF));
    request.setQueryString(null);
    request.setRequestURI(path.replace("/access-checks", "/model-discoveries"));
    bad(() -> ProviderRenameRequest.validateAccessCheck(request, ORG, TARGET, CSRF));
  }

  @Test
  void acceptsOnlyExactRawRouteIncludingContextPathWithoutReadingBodyOrCreatingSession() {
    for (String context : new String[] {"", "/agentos"}) {
      for (String media :
          new String[] {
            "application/json",
            "application/json; charset=UTF-8",
            "Application/JSON; charset=\"UTF-8\""
          }) {
        var request = request();
        request.setContextPath(context);
        request.setRequestURI(context + PATH);
        request.removeHeader("Content-Type");
        request.addHeader("Content-Type", media);
        request.addHeader("Content-Length", "8192");
        assertThat(validate(request)).isEqualTo(UUID.fromString(KEY));
      }
    }
    var chunked = request();
    chunked.addHeader("Transfer-Encoding", "chunked");
    assertThat(validate(chunked)).isEqualTo(UUID.fromString(KEY));
  }

  @Test
  void rejectsEncodedAliasedAndMismatchedPathsBeforeBodyAccess() {
    for (String path :
        new String[] {
          PATH.replace("71a00000", "%371a00000"),
          PATH.replace(TARGET, TARGET.replace("a", "%61")),
          PATH.replace(TARGET, TARGET.toUpperCase()),
          PATH.replace(ORG, ORG.toUpperCase()),
          PATH.replace(TARGET, KEY),
          PATH + "/",
          PATH + ";parameter=1",
          PATH.replace("/rename", "/%72ename"),
          PATH.replace("/rename", "/other"),
          PATH.replace("/provider-connections/", "//provider-connections/"),
          "/different" + PATH
        }) {
      var request = request();
      request.setRequestURI(path);
      bad(() -> validate(request));
    }
    for (String target :
        new String[] {
          "",
          "1-1-4-8-1",
          "71a00000-0000-0000-8000-000000000002",
          "71a00000-0000-7000-8000-000000000002",
          "71a00000-0000-4000-7000-000000000002"
        }) {
      var request = request();
      request.setRequestURI(PATH.replace(TARGET, target));
      bad(() -> ProviderRenameRequest.validate(request, ORG, target, CSRF));
    }
    bad(() -> ProviderRenameRequest.validate(request(), null, TARGET, CSRF));
    bad(() -> ProviderRenameRequest.validate(request(), ORG, null, CSRF));
    bad(() -> ProviderRenameRequest.validate(null, ORG, TARGET, CSRF));
  }

  @Test
  void rejectsAmbiguousHeadersCoercionsFramingQueryAndNegotiation() {
    List<Consumer<MockHttpServletRequest>> changes =
        List.of(
            r -> r.setMethod("GET"),
            r -> r.setQueryString(""),
            r -> r.setQueryString("expectedRevision=1"),
            r -> r.removeHeader("Content-Type"),
            r -> r.addHeader("Content-Type", "application/json"),
            r -> replace(r, "Content-Type", "application/x-www-form-urlencoded"),
            r -> replace(r, "Content-Type", "application/json;charset=latin1"),
            r -> replace(r, "Content-Type", "application/json;charset=UTF-8;charset=UTF-8"),
            r -> r.addHeader("Content-Encoding", "identity"),
            r -> r.removeHeader(CSRF),
            r -> r.addHeader(CSRF, "duplicate"),
            r -> replace(r, CSRF, "invalid token"),
            r -> r.removeHeader("Idempotency-Key"),
            r -> r.addHeader("Idempotency-Key", KEY),
            r -> replace(r, "Idempotency-Key", KEY + "," + KEY),
            r -> replace(r, "Idempotency-Key", KEY.toUpperCase()),
            r -> replace(r, "Idempotency-Key", " " + KEY),
            r -> r.addHeader("Content-Length", "8193"),
            r -> r.addHeader("Content-Length", "0"),
            r -> r.addHeader("Content-Length", "01"),
            r -> r.addHeader("Content-Length", "-1"),
            r -> {
              r.addHeader("Content-Length", "2");
              r.addHeader("Content-Length", "2");
            },
            r -> {
              r.addHeader("Content-Length", "2");
              r.addHeader("Transfer-Encoding", "chunked");
            },
            r -> r.addHeader("Transfer-Encoding", "gzip,chunked"),
            r -> {
              r.addHeader("Transfer-Encoding", "chunked");
              r.addHeader("Transfer-Encoding", "chunked");
            },
            r -> r.addHeader("Accept", "text/html"),
            r -> r.addHeader("Accept", "application/json;q=0,*/*;q=1"),
            r -> {
              r.addHeader("Accept", "application/json");
              r.addHeader("Accept", "application/json");
            },
            r -> replace(r, "Content-Type", "application/json\r\nX-Injected:1"));
    for (var change : changes) {
      var request = request();
      change.accept(request);
      bad(() -> validate(request));
    }
  }

  @Test
  void validatesScopeAndTrustedCsrfHeaderConfigurationIndependentlyOfPathEquality() {
    var request = request();
    request.setRequestURI(PATH.replace(ORG, ORG.toUpperCase()));
    bad(() -> ProviderRenameRequest.validate(request, ORG.toUpperCase(), TARGET, CSRF));
    bad(() -> ProviderRenameRequest.validate(request(), ORG, TARGET, "invalid:header"));
    bad(() -> ProviderRenameRequest.validate(request(), ORG, TARGET, null));
  }

  private static UUID validate(MockHttpServletRequest request) {
    return ProviderRenameRequest.validate(request, ORG, TARGET, CSRF);
  }

  private static void bad(Runnable validation) {
    assertThatThrownBy(validation::run)
        .isInstanceOf(PolicyFailure.class)
        .hasMessage("BAD_REQUEST")
        .hasNoCause()
        .satisfies(failure -> assertThat(((PolicyFailure) failure).status()).isEqualTo(400));
  }

  private static void replace(MockHttpServletRequest request, String name, String value) {
    request.removeHeader(name);
    request.addHeader(name, value);
  }

  private static MockHttpServletRequest request() {
    var request =
        new MockHttpServletRequest() {
          @Override
          public ServletInputStream getInputStream() {
            throw new AssertionError("Header validation must not consume the body");
          }

          @Override
          public String getParameter(String name) {
            throw new AssertionError("Header validation must not consult form parameters");
          }

          @Override
          public HttpSession getSession(boolean create) {
            throw new AssertionError("Header validation must not consult session state");
          }

          @Override
          public HttpSession getSession() {
            throw new AssertionError("Header validation must not create a session");
          }
        };
    request.setMethod("POST");
    request.setRequestURI(PATH);
    request.addHeader("Content-Type", "application/json");
    request.addHeader(CSRF, "synthetic-header-shape-not-csrf-verification");
    request.addHeader("Idempotency-Key", KEY);
    return request;
  }
}

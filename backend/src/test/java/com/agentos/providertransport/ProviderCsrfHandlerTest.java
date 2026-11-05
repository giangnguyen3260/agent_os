package com.agentos.providertransport;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

class ProviderCsrfHandlerTest {
  @Test
  void actualSessionTokenPassesWithoutReadingSecretBodyOrParameters() throws Exception {
    var repository = new HttpSessionCsrfTokenRepository();
    var seed = request(null);
    var token = repository.generateToken(seed);
    repository.saveToken(token, seed, new MockHttpServletResponse());
    var request = request(seed.getSession(false));
    request.addHeader(token.getHeaderName(), token.getToken());
    var response = new MockHttpServletResponse();
    var passed = new AtomicInteger();
    filter(repository).doFilter(request, response, (req, res) -> passed.incrementAndGet());
    assertThat(passed.get()).isEqualTo(1);
  }

  @Test
  void missingWrongDuplicateAndFoldedTokensFailBeforeBodyConsumption() throws Exception {
    var repository = new HttpSessionCsrfTokenRepository();
    var seed = request(null);
    var token = repository.generateToken(seed);
    repository.saveToken(token, seed, new MockHttpServletResponse());
    for (List<String> headers :
        List.of(
            List.<String>of(),
            List.of("wrong-token"),
            List.of(token.getToken(), token.getToken()),
            List.of(token.getToken() + "," + token.getToken()),
            List.of(" " + token.getToken()),
            List.of(token.getToken() + "\r\nInjected:1"))) {
      var request = request(seed.getSession(false));
      headers.forEach(value -> request.addHeader(token.getHeaderName(), value));
      var response = new MockHttpServletResponse();
      var passed = new AtomicInteger();
      filter(repository).doFilter(request, response, (req, res) -> passed.incrementAndGet());
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(passed.get()).isZero();
    }
  }

  @Test
  void tokenFromAnotherSessionCannotAuthorizeCommand() throws Exception {
    var repository = new HttpSessionCsrfTokenRepository();
    var first = request(null);
    var second = request(null);
    var firstToken = repository.generateToken(first);
    var secondToken = repository.generateToken(second);
    repository.saveToken(firstToken, first, new MockHttpServletResponse());
    repository.saveToken(secondToken, second, new MockHttpServletResponse());
    var request = request(second.getSession(false));
    request.addHeader(firstToken.getHeaderName(), firstToken.getToken());
    var response = new MockHttpServletResponse();
    filter(repository)
        .doFilter(
            request,
            response,
            (req, res) -> {
              throw new AssertionError("Foreign session token must not pass");
            });
    assertThat(response.getStatus()).isEqualTo(403);
  }

  private static CsrfFilter filter(HttpSessionCsrfTokenRepository repository) {
    var filter = new CsrfFilter(repository);
    filter.setRequestHandler(new ProviderCsrfHandler());
    return filter;
  }

  private static MockHttpServletRequest request(HttpSession session) {
    var request =
        new MockHttpServletRequest() {
          @Override
          public ServletInputStream getInputStream() {
            throw new AssertionError("CSRF must not read a provider credential body");
          }

          @Override
          public String getParameter(String name) {
            throw new AssertionError("No form fallback for provider CSRF");
          }

          @Override
          public Map<String, String[]> getParameterMap() {
            throw new AssertionError("No form fallback for provider CSRF");
          }
        };
    request.setMethod("POST");
    request.setRequestURI(
        "/api/v1/organizations/11111111-1111-4111-8111-111111111111/provider-connections");
    if (session != null) request.setSession((org.springframework.mock.web.MockHttpSession) session);
    return request;
  }
}

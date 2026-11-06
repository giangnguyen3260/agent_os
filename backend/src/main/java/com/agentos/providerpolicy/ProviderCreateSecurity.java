package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providertransport.ProviderCreateBody;
import com.agentos.providertransport.ProviderCreateRequest;
import com.agentos.providertransport.ProviderCreateResponses;
import com.agentos.providertransport.ProviderCsrfHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opt-in Create transport. The deployment must configure a bounded connector upload/read timeout
 * before enabling the chain with a real coordinator.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-create.enabled", havingValue = "true")
public class ProviderCreateSecurity {
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final Pattern COLLECTION =
      Pattern.compile("/api/v1/organizations/([^/]+)/provider-connections");

  @Bean
  @Order(-1)
  SecurityFilterChain providerCreateChain(
      HttpSecurity http,
      PolicyAuthority authority,
      ProviderCreateCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    var tokens = new HttpSessionCsrfTokenRepository();
    tokens.setHeaderName(CSRF_HEADER);
    http.securityMatcher(
            request -> "POST".equals(request.getMethod()) && organization(request) != null)
        .securityContext(
            context ->
                context.securityContextRepository(new HttpSessionSecurityContextRepository()))
        .requestCache(cache -> cache.disable())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(tokens).csrfTokenRequestHandler(new ProviderCsrfHandler()))
        .exceptionHandling(
            errors ->
                errors.accessDeniedHandler(
                    (request, response, failure) ->
                        OptionsResponses.error(json, request, response, PolicyFailure.forbidden())))
        .addFilterBefore(new CreateAuthority(authority, json), CsrfFilter.class)
        .addFilterAfter(new CreateCommand(coordinator, json), CsrfFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
    return http.build();
  }

  private static String organization(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    var match = COLLECTION.matcher(path);
    return match.matches() ? match.group(1) : null;
  }

  private static HumanIdentity human() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof HumanIdentity identity) {
      return identity;
    }
    throw new PolicyFailure(401, "UNAUTHORIZED");
  }

  private static final class CreateAuthority extends OncePerRequestFilter {
    private final PolicyAuthority authority;
    private final ObjectMapper json;

    CreateAuthority(PolicyAuthority authority, ObjectMapper json) {
      this.authority = authority;
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      try {
        HumanIdentity identity = human();
        if (!authority.current(identity)) {
          var existing = request.getSession(false);
          if (existing != null) existing.invalidate();
          SecurityContextHolder.clearContext();
          throw new PolicyFailure(401, "UNAUTHORIZED");
        }
        UUID org;
        try {
          org = UUID.fromString(organization(request));
        } catch (IllegalArgumentException failure) {
          throw PolicyFailure.forbidden();
        }
        authority.require(identity, org);
      } catch (PolicyFailure failure) {
        OptionsResponses.error(json, request, response, failure);
        return;
      } catch (RuntimeException failure) {
        OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
        return;
      }
      chain.doFilter(request, response);
    }
  }

  private static final class CreateCommand extends OncePerRequestFilter {
    private final ProviderCreateCoordinator coordinator;
    private final ObjectMapper json;
    // Bound simultaneous credential reads and preparations per API instance without queueing.
    private final Semaphore submissions = new Semaphore(8);

    CreateCommand(ProviderCreateCoordinator coordinator, ObjectMapper json) {
      this.coordinator = coordinator;
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException {
      String organization = organization(request);
      UUID key;
      try {
        key = ProviderCreateRequest.validate(request, organization, CSRF_HEADER);
      } catch (PolicyFailure failure) {
        OptionsResponses.error(json, request, response, failure);
        return;
      } catch (RuntimeException failure) {
        OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
        return;
      }
      if (!submissions.tryAcquire()) {
        OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
        return;
      }
      WriteOutcome outcome;
      try {
        ProviderCreateBody body;
        try {
          body = ProviderCreateBody.read(request.getInputStream());
        } catch (IOException | IllegalArgumentException failure) {
          OptionsResponses.error(json, request, response, new PolicyFailure(400, "BAD_REQUEST"));
          return;
        } catch (RuntimeException failure) {
          OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
          return;
        }
        try (body) {
          outcome = coordinator.create(human(), UUID.fromString(organization), key, body);
        } catch (RuntimeException failure) {
          OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
          return;
        }
      } finally {
        submissions.release();
      }
      ProviderCreateResponses.writeOutcome(json, response, outcome);
    }
  }
}

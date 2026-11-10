package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providerrename.ProviderRenameCoordinator;
import com.agentos.providertransport.ProviderCsrfHandler;
import com.agentos.providertransport.ProviderRenameBody;
import com.agentos.providertransport.ProviderRenameRequest;
import com.agentos.providertransport.ProviderRenameResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
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

/** Opt-in Rename ingress; deployment must enforce a bounded connector upload/read timeout. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rename.http-enabled", havingValue = "true")
public class ProviderRenameSecurity {
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final Pattern ROUTE =
      Pattern.compile("/api/v1/organizations/([^/]+)/provider-connections/([^/]+)/rename");

  @Bean
  @Order(-2)
  SecurityFilterChain providerRenameChain(
      HttpSecurity http,
      PolicyAuthority authority,
      ProviderRenameCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    var tokens = new HttpSessionCsrfTokenRepository();
    tokens.setHeaderName(CSRF_HEADER);
    http.securityMatcher(request -> "POST".equals(request.getMethod()) && route(request).matches())
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
        .addFilterBefore(new RenameAuthority(authority, json), CsrfFilter.class)
        .addFilterAfter(new RenameCommand(coordinator, json), CsrfFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
    return http.build();
  }

  private static Matcher route(HttpServletRequest request) {
    return ROUTE.matcher(request.getRequestURI().substring(request.getContextPath().length()));
  }

  private static HumanIdentity human() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof HumanIdentity identity) return identity;
    throw new PolicyFailure(401, "UNAUTHORIZED");
  }

  private static final class RenameAuthority extends OncePerRequestFilter {
    private final PolicyAuthority authority;
    private final ObjectMapper json;

    RenameAuthority(PolicyAuthority authority, ObjectMapper json) {
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
        var path = route(request);
        UUID org;
        try {
          if (!path.matches()) throw PolicyFailure.forbidden();
          org = UUID.fromString(path.group(1));
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

  private static final class RenameCommand extends OncePerRequestFilter {
    private final ProviderRenameCoordinator coordinator;
    private final ObjectMapper json;
    private final Semaphore submissions = new Semaphore(8);

    RenameCommand(ProviderRenameCoordinator coordinator, ObjectMapper json) {
      this.coordinator = coordinator;
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException {
      UUID key;
      UUID organization;
      UUID target;
      try {
        var path = route(request);
        if (!path.matches()) throw new PolicyFailure(400, "BAD_REQUEST");
        key = ProviderRenameRequest.validate(request, path.group(1), path.group(2), CSRF_HEADER);
        organization = UUID.fromString(path.group(1));
        target = UUID.fromString(path.group(2));
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
        ProviderRenameBody body;
        try {
          body = ProviderRenameBody.read(target, request.getInputStream());
        } catch (IOException | IllegalArgumentException failure) {
          OptionsResponses.error(json, request, response, new PolicyFailure(400, "BAD_REQUEST"));
          return;
        } catch (RuntimeException failure) {
          OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
          return;
        }
        try {
          outcome = coordinator.rename(human(), organization, key, body.command());
        } catch (RuntimeException failure) {
          OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
          return;
        }
      } finally {
        submissions.release();
      }
      ProviderRenameResponses.writeOutcome(json, response, outcome);
    }
  }
}

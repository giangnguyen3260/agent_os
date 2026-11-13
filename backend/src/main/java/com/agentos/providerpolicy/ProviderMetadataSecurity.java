package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.agentos.providertransport.ProviderCsrfHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared ingress for metadata commands with the same authority and body limits. */
final class ProviderMetadataSecurity {
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";

  private ProviderMetadataSecurity() {}

  interface RequestValidator {
    UUID validate(
        HttpServletRequest request, String organization, String target, String csrfHeader);
  }

  interface BodyDecoder<C> {
    C read(UUID target, InputStream input);
  }

  interface Command<C> {
    WriteOutcome run(HumanIdentity actor, UUID organization, UUID key, C command);
  }

  interface OutcomeWriter {
    void write(ObjectMapper json, HttpServletResponse response, WriteOutcome outcome)
        throws IOException;
  }

  static <C> SecurityFilterChain build(
      HttpSecurity http,
      PolicyAuthority authority,
      ObjectMapper json,
      String suffix,
      RequestValidator validator,
      BodyDecoder<C> decoder,
      Command<C> command,
      OutcomeWriter writer)
      throws Exception {
    var route =
        Pattern.compile(
            "/api/v1/organizations/([^/]+)/provider-connections/([^/]+)/" + Pattern.quote(suffix));
    var tokens = new HttpSessionCsrfTokenRepository();
    tokens.setHeaderName(CSRF_HEADER);
    http.securityMatcher(
            request -> "POST".equals(request.getMethod()) && route(route, request).matches())
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
        .addFilterBefore(new MetadataAuthority(route, authority, json), CsrfFilter.class)
        .addFilterAfter(
            new MetadataCommand<>(route, validator, decoder, command, writer, json),
            CsrfFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
    return http.build();
  }

  private static Matcher route(Pattern route, HttpServletRequest request) {
    return route.matcher(request.getRequestURI().substring(request.getContextPath().length()));
  }

  private static HumanIdentity human() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof HumanIdentity identity) return identity;
    throw new PolicyFailure(401, "UNAUTHORIZED");
  }

  private static final class MetadataAuthority extends OncePerRequestFilter {
    private final Pattern route;
    private final PolicyAuthority authority;
    private final ObjectMapper json;

    MetadataAuthority(Pattern route, PolicyAuthority authority, ObjectMapper json) {
      this.route = route;
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
        var path = route(route, request);
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

  private static final class MetadataCommand<C> extends OncePerRequestFilter {
    private final Pattern route;
    private final RequestValidator validator;
    private final BodyDecoder<C> decoder;
    private final Command<C> command;
    private final OutcomeWriter writer;
    private final ObjectMapper json;
    private final Semaphore submissions = new Semaphore(8);

    MetadataCommand(
        Pattern route,
        RequestValidator validator,
        BodyDecoder<C> decoder,
        Command<C> command,
        OutcomeWriter writer,
        ObjectMapper json) {
      this.route = route;
      this.validator = validator;
      this.decoder = decoder;
      this.command = command;
      this.writer = writer;
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException {
      UUID key;
      UUID organization;
      UUID target;
      HumanIdentity actor;
      try {
        var path = route(route, request);
        if (!path.matches()) throw new PolicyFailure(400, "BAD_REQUEST");
        key = validator.validate(request, path.group(1), path.group(2), CSRF_HEADER);
        organization = UUID.fromString(path.group(1));
        target = UUID.fromString(path.group(2));
        actor = human();
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
        C body;
        try {
          body = decoder.read(target, request.getInputStream());
        } catch (IOException | IllegalArgumentException failure) {
          OptionsResponses.error(json, request, response, new PolicyFailure(400, "BAD_REQUEST"));
          return;
        } catch (RuntimeException failure) {
          OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
          return;
        }
        try {
          outcome = command.run(actor, organization, key, body);
        } catch (RuntimeException failure) {
          OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
          return;
        }
      } finally {
        submissions.release();
      }
      writer.write(json, response, outcome);
    }
  }
}

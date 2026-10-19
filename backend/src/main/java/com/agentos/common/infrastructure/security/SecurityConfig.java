package com.agentos.common.infrastructure.security;

import com.agentos.auth.HumanAuthenticationProvider;
import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, HumanAuthenticationProvider provider, JdbcTemplate jdbc, ObjectMapper json)
      throws Exception {
    http.authenticationProvider(provider)
        .addFilterAfter(
            new org.springframework.web.filter.OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  jakarta.servlet.http.HttpServletRequest request,
                  HttpServletResponse response,
                  jakarta.servlet.FilterChain chain)
                  throws jakarta.servlet.ServletException, java.io.IOException {
                var authentication =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
                if (authentication != null
                    && authentication.getPrincipal() instanceof HumanIdentity human
                    && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                            "select count(*) > 0 from human_accounts where id = ? and enabled ="
                                + " true and credential_version = ?",
                            Boolean.class,
                            human.id(),
                            human.credentialVersion()))) {
                  var existing = request.getSession(false);
                  if (existing != null) existing.invalidate();
                  org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
                chain.doFilter(request, response);
              }
            },
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/api/v1/auth/recovery/request",
                        "/api/v1/auth/recovery/confirm",
                        "/actuator/health",
                        "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/v1/organizations", "/api/v1/organizations/")
                    .denyAll()
                    .requestMatchers("/api/v1/organizations/**")
                    .access(
                        (authentication, context) -> {
                          var current = authentication.get();
                          if (!(current.getPrincipal() instanceof HumanIdentity human))
                            return new AuthorizationDecision(false);
                          try {
                            UUID organization =
                                UUID.fromString(context.getRequest().getRequestURI().split("/")[4]);
                            String method = context.getRequest().getMethod();
                            boolean read =
                                method.equals("GET")
                                    || method.equals("HEAD")
                                    || method.equals("OPTIONS");
                            return new AuthorizationDecision(
                                Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                        "select count(*) > 0 from organization_memberships m join"
                                            + " human_accounts a on a.id = m.account_id where"
                                            + " m.account_id = ? and m.organization_id = ? and"
                                            + " a.enabled = true"
                                            + (read ? "" : " and m.role = 'ADMIN'"),
                                        Boolean.class,
                                        human.id(),
                                        organization)));
                          } catch (IllegalArgumentException ex) {
                            return new AuthorizationDecision(false);
                          }
                        })
                    .requestMatchers("/api/v1/auth/session", "/api/v1/auth/workspaces")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            write(
                                json,
                                response,
                                401,
                                ApiErrorResponse.of("UNAUTHORIZED", "Authentication required")))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            write(
                                json,
                                response,
                                request.getUserPrincipal() == null
                                        && !(exception
                                            instanceof
                                            org.springframework.security.web.csrf.CsrfException)
                                    ? 401
                                    : 403,
                                ApiErrorResponse.of(
                                    request.getUserPrincipal() == null
                                            && !(exception
                                                instanceof
                                                org.springframework.security.web.csrf.CsrfException)
                                        ? "UNAUTHORIZED"
                                        : "FORBIDDEN",
                                    "Request denied"))))
        .formLogin(
            form ->
                form.loginProcessingUrl("/api/v1/auth/login")
                    .successHandler(
                        (request, response, authentication) ->
                            write(
                                json, response, 200, ApiResponse.ok(authentication.getPrincipal())))
                    .failureHandler(
                        (request, response, exception) ->
                            write(
                                json,
                                response,
                                401,
                                ApiErrorResponse.of(
                                    "INVALID_CREDENTIALS", "Invalid email or password"))))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> {
                          if (authentication != null
                              && authentication.getPrincipal() instanceof HumanIdentity human)
                            jdbc.update(
                                "insert into authentication_audit(id, account_id, event_type)"
                                    + " values (?, ?, 'LOGOUT')",
                                UUID.randomUUID(),
                                human.id());
                          response.setStatus(204);
                        }))
        .requestCache(cache -> cache.disable())
        .sessionManagement(
            session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
    return http.build();
  }

  private static void write(
      ObjectMapper json, HttpServletResponse response, int status, Object body)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    json.writeValue(response.getOutputStream(), body);
  }
}

package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class OptionsGate extends OncePerRequestFilter {
  private static final String ADMITTED = OptionsGate.class.getName() + ".admitted";
  private final PolicyAuthority authority;
  private final AdmissionService admission;
  private final ObjectMapper json;

  OptionsGate(PolicyAuthority authority, AdmissionService admission, ObjectMapper json) {
    this.authority = authority;
    this.admission = admission;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    try {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null
          || !authentication.isAuthenticated()
          || !(authentication.getPrincipal() instanceof HumanIdentity))
        throw new PolicyFailure(401, "UNAUTHORIZED");
      var human = (HumanIdentity) authentication.getPrincipal();
      if (!authority.current(human)) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        throw new PolicyFailure(401, "UNAUTHORIZED");
      }
      UUID org;
      try {
        org =
            UUID.fromString(
                request.getRequestURI().substring(request.getContextPath().length()).split("/")[4]);
      } catch (IllegalArgumentException failure) {
        throw PolicyFailure.forbidden();
      }
      authority.require(human, org);
      if (!request.getMethod().equals("GET")) throw new PolicyFailure(405, "METHOD_NOT_ALLOWED");
      admission.acquire(human, org);
    } catch (PolicyFailure failure) {
      OptionsResponses.error(json, request, response, failure);
      return;
    } catch (RuntimeException failure) {
      OptionsResponses.error(json, request, response, PolicyFailure.unavailable());
      return;
    }
    request.setAttribute(ADMITTED, request.getRequestURI());
    chain.doFilter(request, response);
  }

  static boolean admitted(HttpServletRequest request) {
    return request.getRequestURI().equals(request.getAttribute(ADMITTED));
  }
}

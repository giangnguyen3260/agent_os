package com.agentos.taskowner;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Fresh ADMIN gate before strict parsing, including GET and receipt recovery. */
@Configuration
public class TaskOwnerSecurity implements WebMvcConfigurer {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public TaskOwnerSecurity(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler)
                  throws Exception {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                HumanIdentity human =
                    auth != null && auth.getPrincipal() instanceof HumanIdentity h ? h : null;
                boolean allowed = false;
                if (human != null) {
                  try {
                    UUID org = UUID.fromString(request.getRequestURI().split("/")[4]);
                    allowed =
                        Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                "select count(*) > 0 from organization_memberships m join human_accounts a on a.id=m.account_id where m.organization_id=? and m.account_id=? and m.role='ADMIN' and a.enabled=true and a.credential_version=?",
                                Boolean.class,
                                org,
                                human.id(),
                                human.credentialVersion()));
                  } catch (IllegalArgumentException ex) {
                    /* Match existing transport scope denial. */
                  }
                }
                if (allowed) return true;
                response.setStatus(human == null ? 401 : 403);
                response.setContentType("application/json");
                json.writeValue(
                    response.getOutputStream(),
                    ApiErrorResponse.of(
                        human == null ? "UNAUTHORIZED" : "FORBIDDEN", "Request denied"));
                return false;
              }
            })
        .addPathPatterns(
            "/api/v1/organizations/*/teams/*/missions/*/tasks/*/draft-owner",
            "/api/v1/organizations/*/teams/*/missions/*/tasks/*/draft-owner/**");
  }
}

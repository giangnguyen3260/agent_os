package com.agentos.auth;

import com.agentos.common.api.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final JdbcTemplate jdbc;

  public AuthController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/csrf")
  public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
    return ApiResponse.ok(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
  }

  @GetMapping("/session")
  public ApiResponse<HumanIdentity> session(@AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(human);
  }

  @GetMapping("/workspaces")
  public ApiResponse<List<Workspace>> workspaces(@AuthenticationPrincipal HumanIdentity human) {
    return ApiResponse.ok(
        jdbc.query(
            "select o.id, o.name, m.role from organizations o join organization_memberships m on"
                + " o.id = m.organization_id where m.account_id = ? order by o.name, o.id",
            (rs, row) ->
                new Workspace(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role")),
            human.id()));
  }

  @GetMapping("/workspaces/{id}")
  public ApiResponse<Workspace> workspace(
      @AuthenticationPrincipal HumanIdentity human, @PathVariable UUID id) {
    return ApiResponse.ok(
        jdbc.query(
                "select o.id, o.name, m.role from organizations o join organization_memberships m on"
                    + " o.id = m.organization_id where m.account_id = ? and o.id = ?",
                (rs, row) ->
                    new Workspace(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role")),
                human.id(),
                id)
            .stream()
            .findFirst()
            .orElseThrow(() -> new EntityNotFoundException("Workspace not found")));
  }

  public record Workspace(UUID id, String name, String role) {}
}

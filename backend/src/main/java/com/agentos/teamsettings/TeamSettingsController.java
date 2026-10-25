package com.agentos.teamsettings;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/settings")
public class TeamSettingsController {
  private final TeamSettingsService service;

  public TeamSettingsController(TeamSettingsService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<TeamSettingsDtos.Context> context(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) {
    query(request, Set.of());
    return ApiResponse.ok(
        service.context(
            TeamSettingsWire.uuid(organizationId), TeamSettingsWire.uuid(teamId), human));
  }

  @GetMapping("/candidates")
  public ApiResponse<TeamSettingsDtos.Candidates> candidates(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) {
    query(request, Set.of("page", "query"));
    String page = request.getParameter("page");
    if (page != null && !page.matches("0|[1-9][0-9]{0,5}")) throw TeamSettingsFailure.bad();
    int number = page == null ? 0 : Integer.parseInt(page);
    return ApiResponse.ok(
        service.candidates(
            TeamSettingsWire.uuid(organizationId),
            TeamSettingsWire.uuid(teamId),
            human,
            number,
            request.getParameter("query")));
  }

  @GetMapping("/receipts/{key}")
  public ApiResponse<TeamSettingsDtos.Receipt> receipt(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) {
    query(request, Set.of());
    return ApiResponse.ok(
        service.receipt(
            TeamSettingsWire.uuid(organizationId),
            TeamSettingsWire.uuid(teamId),
            human,
            TeamSettingsWire.uuid(key)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TeamSettingsDtos.Receipt>> update(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request, Set.of());
    try {
      MediaType type =
          request.getContentType() == null
              ? null
              : MediaType.parseMediaType(request.getContentType());
      if (type == null
          || !"application".equals(type.getType())
          || !"json".equals(type.getSubtype())
          || (type.getCharset() != null
              && !java.nio.charset.StandardCharsets.UTF_8.equals(type.getCharset())))
        throw TeamSettingsFailure.bad();
    } catch (org.springframework.http.InvalidMediaTypeException ex) {
      throw TeamSettingsFailure.bad();
    }
    var headers = Collections.list(request.getHeaders("Idempotency-Key"));
    if (headers.size() != 1) throw TeamSettingsFailure.bad();
    UUID key = TeamSettingsWire.uuid(headers.get(0));
    var command = TeamSettingsWire.read(request.getInputStream());
    var result =
        service.update(
            TeamSettingsWire.uuid(organizationId),
            TeamSettingsWire.uuid(teamId),
            human,
            key,
            command);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.receipt()));
  }

  private static void query(HttpServletRequest request, Set<String> allowed) {
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (!allowed.contains(key) || values.length != 1) throw TeamSettingsFailure.bad();
            });
    if (!"POST".equals(request.getMethod())
        && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null))
      throw TeamSettingsFailure.bad();
  }
}

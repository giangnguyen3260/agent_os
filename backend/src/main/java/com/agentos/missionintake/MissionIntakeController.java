package com.agentos.missionintake;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
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
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/task-intake")
public class MissionIntakeController {
  private final MissionIntakeService service;

  public MissionIntakeController(MissionIntakeService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<MissionIntakeDtos.Context> context(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request);
    return ApiResponse.ok(
        service.context(
            MissionIntakeWire.uuid(organizationId),
            MissionIntakeWire.uuid(teamId),
            MissionIntakeWire.uuid(missionId),
            human));
  }

  @GetMapping("/receipts/{key}")
  public ApiResponse<MissionIntakeDtos.ReceiptLookup> receipt(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request);
    return ApiResponse.ok(
        new MissionIntakeDtos.ReceiptLookup(
            service.receipt(
                MissionIntakeWire.uuid(organizationId),
                MissionIntakeWire.uuid(teamId),
                MissionIntakeWire.uuid(missionId),
                human,
                MissionIntakeWire.uuid(key))));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<MissionIntakeDtos.Receipt>> update(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request);
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
        throw MissionIntakeFailure.bad();
    } catch (org.springframework.http.InvalidMediaTypeException ex) {
      throw MissionIntakeFailure.bad();
    }
    var headers = Collections.list(request.getHeaders("Idempotency-Key"));
    if (headers.size() != 1) throw MissionIntakeFailure.bad();
    UUID key = MissionIntakeWire.uuid(headers.get(0));
    var command = MissionIntakeWire.read(request.getInputStream());
    var result =
        service.update(
            MissionIntakeWire.uuid(organizationId),
            MissionIntakeWire.uuid(teamId),
            MissionIntakeWire.uuid(missionId),
            human,
            key,
            command);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.receipt()));
  }

  private static void query(HttpServletRequest request) throws IOException {
    if (request.getQueryString() != null) throw MissionIntakeFailure.bad();
    if (!"POST".equals(request.getMethod()) && request.getInputStream().read() != -1)
      throw MissionIntakeFailure.bad();
  }
}

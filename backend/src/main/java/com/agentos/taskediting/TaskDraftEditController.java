package com.agentos.taskediting;

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
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks/{taskId}/draft-settings")
public class TaskDraftEditController {
  private final TaskDraftEditService service;

  public TaskDraftEditController(TaskDraftEditService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<TaskDraftEditDtos.Context> context(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) {
    query(request, Set.of());
    return ApiResponse.ok(
        service.context(
            TaskDraftEditWire.uuid(organizationId),
            TaskDraftEditWire.uuid(teamId),
            TaskDraftEditWire.uuid(missionId),
            TaskDraftEditWire.uuid(taskId),
            human));
  }

  @GetMapping("/receipts/{key}")
  public ApiResponse<TaskDraftEditDtos.ReceiptLookup> receipt(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) {
    query(request, Set.of());
    return ApiResponse.ok(
        new TaskDraftEditDtos.ReceiptLookup(
            service.receipt(
                TaskDraftEditWire.uuid(organizationId),
                TaskDraftEditWire.uuid(teamId),
                TaskDraftEditWire.uuid(missionId),
                TaskDraftEditWire.uuid(taskId),
                human,
                TaskDraftEditWire.uuid(key))));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TaskDraftEditDtos.Receipt>> update(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
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
        throw TaskDraftEditFailure.bad();
    } catch (org.springframework.http.InvalidMediaTypeException ex) {
      throw TaskDraftEditFailure.bad();
    }
    var headers = Collections.list(request.getHeaders("Idempotency-Key"));
    if (headers.size() != 1) throw TaskDraftEditFailure.bad();
    UUID key = TaskDraftEditWire.uuid(headers.get(0));
    var command = TaskDraftEditWire.read(request.getInputStream());
    var result =
        service.update(
            TaskDraftEditWire.uuid(organizationId),
            TaskDraftEditWire.uuid(teamId),
            TaskDraftEditWire.uuid(missionId),
            TaskDraftEditWire.uuid(taskId),
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
              if (!allowed.contains(key) || values.length != 1) throw TaskDraftEditFailure.bad();
            });
    if (!"POST".equals(request.getMethod())
        && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null))
      throw TaskDraftEditFailure.bad();
  }
}

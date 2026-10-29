package com.agentos.taskowner;

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
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks/{taskId}/draft-owner")
public class TaskOwnerController {
  private final TaskOwnerService service;

  public TaskOwnerController(TaskOwnerService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<TaskOwnerDtos.Context> context(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) throws IOException {
    query(request, Set.of());
    return ApiResponse.ok(
        service.context(
            TaskOwnerWire.uuid(organizationId),
            TaskOwnerWire.uuid(teamId),
            TaskOwnerWire.uuid(missionId),
            TaskOwnerWire.uuid(taskId),
            human));
  }

  @GetMapping("/eligible-owners")
  public ApiResponse<TaskOwnerDtos.Candidates> owners(
      @PathVariable String organizationId, @PathVariable String teamId,
      @PathVariable String missionId, @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human, HttpServletRequest request) throws IOException {
    query(request,Set.of("page","query"));
    return ApiResponse.ok(service.owners(TaskOwnerWire.uuid(organizationId),TaskOwnerWire.uuid(teamId),
        TaskOwnerWire.uuid(missionId),TaskOwnerWire.uuid(taskId),human,
        TaskOwnerWire.page(request.getParameter("page")),TaskOwnerWire.query(request.getParameter("query"))));
  }

  @GetMapping("/eligible-owners/{agentId}")
  public ApiResponse<TaskOwnerDtos.Candidate> candidate(
      @PathVariable String organizationId, @PathVariable String teamId,
      @PathVariable String missionId, @PathVariable String taskId, @PathVariable String agentId,
      @AuthenticationPrincipal HumanIdentity human, HttpServletRequest request) throws IOException {
    query(request,Set.of());
    return ApiResponse.ok(service.candidate(TaskOwnerWire.uuid(organizationId),TaskOwnerWire.uuid(teamId),
        TaskOwnerWire.uuid(missionId),TaskOwnerWire.uuid(taskId),human,TaskOwnerWire.uuid(agentId)));
  }

  @GetMapping("/receipts/{key}")
  public ApiResponse<TaskOwnerDtos.ReceiptLookup> receipt(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request) throws IOException {
    query(request, Set.of());
    return ApiResponse.ok(
        new TaskOwnerDtos.ReceiptLookup(
            service.receipt(
                TaskOwnerWire.uuid(organizationId),
                TaskOwnerWire.uuid(teamId),
                TaskOwnerWire.uuid(missionId),
                TaskOwnerWire.uuid(taskId),
                human,
                TaskOwnerWire.uuid(key))));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TaskOwnerDtos.Receipt>> update(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request, Set.of());
    if (Collections.list(request.getHeaders("Content-Type")).size() != 1)
      throw TaskOwnerFailure.bad();
    try {
      MediaType type =
          request.getContentType() == null
              ? null
              : MediaType.parseMediaType(request.getContentType());
      if (type == null
          || !"application".equals(type.getType())
          || !"json".equals(type.getSubtype())
          || type.getParameters().keySet().stream().anyMatch(name -> !"charset".equalsIgnoreCase(name))
          || (type.getCharset() != null
              && !java.nio.charset.StandardCharsets.UTF_8.equals(type.getCharset())))
        throw TaskOwnerFailure.bad();
    } catch (org.springframework.http.InvalidMediaTypeException ex) {
      throw TaskOwnerFailure.bad();
    }
    var headers = Collections.list(request.getHeaders("Idempotency-Key"));
    if (headers.size() != 1) throw TaskOwnerFailure.bad();
    UUID key = TaskOwnerWire.uuid(headers.get(0));
    var command = TaskOwnerWire.read(request.getInputStream());
    var result =
        service.update(
            TaskOwnerWire.uuid(organizationId),
            TaskOwnerWire.uuid(teamId),
            TaskOwnerWire.uuid(missionId),
            TaskOwnerWire.uuid(taskId),
            human,
            key,
            command);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.receipt()));
  }

  private static void query(HttpServletRequest request, Set<String> allowed) throws IOException {
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (!allowed.contains(key) || values.length != 1) throw TaskOwnerFailure.bad();
            });
    if (!"POST".equals(request.getMethod())
        && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null || request.getInputStream().read() != -1))
      throw TaskOwnerFailure.bad();
  }
}

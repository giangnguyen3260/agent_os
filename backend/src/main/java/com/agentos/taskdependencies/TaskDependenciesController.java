package com.agentos.taskdependencies;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/tasks/{taskId}/draft-dependencies")
public class TaskDependenciesController {
  private final TaskDependenciesService service;

  public TaskDependenciesController(TaskDependenciesService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<TaskDependenciesDtos.Context> context(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request, Set.of());
    return ApiResponse.ok(
        service.context(
            TaskDependenciesWire.uuid(organizationId),
            TaskDependenciesWire.uuid(teamId),
            TaskDependenciesWire.uuid(missionId),
            TaskDependenciesWire.uuid(taskId),
            human));
  }

  @GetMapping("/eligible-dependencies")
  public ApiResponse<TaskDependenciesDtos.Candidates> dependencies(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    var parameters = query(request, Set.of("page", "query"));
    return ApiResponse.ok(
        service.dependencies(
            TaskDependenciesWire.uuid(organizationId),
            TaskDependenciesWire.uuid(teamId),
            TaskDependenciesWire.uuid(missionId),
            TaskDependenciesWire.uuid(taskId),
            human,
            TaskDependenciesWire.page(parameters.get("page")),
            TaskDependenciesWire.query(parameters.get("query"))));
  }

  @GetMapping("/eligible-dependencies/{dependencyId}")
  public ApiResponse<TaskDependenciesDtos.Candidate> candidate(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @PathVariable String dependencyId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request, Set.of());
    return ApiResponse.ok(
        service.candidate(
            TaskDependenciesWire.uuid(organizationId),
            TaskDependenciesWire.uuid(teamId),
            TaskDependenciesWire.uuid(missionId),
            TaskDependenciesWire.uuid(taskId),
            human,
            TaskDependenciesWire.uuid(dependencyId)));
  }

  @GetMapping("/receipts/{key}")
  public ApiResponse<TaskDependenciesDtos.ReceiptLookup> receipt(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request, Set.of());
    return ApiResponse.ok(
        new TaskDependenciesDtos.ReceiptLookup(
            service.receipt(
                TaskDependenciesWire.uuid(organizationId),
                TaskDependenciesWire.uuid(teamId),
                TaskDependenciesWire.uuid(missionId),
                TaskDependenciesWire.uuid(taskId),
                human,
                TaskDependenciesWire.uuid(key))));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TaskDependenciesDtos.Receipt>> update(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String missionId,
      @PathVariable String taskId,
      @AuthenticationPrincipal HumanIdentity human,
      HttpServletRequest request)
      throws IOException {
    query(request, Set.of());
    if (Collections.list(request.getHeaders("Content-Type")).size() != 1)
      throw TaskDependenciesFailure.bad();
    if (request.getContentType() == null
        || !request
            .getContentType()
            .matches(
                "(?i)application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:UTF-8|\"UTF-8\"))?[ "
                    + "\t]*")) throw TaskDependenciesFailure.bad();
    var headers = Collections.list(request.getHeaders("Idempotency-Key"));
    if (headers.size() != 1) throw TaskDependenciesFailure.bad();
    UUID key = TaskDependenciesWire.uuid(headers.get(0));
    var command =
        TaskDependenciesWire.read(request.getInputStream(), TaskDependenciesWire.uuid(taskId));
    var result =
        service.update(
            TaskDependenciesWire.uuid(organizationId),
            TaskDependenciesWire.uuid(teamId),
            TaskDependenciesWire.uuid(missionId),
            TaskDependenciesWire.uuid(taskId),
            human,
            key,
            command);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .body(ApiResponse.ok(result.receipt()));
  }

  private static Map<String, String> query(HttpServletRequest request, Set<String> allowed)
      throws IOException {
    var parameters = new HashMap<String, String>();
    String raw = request.getQueryString();
    if (raw != null && !raw.isEmpty()) {
      for (String pair : raw.split("&", -1)) {
        int separator = pair.indexOf('=');
        String name = decode(separator < 0 ? pair : pair.substring(0, separator));
        String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
        if (!allowed.contains(name) || parameters.putIfAbsent(name, value) != null)
          throw TaskDependenciesFailure.bad();
      }
    }
    if (!"POST".equals(request.getMethod())
        && (request.getContentLengthLong() > 0
            || request.getHeader("Transfer-Encoding") != null
            || request.getInputStream().read() != -1)) throw TaskDependenciesFailure.bad();
    return parameters;
  }

  private static String decode(String value) {
    var bytes = new ByteArrayOutputStream();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '%') {
        if (i + 2 >= value.length()) throw TaskDependenciesFailure.bad();
        char highChar = value.charAt(++i), lowChar = value.charAt(++i);
        if (highChar > 127 || lowChar > 127) throw TaskDependenciesFailure.bad();
        int high = Character.digit(highChar, 16);
        int low = Character.digit(lowChar, 16);
        if (high < 0 || low < 0) throw TaskDependenciesFailure.bad();
        bytes.write(high * 16 + low);
      } else {
        if (c > 127) throw TaskDependenciesFailure.bad();
        bytes.write(c == '+' ? ' ' : c);
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException ex) {
      throw TaskDependenciesFailure.bad();
    }
  }
}

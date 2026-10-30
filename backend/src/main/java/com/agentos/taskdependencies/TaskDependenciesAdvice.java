package com.agentos.taskdependencies;

import com.agentos.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TaskDependenciesController.class)
public class TaskDependenciesAdvice {
  @ExceptionHandler(TaskDependenciesFailure.class)
  public ResponseEntity<ApiErrorResponse> failure(TaskDependenciesFailure failure) {
    return ResponseEntity.status(failure.status())
        .body(ApiErrorResponse.of(failure.code(), "Request could not be completed"));
  }

  @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
  public ResponseEntity<ApiErrorResponse> unavailable(
      Exception failure, HttpServletRequest request) {
    return ResponseEntity.internalServerError()
        .body(
            ApiErrorResponse.of(
                "TASK_DEPENDENCIES_UNAVAILABLE", "Saved configuration is unavailable"));
  }

  @ExceptionHandler({IllegalArgumentException.class, IOException.class})
  public ResponseEntity<ApiErrorResponse> malformed(Exception failure) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of("BAD_REQUEST", "Invalid settings request"));
  }
}

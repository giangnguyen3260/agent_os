package com.agentos.taskediting;

import com.agentos.common.api.ApiErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TaskDraftEditController.class)
public class TaskDraftEditAdvice {
  @ExceptionHandler(TaskDraftEditFailure.class)
  public ResponseEntity<ApiErrorResponse> failure(TaskDraftEditFailure failure) {
    return ResponseEntity.status(failure.status())
        .body(ApiErrorResponse.of(failure.code(), "Request could not be completed"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.io.IOException.class})
  public ResponseEntity<ApiErrorResponse> malformed(Exception failure) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of("BAD_REQUEST", "Invalid settings request"));
  }
}

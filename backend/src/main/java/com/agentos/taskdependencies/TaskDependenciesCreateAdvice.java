package com.agentos.taskdependencies;

import com.agentos.common.api.ApiErrorResponse;
import com.agentos.mission.api.TaskCreateController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only graph integrity failures alter the existing create error boundary. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TaskCreateController.class)
public class TaskDependenciesCreateAdvice {
  @ExceptionHandler(TaskDependenciesFailure.class)
  public ResponseEntity<ApiErrorResponse> unavailable(TaskDependenciesFailure failure) {
    return ResponseEntity.status(failure.status())
        .body(ApiErrorResponse.of(failure.code(), "Request could not be completed"));
  }
}

package com.agentos.providerpolicy;

import com.agentos.common.api.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OptionsController.class)
public class OptionsAdvice {
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> failure(Exception exception) {
    PolicyFailure failure =
        exception instanceof PolicyFailure known ? known : PolicyFailure.unavailable();
    if (failure.status() != 400 && failure.status() != 401 && failure.status() != 403)
      failure = PolicyFailure.unavailable();
    return ResponseEntity.status(failure.status())
        .header("Cache-Control", "no-store")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiErrorResponse.of(failure.code(), OptionsResponses.message(failure.status())));
  }
}

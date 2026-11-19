package com.agentos.orchestration.api;

import com.agentos.common.api.ApiErrorResponse;
import com.agentos.model.infrastructure.OpenAiTextTransport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {OpenAiExecutionController.class, OpenAiTaskStartController.class})
class OpenAiExecutionErrors {
  @ExceptionHandler({IllegalStateException.class, OpenAiTextTransport.Unknown.class})
  ResponseEntity<ApiErrorResponse> unavailable(RuntimeException failure) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ApiErrorResponse.of(
                "EXECUTION_START_UNAVAILABLE",
                "Execution Start is unavailable; refresh current Run and configuration."));
  }
}

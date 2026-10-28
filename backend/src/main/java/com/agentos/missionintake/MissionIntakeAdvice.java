package com.agentos.missionintake;

import com.agentos.common.api.ApiErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MissionIntakeController.class)
public class MissionIntakeAdvice {
  @ExceptionHandler(MissionIntakeFailure.class)
  public ResponseEntity<ApiErrorResponse> failure(MissionIntakeFailure failure) {
    return ResponseEntity.status(failure.status())
        .body(ApiErrorResponse.of(failure.code(), "Request could not be completed"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> unavailable(Exception failure) {
    return ResponseEntity.internalServerError()
        .body(
            ApiErrorResponse.of(
                "MISSION_INTAKE_UNAVAILABLE", "Saved intake configuration is unavailable"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.io.IOException.class})
  public ResponseEntity<ApiErrorResponse> malformed(Exception failure) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of("BAD_REQUEST", "Invalid settings request"));
  }
}

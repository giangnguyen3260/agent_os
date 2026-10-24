package com.agentos.invitation;

import com.agentos.common.api.ApiErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.agentos.invitation")
@Order(-1)
public class InvitationErrors {
  @ExceptionHandler(InvitationFailure.class)
  public ResponseEntity<ApiErrorResponse> failure(InvitationFailure e) {
    return ResponseEntity.status(e.status).body(ApiErrorResponse.of(e.code, e.getMessage()));
  }
}

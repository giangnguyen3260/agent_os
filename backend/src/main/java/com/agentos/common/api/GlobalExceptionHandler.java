package com.agentos.common.api;

import com.agentos.organization.application.NewTeamDraftService.Conflict;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(com.agentos.agent.application.AgentDraftService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> handleAgentConflict(
      com.agentos.agent.application.AgentDraftService.Conflict ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(Conflict.class)
  public ResponseEntity<ApiErrorResponse> handleTeamConflict(Conflict ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(EntityNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(EntityNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of("NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(ApiErrorResponse.of("BAD_REQUEST", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of("VALIDATION_ERROR", "Request validation failed"));
  }
}

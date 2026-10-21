package com.agentos.common.api;

import com.agentos.organization.application.NewTeamDraftService.Conflict;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(com.agentos.agent.application.AgentDraftService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> handleAgentConflict(
      com.agentos.agent.application.AgentDraftService.Conflict ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(com.agentos.mission.application.MissionCreateService.Conflict.class)
  public ResponseEntity<ApiErrorResponse> handleMissionConflict(
      com.agentos.mission.application.MissionCreateService.Conflict ex) {
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

  // Resolve conversion errors directly: sendError would dispatch to the denied /error route.
  // Never expose Jackson messages, which can contain private request values.
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    ServletRequestBindingException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiErrorResponse> handleMalformedRequest(Exception ex) {
    return ResponseEntity.badRequest().body(ApiErrorResponse.of("BAD_REQUEST", "Invalid request"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException ex) {
    var status = HttpStatus.resolve(ex.getStatusCode().value());
    return ResponseEntity.status(ex.getStatusCode())
        .body(
            ApiErrorResponse.of(
                status == null ? "REQUEST_ERROR" : status.name(), "Request cannot be completed"));
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

package com.agentos.auth;

import com.agentos.common.api.ApiErrorResponse;
import com.agentos.common.api.ApiResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/recovery")
public class RecoveryController {
  private final RecoveryService recovery;

  public RecoveryController(RecoveryService recovery) {
    this.recovery = recovery;
  }

  @PostMapping("/request")
  public ResponseEntity<?> request(@RequestBody Request input) {
    if (!recovery.enabled()) return unavailable();
    recovery.request(input.email());
    return ResponseEntity.accepted().body(ApiResponse.ok(Map.of("accepted", true)));
  }

  @PostMapping("/confirm")
  public ResponseEntity<?> confirm(@RequestBody Confirm input) {
    if (!recovery.enabled()) return unavailable();
    if (!recovery.confirm(input.token(), input.password()))
      return ResponseEntity.badRequest()
          .body(ApiErrorResponse.of("INVALID_RESET_TOKEN", "Invalid or expired reset token"));
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<?> unavailable() {
    return ResponseEntity.status(503)
        .body(ApiErrorResponse.of("RECOVERY_UNAVAILABLE", "Password recovery is unavailable"));
  }

  public record Request(String email) {}

  public record Confirm(String token, String password) {}
}

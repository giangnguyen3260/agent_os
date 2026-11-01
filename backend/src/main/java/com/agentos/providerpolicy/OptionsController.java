package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OptionsController {
  private final PolicyService policies;

  public OptionsController(PolicyService policies) {
    this.policies = policies;
  }

  @GetMapping("/api/v1/organizations/{organizationId}/provider-connections/options")
  public ResponseEntity<ApiResponse<PolicyService.Options>> options(
      @AuthenticationPrincipal HumanIdentity actor,
      @PathVariable String organizationId,
      HttpServletRequest request)
      throws IOException {
    OptionsWire.validate(request, organizationId);
    PolicyService.Options result;
    try {
      result = policies.options(actor, UUID.fromString(organizationId));
    } catch (RuntimeException failure) {
      if (failure instanceof PolicyFailure known
          && (known.status() == 401 || known.status() == 403)) throw known;
      throw PolicyFailure.unavailable();
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiResponse.ok(result));
  }
}

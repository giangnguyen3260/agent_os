package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OptionsController {
  private final PolicyService policies;
  private final boolean createEnabled;
  private final boolean renameEnabled;
  private final boolean disableEnabled;

  public OptionsController(
      PolicyService policies,
      @Value("${agentos.provider-create.enabled:false}") boolean createEnabled,
      @Value("${agentos.provider-rename.http-enabled:false}") boolean renameEnabled,
      @Value("${agentos.provider-disable.http-enabled:false}") boolean disableEnabled) {
    this.policies = policies;
    this.createEnabled = createEnabled;
    this.renameEnabled = renameEnabled;
    this.disableEnabled = disableEnabled;
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
        .header("X-Provider-Create-Enabled", Boolean.toString(createEnabled))
        .header("X-Provider-Rename-Enabled", Boolean.toString(renameEnabled))
        .header("X-Provider-Disable-Enabled", Boolean.toString(disableEnabled))
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(ApiResponse.ok(result));
  }
}

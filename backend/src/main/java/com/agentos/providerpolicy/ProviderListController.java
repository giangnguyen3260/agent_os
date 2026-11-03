package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.provider-list.enabled", havingValue = "true")
public class ProviderListController {
  private final ProviderListSnapshot snapshot;

  ProviderListController(ProviderListSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  @GetMapping("/api/v1/organizations/{organizationId}/provider-connections")
  public ResponseEntity<ApiResponse<ProviderListResponse>> list(
      @AuthenticationPrincipal HumanIdentity actor,
      @PathVariable String organizationId,
      HttpServletRequest request)
      throws IOException {
    int page = ProviderListWire.validate(request, organizationId);
    UUID organization = UUID.fromString(organizationId);
    ProviderListResponse data;
    try {
      data =
          ProviderListResponse.fromSnapshot(
              organization, snapshot.readWithEvidence(actor, organization, page));
    } catch (PolicyFailure failure) {
      if (failure.status() == 401) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
      }
      throw failure;
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.ok(data));
  }
}

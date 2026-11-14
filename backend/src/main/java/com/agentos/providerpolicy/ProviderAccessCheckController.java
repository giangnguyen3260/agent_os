package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.provider-access-check.http-enabled", havingValue = "true")
public class ProviderAccessCheckController {
  private static final String ROOT = "/api/v1/organizations/";
  private static final DateTimeFormatter TIME =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
  private final ProviderReceiptService receipts;

  ProviderAccessCheckController(ProviderReceiptService receipts) {
    this.receipts = receipts;
  }

  @GetMapping(ROOT + "{org}/provider-connections/{target}/checks/{check}")
  ResponseEntity<ApiResponse<Check>> status(
      @AuthenticationPrincipal HumanIdentity actor,
      @PathVariable String org,
      @PathVariable String target,
      @PathVariable String check,
      HttpServletRequest request)
      throws IOException {
    if (!OptionsGate.admitted(request)) throw PolicyFailure.forbidden();
    if (!OptionsWire.canonicalUuid(target)
        || !OptionsWire.canonicalUuid(check)
        || !request
            .getRequestURI()
            .equals(
                request.getContextPath()
                    + ROOT
                    + org
                    + "/provider-connections/"
                    + target
                    + "/checks/"
                    + check)) throw OptionsWire.bad();
    OptionsWire.validate(request, org);
    try {
      var value =
          receipts.accessCheckStatus(
              actor, UUID.fromString(org), UUID.fromString(target), UUID.fromString(check));
      String state =
          value.completedAt() != null
              ? "COMPLETED"
              : value.startedAt() != null ? "RUNNING" : "QUEUED";
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              ApiResponse.ok(
                  new Check(
                      value.organizationId(),
                      value.connectionId(),
                      value.id(),
                      "ACCESS_CHECK",
                      Long.toString(value.connectionRevision()),
                      Long.toString(value.credentialRevision()),
                      Long.toString(value.policyRevision()),
                      time(value.acceptedAt()),
                      time(value.startedAt()),
                      time(value.completedAt()),
                      value.completedAt() == null
                          ? null
                          : time(value.completedAt().plusMinutes(15)),
                      state,
                      value.outcome(),
                      value.errorCode(),
                      "NOT_REQUESTED",
                      null)));
    } catch (ProviderReceiptFailure failure) {
      if (failure.status() == 401 || failure.status() == 403 || failure.status() == 404)
        throw new PolicyFailure(failure.status(), failure.code());
      throw PolicyFailure.unavailable();
    }
  }

  private static String time(OffsetDateTime value) {
    return value == null ? null : TIME.format(value);
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record Check(
      UUID organizationId,
      UUID connectionId,
      UUID id,
      String kind,
      String connectionRevision,
      String credentialRevision,
      String policyRevision,
      String acceptedAt,
      String startedAt,
      String completedAt,
      String expiresAt,
      String state,
      String outcome,
      String errorCode,
      String catalogueState,
      Integer modelCount) {}
}

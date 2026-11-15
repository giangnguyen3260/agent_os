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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression(
    "${agentos.provider-access-check.http-enabled:false} ||"
        + " ${agentos.provider-model-discovery.http-enabled:false}")
public class ProviderAccessCheckController {
  private static final String ROOT = "/api/v1/organizations/";
  private static final DateTimeFormatter TIME =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
  private final ProviderReceiptService receipts;
  private final boolean accessEnabled;
  private final boolean discoveryEnabled;

  ProviderAccessCheckController(
      ProviderReceiptService receipts,
      @Value("${agentos.provider-access-check.http-enabled:false}") boolean accessEnabled,
      @Value("${agentos.provider-model-discovery.http-enabled:false}") boolean discoveryEnabled) {
    this.receipts = receipts;
    this.accessEnabled = accessEnabled;
    this.discoveryEnabled = discoveryEnabled;
  }

  @GetMapping(ROOT + "{org}/provider-connections/{target}/checks/{check}")
  ResponseEntity<ApiResponse<Check>> status(
      @AuthenticationPrincipal HumanIdentity actor,
      @PathVariable String org,
      @PathVariable String target,
      @PathVariable String check,
      HttpServletRequest request)
      throws IOException {
    validatePath(request, org, target, check, "");
    OptionsWire.validate(request, org);
    try {
      var value =
          receipts.checkStatus(
              actor,
              UUID.fromString(org),
              UUID.fromString(target),
              UUID.fromString(check),
              accessEnabled,
              discoveryEnabled);
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
                      value.kind(),
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
                      value.catalogueState(),
                      value.modelCount())));
    } catch (ProviderReceiptFailure failure) {
      throw readFailure(failure);
    }
  }

  @GetMapping(ROOT + "{org}/provider-connections/{target}/checks/{check}/models")
  ResponseEntity<ApiResponse<ProviderReceiptService.ModelPage>> models(
      @AuthenticationPrincipal HumanIdentity actor,
      @PathVariable String org,
      @PathVariable String target,
      @PathVariable String check,
      HttpServletRequest request)
      throws IOException {
    if (!discoveryEnabled) throw new PolicyFailure(404, "NOT_FOUND");
    validatePath(request, org, target, check, "/models");
    int page = ProviderListWire.validate(request, org);
    try {
      var value =
          receipts.discoveryModels(
              actor,
              UUID.fromString(org),
              UUID.fromString(target),
              UUID.fromString(check),
              page,
              accessEnabled);
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .body(ApiResponse.ok(value));
    } catch (ProviderReceiptFailure failure) {
      throw readFailure(failure);
    }
  }

  private static void validatePath(
      HttpServletRequest request, String org, String target, String check, String suffix) {
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
                    + check
                    + suffix)) throw OptionsWire.bad();
  }

  private static PolicyFailure readFailure(ProviderReceiptFailure failure) {
    return switch (failure.status()) {
      case 401, 403, 404 -> new PolicyFailure(failure.status(), failure.code());
      case 409 ->
          "RESULT_NOT_AVAILABLE".equals(failure.code())
              ? new PolicyFailure(409, "RESULT_NOT_AVAILABLE")
              : PolicyFailure.unavailable();
      default -> PolicyFailure.unavailable();
    };
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

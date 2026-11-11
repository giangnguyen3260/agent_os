package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.Failure;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providertransport.ProviderCreateResponses;
import com.agentos.providertransport.ProviderDisableResponses;
import com.agentos.providertransport.ProviderRenameResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression(
    "${agentos.provider-create.enabled:false} or ${agentos.provider-rename.http-enabled:false}"
        + " or ${agentos.provider-disable.http-enabled:false}")
public class ProviderCreateReceiptController {
  private static final String ORGANIZATIONS = "/api/v1/organizations/";
  private static final String RECEIPTS = "/provider-connections/receipts/";

  private final ProviderCompletionBoundary completion;
  private final ObjectMapper json;
  private final boolean renameEnabled;
  private final boolean disableEnabled;

  ProviderCreateReceiptController(
      PlatformTransactionManager manager,
      JdbcTemplate jdbc,
      ProviderReceiptService receipts,
      ObjectMapper json,
      @Value("${agentos.provider-rename.http-enabled:false}") boolean renameEnabled,
      @Value("${agentos.provider-disable.http-enabled:false}") boolean disableEnabled) {
    this.completion = new ProviderCompletionBoundary(manager, jdbc, receipts);
    this.json = json;
    this.renameEnabled = renameEnabled;
    this.disableEnabled = disableEnabled;
  }

  @GetMapping(ORGANIZATIONS + "{organizationId}" + RECEIPTS + "{key}")
  public void ownReceipt(
      @AuthenticationPrincipal HumanIdentity actor,
      @PathVariable String organizationId,
      @PathVariable String key,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    // A decoded MVC alias must not reach the lookup through a different security chain.
    if (!OptionsGate.admitted(request)) throw PolicyFailure.forbidden();
    if (!OptionsWire.canonicalUuid(key)
        || !request
            .getRequestURI()
            .equals(request.getContextPath() + ORGANIZATIONS + organizationId + RECEIPTS + key)) {
      throw OptionsWire.bad();
    }
    OptionsWire.validate(request, organizationId);
    var result = completion.lookupOwn(actor, UUID.fromString(organizationId), UUID.fromString(key));
    if (result.failure() != null && result.failure().code() == Code.UNAUTHORIZED) {
      var session = request.getSession(false);
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
    }
    writeLookup(json, response, result, renameEnabled, disableEnabled);
  }

  /** Dispatch only the explicitly supported historical configuration receipt operations. */
  static void writeLookup(
      ObjectMapper json,
      HttpServletResponse response,
      LookupOutcome result,
      boolean renameEnabled,
      boolean disableEnabled)
      throws IOException {
    if (result == null || result.state() != LookupState.FOUND) {
      ProviderCreateResponses.writeLookup(json, response, result);
      return;
    }
    var operation = result.receipt() == null ? null : result.receipt().operation();
    if (operation == Operation.CREATE) {
      ProviderCreateResponses.writeLookup(json, response, result);
    } else if (operation == Operation.RENAME && renameEnabled) {
      ProviderRenameResponses.writeLookup(json, response, result);
    } else if (operation == Operation.DISABLE && disableEnabled) {
      ProviderDisableResponses.writeLookup(json, response, result);
    } else {
      ProviderCreateResponses.error(json, response, new Failure(Code.RECEIPT_UNAVAILABLE));
    }
  }
}

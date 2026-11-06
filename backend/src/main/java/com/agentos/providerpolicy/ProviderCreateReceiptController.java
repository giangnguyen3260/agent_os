package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providertransport.ProviderCreateResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "agentos.provider-create.enabled", havingValue = "true")
public class ProviderCreateReceiptController {
  private static final String ORGANIZATIONS = "/api/v1/organizations/";
  private static final String RECEIPTS = "/provider-connections/receipts/";

  private final ProviderCompletionBoundary completion;
  private final ObjectMapper json;

  ProviderCreateReceiptController(
      PlatformTransactionManager manager,
      JdbcTemplate jdbc,
      ProviderReceiptService receipts,
      ObjectMapper json) {
    this.completion = new ProviderCompletionBoundary(manager, jdbc, receipts);
    this.json = json;
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
    var result =
        completion.lookupOwn(actor, UUID.fromString(organizationId), UUID.fromString(key));
    if (result.failure() != null && result.failure().code() == Code.UNAUTHORIZED) {
      var session = request.getSession(false);
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
    }
    ProviderCreateResponses.writeLookup(json, response, result);
  }
}

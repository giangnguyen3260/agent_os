package com.agentos.providertransport;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Header-only resolution for a future provider command chain. Spring's CsrfFilter still performs
 * actual session-token comparison. No form parameter/body fallback is permitted.
 */
public final class ProviderCsrfHandler extends CsrfTokenRequestAttributeHandler {
  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
    var values = Collections.list(request.getHeaders(csrfToken.getHeaderName()));
    if (values.size() != 1) return null;
    String value = values.get(0);
    if (value == null || !value.matches("[A-Za-z0-9_+/=-]{1,512}")) return null;
    return value;
  }
}

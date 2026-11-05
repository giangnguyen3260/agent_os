package com.agentos.providertransport;

import com.agentos.providerpolicy.PolicyFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;

/**
 * Header-only validation after current ADMIN and actual session-CSRF verification. Never consumes a
 * body, obtains a session, reserves admission or accesses external keys.
 */
public final class ProviderCreateRequest {
  private static final String UUID_WIRE =
      "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  private ProviderCreateRequest() {}

  /**
   * csrfHeaderName comes from the trusted session CSRF configuration, never a request parameter.
   */
  public static UUID validate(
      HttpServletRequest request, String organization, String csrfHeaderName) {
    if (request == null
        || organization == null
        || !organization.matches(UUID_WIRE)
        || !"POST".equals(request.getMethod())
        || request.getQueryString() != null
        || csrfHeaderName == null
        || !csrfHeaderName.matches("[A-Za-z0-9-]{1,64}")
        || request.getHeader("Content-Encoding") != null) throw bad();
    String type = one(request, "Content-Type", true);
    if (!type.matches(
        "(?i)[ \\t]*application/json[ \\t]*"
            + "(?:;[ \\t]*charset[ \\t]*=[ \\t]*"
            + "(?:UTF-8|\\x22UTF-8\\x22)[ \\t]*)?")) throw bad();
    String csrf = one(request, csrfHeaderName, true);
    if (!csrf.matches("[A-Za-z0-9_+/=-]{1,512}")) throw bad();
    String key = one(request, "Idempotency-Key", true);
    if (!key.matches(UUID_WIRE)) throw bad();
    String length = one(request, "Content-Length", false);
    String transfer = one(request, "Transfer-Encoding", false);
    if (length != null && transfer != null) throw bad();
    if (length != null && (!length.matches("[1-9][0-9]{0,3}") || Integer.parseInt(length) > 8192))
      throw bad();
    if (transfer != null && !transfer.equalsIgnoreCase("chunked")) throw bad();
    one(request, "Accept", false);
    ProviderAccept.validate(request);
    return UUID.fromString(key);
  }

  private static String one(HttpServletRequest request, String name, boolean required) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() > 1 || (required && values.size() != 1)) throw bad();
    if (values.isEmpty()) return null;
    String value = values.get(0);
    if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw bad();
    return value;
  }

  private static PolicyFailure bad() {
    return new PolicyFailure(400, "BAD_REQUEST");
  }
}

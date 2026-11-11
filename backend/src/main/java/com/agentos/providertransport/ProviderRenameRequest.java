package com.agentos.providertransport;

import com.agentos.providerpolicy.PolicyFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/** Header-only Rename validation after current ADMIN and actual session CSRF verification. */
public final class ProviderRenameRequest {
  private static final Pattern UUID_WIRE =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  private ProviderRenameRequest() {}

  /** Returns the canonical idempotency key without consuming the body or creating a session. */
  public static UUID validate(
      HttpServletRequest request, String organization, String target, String csrfHeaderName) {
    return validate(request, organization, target, csrfHeaderName, "rename");
  }

  public static UUID validateDisable(
      HttpServletRequest request, String organization, String target, String csrfHeaderName) {
    return validate(request, organization, target, csrfHeaderName, "disable");
  }

  private static UUID validate(
      HttpServletRequest request,
      String organization,
      String target,
      String csrfHeaderName,
      String suffix) {
    if (request == null
        || organization == null
        || target == null
        || !UUID_WIRE.matcher(target).matches()
        || !request
            .getRequestURI()
            .equals(
                request.getContextPath()
                    + "/api/v1/organizations/"
                    + organization
                    + "/provider-connections/"
                    + target
                    + "/"
                    + suffix)) throw new PolicyFailure(400, "BAD_REQUEST");
    // Create's header boundary has the same POST/JSON/Accept/framing/CSRF/key contract.
    return ProviderCreateRequest.validate(request, organization, csrfHeaderName);
  }
}

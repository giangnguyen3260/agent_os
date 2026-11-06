package com.agentos.providerpolicy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

final class OptionsWire {
  private static final String CANONICAL_UUID =
      "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  private OptionsWire() {}

  static void validate(HttpServletRequest request, String org) throws IOException {
    if (request.getQueryString() != null) throw bad();
    validateCommon(request, org);
  }

  static void validateCommon(HttpServletRequest request, String org) throws IOException {
    if (!canonicalUuid(org)
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null
        || request.getInputStream().read() != -1) throw bad();
    com.agentos.providertransport.ProviderAccept.validate(request);
  }

  static boolean canonicalUuid(String value) {
    return value != null && value.matches(CANONICAL_UUID);
  }

  static PolicyFailure bad() {
    return new PolicyFailure(400, "BAD_REQUEST");
  }
}

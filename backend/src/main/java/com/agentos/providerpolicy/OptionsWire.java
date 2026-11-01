package com.agentos.providerpolicy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import org.springframework.http.MediaType;

final class OptionsWire {
  private OptionsWire() {}

  static void validate(HttpServletRequest request, String org) throws IOException {
    if (!org.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        || request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null
        || request.getInputStream().read() != -1) throw bad();
    var headers = Collections.list(request.getHeaders("Accept"));
    if (headers.isEmpty()) return;
    try {
      int specificity = -1;
      double selectedQuality = 0;
      var seen = new java.util.HashSet<String>();
      for (String header : headers) {
        if (header.isBlank()
            || header.startsWith(",")
            || header.endsWith(",")
            || header.contains(",,")) throw bad();
        for (MediaType type : MediaType.parseMediaTypes(header)) {
          double quality = type.getQualityValue();
          if (!Double.isFinite(quality) || quality < 0 || quality > 1) throw bad();
          String range =
              type.getType().toLowerCase(java.util.Locale.ROOT)
                  + "/"
                  + type.getSubtype().toLowerCase(java.util.Locale.ROOT);
          if (!seen.add(range)) throw bad();
          if (type.includes(MediaType.APPLICATION_JSON)) {
            int rank = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
            if (rank > specificity) {
              specificity = rank;
              selectedQuality = quality;
            }
          }
        }
      }
      if (specificity < 0 || selectedQuality <= 0) throw bad();
    } catch (IllegalArgumentException failure) {
      throw bad();
    }
  }

  static PolicyFailure bad() {
    return new PolicyFailure(400, "BAD_REQUEST");
  }
}

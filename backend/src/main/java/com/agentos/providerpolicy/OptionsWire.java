package com.agentos.providerpolicy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
      var seen = new HashSet<String>();
      for (String header : headers) {
        for (String raw : parts(header, ',')) {
          parameters(raw);
          MediaType type = MediaType.parseMediaType(raw);
          if (type.isWildcardType() && !type.getSubtype().equals("*")) throw bad();
          double quality = type.getQualityValue();
          if (!Double.isFinite(quality) || quality < 0 || quality > 1) throw bad();
          String range =
              type.getType().toLowerCase(Locale.ROOT)
                  + "/"
                  + type.getSubtype().toLowerCase(Locale.ROOT);
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

  private static void parameters(String raw) {
    var tokens = parts(raw, ';');
    var names = new HashSet<String>();
    for (int i = 1; i < tokens.size(); i++) {
      String parameter = tokens.get(i);
      int equals = parameter.indexOf('=');
      if (equals <= 0) throw bad();
      String name = parameter.substring(0, equals).strip().toLowerCase(Locale.ROOT);
      String value = parameter.substring(equals + 1).strip();
      if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value.isEmpty() || !names.add(name))
        throw bad();
      if (name.equals("q") && !value.matches("(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)"))
        throw bad();
    }
  }

  /** Split only outside quoted strings, retaining escapes for MediaType's value validation. */
  private static List<String> parts(String input, char delimiter) {
    var values = new ArrayList<String>();
    boolean quoted = false;
    int start = 0;
    for (int i = 0; i < input.length(); i++) {
      char value = input.charAt(i);
      if (quoted && value == '\\') {
        if (++i == input.length()) throw bad();
      } else if (value == '"') quoted = !quoted;
      else if (value == delimiter && !quoted) {
        String token = input.substring(start, i).strip();
        if (token.isEmpty()) throw bad();
        values.add(token);
        start = i + 1;
      }
    }
    if (quoted) throw bad();
    String last = input.substring(start).strip();
    if (last.isEmpty()) throw bad();
    values.add(last);
    return values;
  }

  static PolicyFailure bad() {
    return new PolicyFailure(400, "BAD_REQUEST");
  }
}

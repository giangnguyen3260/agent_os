package com.agentos.providerpolicy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.regex.Pattern;

final class ProviderListWire {
  private static final Pattern PAGE = Pattern.compile("page=(0|[1-9][0-9]{0,5})");

  private ProviderListWire() {}

  static int validate(HttpServletRequest request, String organization) throws IOException {
    int page = parsePage(request.getQueryString());
    OptionsWire.validateCommon(request, organization);
    return page;
  }

  static int parsePage(String query) {
    if (query == null) return 0;
    var match = PAGE.matcher(query);
    if (!match.matches()) throw OptionsWire.bad();
    int page = Integer.parseInt(match.group(1));
    if (page > 100_000) throw OptionsWire.bad();
    return page;
  }
}

package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderListWireTest {
  @Test
  void acceptsOnlyCanonicalPagesInRange() {
    assertThat(ProviderListWire.parsePage(null)).isZero();
    assertThat(ProviderListWire.parsePage("page=0")).isZero();
    assertThat(ProviderListWire.parsePage("page=100000")).isEqualTo(100_000);
    for (String query :
        new String[] {
          "",
          "page",
          "page=",
          "page=00",
          "page=-1",
          "page=+1",
          "page=100001",
          "page=99999999999",
          "page=%31",
          "%70age=1",
          "page=1&page=2",
          "page=1&",
          "page=1&pageSize=20",
          "page=1.0",
          "page=1e0",
          "Page=1",
          "page=1 "
        }) {
      assertThatThrownBy(() -> ProviderListWire.parsePage(query)).isInstanceOf(PolicyFailure.class);
    }
  }
}

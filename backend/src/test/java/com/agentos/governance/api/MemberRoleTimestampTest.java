package com.agentos.governance.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberRoleTimestampTest {
  @Test
  void resultAndHistoryPreserveSixDigitUtcContractAtEveryPrecision() throws Exception {
    var mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    assertThat(mapper.writeValueAsString(Instant.parse("2026-11-19T00:00:00.123000Z")))
        .isEqualTo("\"2026-11-19T00:00:00.123Z\"");
    var id = UUID.randomUUID();
    for (String fraction : new String[] {"000000", "123000", "123400", "123456"}) {
      String expected = "2026-11-19T00:00:00." + fraction + "Z";
      var time = Instant.parse(expected);
      var result =
          new MemberRoleManagementResponse.Result(
              "CHANGE_ROLE", id, id, id, "Actor", id, id, 1, time, "MEMBER", "ADMIN");
      var item =
          new MemberRoleManagementResponse.Item(
              id, id, "Actor", "MEMBER", "ADMIN", "Reason", time, 1);
      for (Object value : new Object[] {result, item}) {
        String json = mapper.writeValueAsString(value);
        assertThat(mapper.readTree(json).get("recordedAt").asText()).isEqualTo(expected);
        assertThat(mapper.readValue(json, value.getClass())).isEqualTo(value);
      }
    }
  }
}

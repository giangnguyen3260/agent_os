package com.agentos.taskowner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskOwnerWireTest {
  private static final String FP = "a".repeat(64);
  private static final String CLEAR =
      "{\"expectedRevision\":0,\"expectedFingerprint\":\""
          + FP
          + "\",\"ownerAgentId\":null,\"ownerFingerprint\":null}";

  private TaskOwnerDtos.Command read(String body) throws Exception {
    return TaskOwnerWire.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void explicitNullsAreRequiredAndCanonicalAssignmentPreservesIdentity() throws Exception {
    assertThat(read(CLEAR)).isEqualTo(new TaskOwnerDtos.Command(0, FP, null, null));
    UUID id = UUID.randomUUID();
    String assigned =
        CLEAR
            .replace("\"ownerAgentId\":null", "\"ownerAgentId\":\"" + id + "\"")
            .replace("\"ownerFingerprint\":null", "\"ownerFingerprint\":\"" + FP + "\"");
    assertThat(read(assigned)).isEqualTo(new TaskOwnerDtos.Command(0, FP, id, FP));
    for (String invalid :
        java.util.List.of(
            CLEAR.replace(",\"ownerFingerprint\":null", ""),
            CLEAR.replace("\"ownerFingerprint\":null", "\"ownerFingerprint\":\"" + FP + "\""),
            assigned.replace("\"ownerFingerprint\":\"" + FP + "\"", "\"ownerFingerprint\":null"),
            assigned.replace(id.toString(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            assigned.replace(id.toString(), "00000000-0000-0000-0000-000000000000"),
            CLEAR.replace(FP, FP.toUpperCase()),
            CLEAR.replace("\"expectedRevision\":0", "\"expectedRevision\":true"),
            CLEAR.replace("\"expectedRevision\":0", "\"expectedRevision\":-1"),
            CLEAR.replace("\"expectedRevision\":0", "\"expectedRevision\":null"))) {
      assertThatThrownBy(() -> read(invalid))
          .isInstanceOf(TaskOwnerFailure.class)
          .hasMessage("BAD_REQUEST");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"a\nb", "a\rb", "a\tb", "\u0000", "\u2028", "\u2029", "\ud800", "\udc00"})
  void unsafeSearchTextRejected(String query) {
    assertThatThrownBy(() -> TaskOwnerWire.query(query)).hasMessage("BAD_REQUEST");
  }

  @Test
  void unicodeLimitsCountCodepointsAndSearchPreservesLiteralCharacters() {
    assertThat(TaskOwnerWire.query("  %_  ")).isEqualTo("%_");
    assertThat(TaskOwnerWire.query(null)).isEmpty();
    assertThat(TaskOwnerWire.query("😀".repeat(100))).isEqualTo("😀".repeat(100));
    assertThatThrownBy(() -> TaskOwnerWire.query("😀".repeat(101))).hasMessage("BAD_REQUEST");
    assertThat(TaskOwnerWire.text("😀".repeat(255), 255, false)).hasSize(510);
    assertThatThrownBy(() -> TaskOwnerWire.text("😀".repeat(256), 255, false))
        .hasMessage("BAD_REQUEST");
  }
}

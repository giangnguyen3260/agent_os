package com.agentos.missionintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionIntakeWireTest {
  private MissionIntakeDtos.Command read(String source) throws Exception {
    return MissionIntakeWire.read(
        new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
  }

  private String body(String version) {
    return "{\"expectedVersion\":"
        + version
        + ",\"expectedFingerprint\":\""
        + "a".repeat(64)
        + "\",\"paused\":true}";
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "9007199254740993", "9223372036854775807"})
  void fullInt64StringsArePreserved(String version) throws Exception {
    assertThat(read(body("\"" + version + "\"")).expectedVersion()).isEqualTo(version);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "1.0",
        "null",
        "true",
        "\"00\"",
        "\"-1\"",
        "\"+1\"",
        "\" 1\"",
        "\"1e2\"",
        "\"9223372036854775808\""
      })
  void noncanonicalVersionIsRejected(String version) {
    assertThatThrownBy(() -> read(body(version))).isInstanceOf(MissionIntakeFailure.class);
  }

  @Test
  void duplicateUnknownTrailingTypeAndOversizeAreRejected() {
    String valid = body("\"0\"");
    for (String source :
        new String[] {
          valid.replace("\"paused\":true", "\"paused\":true,\"paused\":false"),
          valid.replace("\"paused\":true", "\"paused\":\"true\""),
          valid.replace("\"paused\":true", "\"paused\":true,\"extra\":0"),
          valid + "{}",
          valid + " ".repeat(4096),
          valid.replace("a".repeat(64), "A".repeat(64))
        }) assertThatThrownBy(() -> read(source)).isInstanceOf(MissionIntakeFailure.class);
  }

  @Test
  void invalidUtf8AndUuidAreRejected() {
    assertThatThrownBy(
            () -> MissionIntakeWire.read(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28})))
        .isInstanceOf(MissionIntakeFailure.class);
    for (String id :
        new String[] {
          "1-1-1-1-1",
          "00000000-0000-0000-0000-000000000000",
          "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        })
      assertThatThrownBy(() -> MissionIntakeWire.uuid(id)).isInstanceOf(MissionIntakeFailure.class);
  }

  @Test
  void blockersAreOrderedAndUnknownStatesFailClosed() {
    assertThat(MissionIntakeService.blockers("ARCHIVED", "COMPLETED", Long.MAX_VALUE))
        .containsExactly("TEAM_ARCHIVED", "MISSION_NOT_EDITABLE", "REVISION_EXHAUSTED");
    assertThat(MissionIntakeService.blockers("FUTURE", "FUTURE", 0))
        .containsExactly("UNKNOWN_STATE");
    assertThat(MissionIntakeService.blockers("ACTIVE", "RUNNING", 0)).isEmpty();
  }
}

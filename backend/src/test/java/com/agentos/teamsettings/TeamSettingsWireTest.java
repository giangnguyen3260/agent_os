package com.agentos.teamsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TeamSettingsWireTest {
  static final String BODY =
      """
      {"expectedRevision":0,"expectedFingerprint":"%s","name":"  Team  ",
      "purpose":" Purpose ","description":null,"managerAgentId":null,"agentIds":[],
      "intake":"MANUAL","concurrency":1,"monthlyBudget":1.00,"acknowledgement":true}
      """
          .formatted("a".repeat(64));

  static TeamSettingsDtos.Command parse(String body) throws Exception {
    return TeamSettingsWire.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void canonicalizesOnlyRequiredTextBudgetAndMemberOrdering() throws Exception {
    var command = parse(BODY.replace("\"description\":null", "\"description\":\"  exact  \""));
    assertThat(command.name()).isEqualTo("Team");
    assertThat(command.purpose()).isEqualTo("Purpose");
    assertThat(command.description()).isEqualTo("  exact  ");
    assertThat(command.monthlyBudget().scale()).isEqualTo(2);
    assertThat(parse(BODY.replace("1.00", "1")).monthlyBudget()).isEqualTo(command.monthlyBudget());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"1\"", "1.0", "true", "null", "2147483648", "-1"})
  void rejectsRevisionCoercionAndOverflow(String value) {
    assertThatThrownBy(
            () -> parse(BODY.replace("\"expectedRevision\":0", "\"expectedRevision\":" + value)))
        .isInstanceOf(TeamSettingsFailure.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"1\"", "true", "null", "-0.01", "1000000.01", "1.001", "1.000"})
  void rejectsBudgetCoercionAndExcessPrecision(String value) {
    assertThatThrownBy(() -> parse(BODY.replace("1.00", value)))
        .isInstanceOf(TeamSettingsFailure.class);
  }

  @Test
  void rejectsDuplicateUnknownMissingAndTrailingFields() {
    for (String invalid :
        new String[] {
          BODY.replace("\"expectedRevision\":0", "\"expectedRevision\":0,\"expectedRevision\":0"),
          BODY.replace("\"expectedRevision\":0", "\"unknown\":0,\"expectedRevision\":0"),
          BODY.replace("\"description\":null,", ""),
          BODY + "{}"
        }) {
      assertThatThrownBy(() -> parse(invalid)).isInstanceOf(TeamSettingsFailure.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "c86d03b1-26db-4ff7-902b-861b834c29eA",
        "c86d03b1-26db-0ff7-902b-861b834c29ea",
        "c86d03b1-26db-4ff7-702b-861b834c29ea",
        "1-1-1-1-1"
      })
  void uuidMustBeCanonicalVariantAndSupportedVersion(String value) {
    assertThatThrownBy(() -> TeamSettingsWire.uuid(value)).isInstanceOf(TeamSettingsFailure.class);
  }

  @Test
  void rejectsUnsafeTextButCountsUnicodeCodepoints() throws Exception {
    for (String value : new String[] {"\\nTeam", "Team\\u007f", "Team\\ud800", "Team\\udc00"}) {
      assertThatThrownBy(() -> parse(BODY.replace("  Team  ", value)))
          .isInstanceOf(TeamSettingsFailure.class);
    }
    assertThat(parse(BODY.replace("  Team  ", "😀".repeat(255))).name().codePointCount(0, 510))
        .isEqualTo(255);
    assertThatThrownBy(() -> parse(BODY.replace("  Team  ", "😀".repeat(256))))
        .isInstanceOf(TeamSettingsFailure.class);
  }

  @Test
  void boundsActualUtf8BytesRatherThanCharactersOrContentLength() throws Exception {
    String exact = BODY.stripTrailing();
    exact += " ".repeat(TeamSettingsWire.MAX_BYTES - exact.getBytes(StandardCharsets.UTF_8).length);
    assertThat(parse(exact)).isNotNull();
    String oversized = exact + " ";
    assertThatThrownBy(() -> parse(oversized)).isInstanceOf(TeamSettingsFailure.class);
  }

  @Test
  void rejectsDuplicateMembersAndManagerInOrdinaryIds() {
    String id = "c86d03b1-26db-4ff7-902b-861b834c29ea";
    assertThatThrownBy(
            () ->
                parse(
                    BODY.replace(
                        "\"agentIds\":[]", "\"agentIds\":[\"" + id + "\",\"" + id + "\"]")))
        .isInstanceOf(TeamSettingsFailure.class);
    assertThatThrownBy(
            () ->
                parse(
                    BODY.replace("\"managerAgentId\":null", "\"managerAgentId\":\"" + id + "\"")
                        .replace("\"agentIds\":[]", "\"agentIds\":[\"" + id + "\"]")))
        .isInstanceOf(TeamSettingsFailure.class);
  }

  @Test
  void rejectsNonUtf8AndMalformedUtf8RatherThanAutoDetectingEncoding() {
    assertThatThrownBy(
            () ->
                TeamSettingsWire.read(
                    new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_16))))
        .isInstanceOf(TeamSettingsFailure.class);
    byte[] invalid = {(byte) 0xc0, (byte) 0xaf};
    assertThatThrownBy(() -> TeamSettingsWire.read(new ByteArrayInputStream(invalid)))
        .isInstanceOf(TeamSettingsFailure.class);
  }
}

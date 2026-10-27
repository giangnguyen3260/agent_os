package com.agentos.taskediting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskDraftEditWireTest {
  private String body() throws Exception {
    return new ObjectMapper()
        .writeValueAsString(
            Map.of(
                "expectedRevision",
                0,
                "expectedFingerprint",
                "a".repeat(64),
                "title",
                "Title",
                "description",
                "Input",
                "acceptanceCriteria",
                "Criteria",
                "priority",
                "NORMAL"));
  }

  private TaskDraftEditDtos.Command read(String value) throws Exception {
    return TaskDraftEditWire.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void stripsUnicodeWhitespaceAndCountsCodepointsAndKeepsMultiline() throws Exception {
    assertThat(read(body().replace("Title", "  😀  ")).title()).isEqualTo("😀");
    assertThat(TaskDraftEditWire.safe("😀".repeat(255), 255, false)).hasSize(510);
    assertThat(TaskDraftEditWire.safe(" A\nB\tC\rD ", 8000, true)).isEqualTo("A\nB\tC\rD");
    assertThatThrownBy(() -> TaskDraftEditWire.safe("😀".repeat(256), 255, false))
        .hasMessage("BAD_REQUEST");
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "12", "true", "[]", "{}"})
  void textNeverCoerces(String value) throws Exception {
    String source = body();
    assertThatThrownBy(() -> read(source.replace("\"Title\"", value))).hasMessage("BAD_REQUEST");
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "2147483648", "0.0", "1e0", "\"0\"", "null", "true"})
  void revisionRequiresIntegerRange(String value) throws Exception {
    String source = body();
    assertThatThrownBy(
            () -> read(source.replace("\"expectedRevision\":0", "\"expectedRevision\":" + value)))
        .hasMessage("BAD_REQUEST");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\u0000", "\u000b", "\u007f", "\ud800", "\udc00", "x\ud800y"})
  void unsafeCharactersCannotDisappearDuringStrip(String value) {
    assertThatThrownBy(() -> TaskDraftEditWire.safe(value + "Title", 255, false))
        .hasMessage("BAD_REQUEST");
    assertThatThrownBy(() -> TaskDraftEditWire.safe(value + "Input", 8000, true))
        .hasMessage("BAD_REQUEST");
  }

  @Test
  void duplicateUnknownMissingTrailingAndOversizedBodiesRejected() throws Exception {
    String valid = body();
    for (String invalid :
        new String[] {
          valid.replace("\"title\":", "\"title\":\"first\",\"title\":"),
          valid.replace("\"title\":", "\"extra\":1,\"title\":"),
          valid.replace("\"priority\"", "\"missingPriority\""),
          valid + " {}",
          " ".repeat(65537),
          valid.replace("NORMAL", "MEDIUM"),
          valid.replace("Title", "\\nTitle")
        }) assertThatThrownBy(() -> read(invalid)).hasMessage("BAD_REQUEST");
    assertThatThrownBy(
            () ->
                TaskDraftEditWire.read(
                    new ByteArrayInputStream(new byte[] {(byte) 0xc0, (byte) 0xaf})))
        .hasMessage("BAD_REQUEST");
  }

  @Test
  void routeKeysRequireCanonicalRfc4122VersionAndVariant() {
    for (String invalid :
        new String[] {
          "123",
          "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
          "00000000-0000-0000-0000-000000000000",
          "11111111-1111-6111-8111-111111111111",
          "11111111-1111-4111-7111-111111111111"
        }) assertThatThrownBy(() -> TaskDraftEditWire.uuid(invalid)).hasMessage("BAD_REQUEST");
  }

  @Test
  void historySnapshotsPreserveHistoricalWhitespaceAndNullableFields() throws Exception {
    var mapper = new ObjectMapper();
    var expected = new TaskDraftEditDtos.Values("  Legacy  ", "  Input\n\t  ", null, null);
    assertThat(TaskDraftEditWire.historyValues(mapper.writeValueAsString(expected)))
        .isEqualTo(expected);
    assertThat(TaskDraftEditWire.historyValues(
        mapper.writeValueAsString(new TaskDraftEditDtos.Values("😀".repeat(255), null, null, "NORMAL"))))
        .hasFieldOrPropertyWithValue("priority", "NORMAL");
  }

  @Test
  void historySnapshotsRejectMalformedValuesWithoutCoercionOrNormalization() throws Exception {
    var mapper = new ObjectMapper();
    var valid = mapper.createObjectNode();
    valid.put("title", "Legacy");
    valid.putNull("description");
    valid.putNull("acceptanceCriteria");
    valid.putNull("priority");
    for (String field : new String[] {"title", "description", "acceptanceCriteria", "priority"}) {
      var wrongType = valid.deepCopy();
      wrongType.put(field, 123);
      assertThatThrownBy(() -> TaskDraftEditWire.historyValues(wrongType.toString()))
          .isInstanceOf(IllegalStateException.class);
    }
    var nullTitle = valid.deepCopy();
    nullTitle.putNull("title");
    var unknownField = valid.deepCopy();
    unknownField.put("actorId", "private");
    for (var invalid : java.util.List.of(nullTitle, unknownField))
      assertThatThrownBy(() -> TaskDraftEditWire.historyValues(invalid.toString()))
          .isInstanceOf(IllegalStateException.class);
    for (String field : new String[] {"title", "description", "acceptanceCriteria", "priority"}) {
      int max = field.equals("description") ? 8000 : field.equals("acceptanceCriteria") ? 4000 : 255;
      for (String text : new String[] {"x".repeat(max + 1), "\u0000text", "text\u007f", "\ud800"}) {
        var invalid = valid.deepCopy();
        invalid.put(field, text);
        assertThatThrownBy(() -> TaskDraftEditWire.historyValues(invalid.toString()))
            .isInstanceOf(IllegalStateException.class);
      }
    }
  }

  @Test
  void historyBeforePreservesSafeLegacyPriorityAndBlankText() throws Exception {
    var expected = new TaskDraftEditDtos.Values("  ", "", null, "  Old priority  ");
    assertThat(TaskDraftEditWire.historyValues(new ObjectMapper().writeValueAsString(expected)))
        .isEqualTo(expected);
  }

  @Test
  void historyAfterRequiresCompleteCanonicalCommandValues() throws Exception {
    var mapper = new ObjectMapper();
    var valid = mapper.valueToTree(new TaskDraftEditDtos.Values("Title", "Input", "Criteria", "NORMAL"));
    assertThat(TaskDraftEditWire.historyValues(valid.toString(), true))
        .isEqualTo(new TaskDraftEditDtos.Values("Title", "Input", "Criteria", "NORMAL"));
    for (String field : new String[] {"title", "description", "acceptanceCriteria", "priority"}) {
      for (String value : new String[] {null, "", " ", "  Padded  ", "\u3000"}) {
        var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) valid.deepCopy();
        invalid.put(field, value);
        assertThatThrownBy(() -> TaskDraftEditWire.historyValues(invalid.toString(), true))
            .isInstanceOf(IllegalStateException.class);
      }
    }
    var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) valid.deepCopy();
    unknown.put("priority", "MEDIUM");
    assertThatThrownBy(() -> TaskDraftEditWire.historyValues(unknown.toString(), true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void historyActorNamesMustBeSafeNonblankAndWithinCodepointLimit() {
    for (String invalid : new String[] {"", " ", "\u00a0", "\ufeff", "\u3000", "name\n", "\ud800", "x".repeat(256)})
      assertThatThrownBy(() -> TaskDraftEditWire.historyActorName(invalid))
          .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> TaskDraftEditWire.historyActorName(null))
        .isInstanceOf(IllegalStateException.class);
    TaskDraftEditWire.historyActorName("  Current name  ");
    TaskDraftEditWire.historyActorName("😀".repeat(255));
  }

  @Test
  void legacyAndMissingReceiptNullsRemainExplicitWithGlobalNonNullInclusion() {
    ObjectMapper mapper =
        new ObjectMapper()
            .setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    var values = mapper.valueToTree(new TaskDraftEditDtos.Values("Legacy", null, null, null));
    assertThat(values.size()).isEqualTo(4);
    assertThat(values.has("description")).isTrue();
    assertThat(values.get("description").isNull()).isTrue();
    assertThat(values.has("acceptanceCriteria")).isTrue();
    assertThat(values.get("acceptanceCriteria").isNull()).isTrue();
    assertThat(values.has("priority")).isTrue();
    assertThat(values.get("priority").isNull()).isTrue();
    var lookup = mapper.valueToTree(new TaskDraftEditDtos.ReceiptLookup(null));
    assertThat(lookup.size()).isEqualTo(1);
    assertThat(lookup.has("receipt")).isTrue();
    assertThat(lookup.get("receipt").isNull()).isTrue();
  }
}

package com.agentos.mission.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MissionPlanProposalParserTest {
  private final ObjectMapper json = new ObjectMapper();

  private String graph(int tasks, int edges) throws Exception {
    var nodes = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < tasks; i++) {
      var deps = new ArrayList<String>();
      for (int j = 0; j < i && edges > 0; j++, edges--) deps.add("t" + j);
      nodes.add(
          Map.of(
              "key",
              "t" + i,
              "title",
              "Title",
              "description",
              "Untrusted <script>text</script>",
              "acceptanceCriteria",
              "Output",
              "priority",
              "NORMAL",
              "dependencies",
              deps));
    }
    return json.writeValueAsString(Map.of("schemaVersion", 1, "tasks", nodes));
  }

  @Test
  void graphLimitsAndStableTopologicalOrder() throws Exception {
    var p = MissionPlanProposalParser.parse(graph(20, 100));
    assertThat(p.tasks()).hasSize(20);
    assertThat(p.tasks().stream().mapToInt(t -> t.dependencies().size()).sum()).isEqualTo(100);
    assertThat(p.order()).hasSize(20);
    for (var t : p.tasks())
      for (var d : t.dependencies())
        assertThat(p.order().indexOf(d)).isLessThan(p.order().indexOf(t.key()));
    assertThatThrownBy(() -> MissionPlanProposalParser.parse(graph(21, 100)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MissionPlanProposalParser.parse(graph(20, 101)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAmbiguityAuthorityAndMalformedGraphs() throws Exception {
    String valid = graph(2, 1);
    for (String invalid :
        List.of(
            "```json\n" + valid + "\n```",
            valid + " {}",
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace(
                "\"priority\":\"NORMAL\"",
                "\"priority\":\"NORMAL\",\"ownerAgentId\":\"untrusted\""),
            valid.replace("\"key\":\"t1\"", "\"key\":\"t0\""),
            valid.replace("\"dependencies\":[\"t0\"]", "\"dependencies\":[\"missing\"]"),
            valid.replace("\"dependencies\":[\"t0\"]", "\"dependencies\":[\"t1\"]"),
            valid.replace("\"dependencies\":[\"t0\"]", "\"dependencies\":[\"t0\",\"t0\"]"),
            valid.replace("\"dependencies\":[]", "\"dependencies\":[\"t1\"]")))
      assertThatThrownBy(() -> MissionPlanProposalParser.parse(invalid))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesLossyUnicodeBlankAndByteOverflow() throws Exception {
    String valid = graph(1, 0);
    for (String text :
        List.of(
            valid.replace("Title", " "),
            valid.replace("Title", "\\ud800"),
            valid.replace("Title", "x".repeat(256)),
            valid.replace("Title", "\\u0000"),
            " ".repeat(65537),
            "界".repeat(22000)))
      assertThatThrownBy(() -> MissionPlanProposalParser.parse(text))
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(MissionPlanProposalParser.parse(valid).tasks().getFirst().description())
        .contains("<script>");
  }
}

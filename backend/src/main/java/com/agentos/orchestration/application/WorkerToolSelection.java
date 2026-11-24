package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read inside the caller's authorized organization-serialized Start transaction. */
final class WorkerToolSelection {
  private final JdbcTemplate jdbc;

  WorkerToolSelection(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  String current(UUID org, UUID run, long revision) {
    var rows =
        jdbc.queryForList(
            "select fingerprint from worker_text_tool_approvals where organization_id=? and"
                + " run_id=? and source_revision=?",
            String.class,
            org,
            run,
            revision);
    if (rows.isEmpty()) return null;
    if (rows.size() != 1 || rows.getFirst() == null || !rows.getFirst().matches("[0-9a-f]{64}"))
      throw new IllegalStateException("Tool selection is unavailable");
    return rows.getFirst();
  }

  String bind(UUID org, UUID run, long revision, String original) {
    String selected = current(org, run, revision);
    return selected == null
        ? original
        : TextToolGrantService.hash("execution.start.tool@1|" + original + "|" + selected);
  }
}

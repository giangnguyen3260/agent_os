package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionReviewService {
  public record Review(
      UUID attemptId, long revision, String outputSha256, boolean reworkAvailable) {}

  public record Output(UUID attemptId, long revision, String outputSha256, String content) {}

  public static class Conflict extends IllegalStateException {
    public Conflict() {
      super("Execution output review decision is unavailable.");
    }
  }

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore decisions;
  private final ExecutionOutputContentStore outputs;

  ExecutionReviewService(
      JdbcTemplate jdbc,
      ExecutionReviewDecisionStore decisions,
      ExecutionOutputContentStore outputs) {
    this.jdbc = jdbc;
    this.decisions = decisions;
    this.outputs = outputs;
  }

  public record Attempt(UUID id, int number, String status) {}

  public record Accounting(
      String settledMicrousd,
      String heldMicrousd,
      String unknownMicrousd,
      String unresolvedReservations,
      String inputTokens,
      String outputTokens) {}

  public record Dispatch(
      UUID id, String state, String sourceRevision, String createdAt, boolean handedOff) {}

  public record ToolEffect(
      UUID id,
      UUID attemptId,
      String tool,
      int version,
      String inputSha256,
      int utf8Bytes,
      int codePoints,
      int lines,
      String recordedAt,
      String actualMicrousd) {}

  public record RunDetail(
      UUID id,
      UUID teamId,
      UUID missionId,
      UUID taskId,
      String status,
      String revision,
      String waitReason,
      java.util.List<Attempt> attempts,
      Accounting accounting,
      java.util.List<Dispatch> dispatches,
      java.util.List<ToolEffect> toolEffects) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RunDetail run(UUID organization, UUID run, HumanIdentity reviewer) {
    decisions.requireAdmin(organization, reviewer, false);
    var rows =
        jdbc.query(
            """
            select id,team_id,mission_id,task_id,status,revision,wait_reason
            from execution_runs where organization_id=? and id=?
            """,
            (rs, n) ->
                new RunDetail(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    java.util.List.of(),
                    null,
                    java.util.List.of(),
                    java.util.List.of()),
            organization,
            run);
    if (rows.isEmpty()) throw new EntityNotFoundException("Execution run not found");
    var r = rows.getFirst();
    var attempts =
        jdbc.query(
            """
            select id,attempt_number,status from execution_attempts
            where run_id=? order by attempt_number desc limit 100
            """,
            (rs, n) -> new Attempt(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)),
            run);
    return new RunDetail(
        r.id(),
        r.teamId(),
        r.missionId(),
        r.taskId(),
        r.status(),
        r.revision(),
        r.waitReason(),
        attempts,
        accounting(run),
        dispatches(organization, run),
        toolEffects(organization, run));
  }

  private java.util.List<ToolEffect> toolEffects(UUID org, UUID run) {
    return jdbc.query(
        "select"
            + " e.action_id,e.attempt_id,e.input_sha256,e.utf8_bytes,e.code_points,e.lines,e.recorded_at,b.actual_microusd"
            + " from text_tool_effects e join text_tool_grants g on"
            + " g.organization_id=e.organization_id and g.id=e.grant_id join"
            + " execution_budget_reservations b on b.run_id=e.run_id and b.action_id=e.action_id"
            + " and b.attempt_id=e.attempt_id and b.fence=e.fence where e.organization_id=? and"
            + " e.run_id=? and g.tool_version=1 and b.status='SETTLED' and b.actual_microusd=0"
            + " order by e.recorded_at desc,e.action_id desc limit 100",
        (rs, n) -> {
          String sha = rs.getString(3);
          int bytes = rs.getInt(4), points = rs.getInt(5), lines = rs.getInt(6);
          if (sha == null
              || !sha.matches("[0-9a-f]{64}")
              || bytes < 0
              || bytes > 8192
              || points < 0
              || points > bytes
              || lines < 0
              || lines > points + 1) throw new Conflict();
          return new ToolEffect(
              rs.getObject(1, UUID.class),
              rs.getObject(2, UUID.class),
              "text.metrics",
              1,
              sha,
              bytes,
              points,
              lines,
              rs.getObject(7, java.time.OffsetDateTime.class).toInstant().toString(),
              rs.getString(8));
        },
        org,
        run);
  }

  private java.util.List<Dispatch> dispatches(UUID org, UUID run) {
    return jdbc.query(
        "select d.id,d.state,d.source_run_revision,d.created_at,exists(select 1 from"
            + " execution_model_authorizations a where a.organization_id=d.organization_id and"
            + " a.run_id=d.run_id and a.action_id=d.id and a.handed_off_at is not null) as"
            + " handed_off from execution_start_dispatch d where d.organization_id=? and d.run_id=?"
            + " order by d.created_at desc,d.id desc limit 100",
        (rs, n) ->
            new Dispatch(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, java.time.OffsetDateTime.class).toInstant().toString(),
                rs.getBoolean(5)),
        org,
        run);
  }

  private Accounting accounting(UUID run) {
    return new RecordedAccountingStore(jdbc).run(run);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RunDetail taskRun(
      UUID organization, UUID team, UUID mission, UUID task, HumanIdentity reviewer) {
    decisions.requireAdmin(organization, reviewer, false);
    var ids =
        jdbc.queryForList(
            """
select id from execution_runs where organization_id=? and team_id=? and mission_id=? and task_id=?
""",
            UUID.class,
            organization,
            team,
            mission,
            task);
    if (ids.isEmpty()) throw new EntityNotFoundException("Execution run not found");
    return run(organization, ids.getFirst(), reviewer);
  }

  public record TaskReview(UUID runId) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public TaskReview forTask(
      UUID organization, UUID team, UUID mission, UUID task, HumanIdentity reviewer) {
    decisions.requireAdmin(organization, reviewer, false);
    var runs =
        jdbc.queryForList(
            """
            select r.id from execution_runs r
            where r.organization_id=? and r.team_id=? and r.mission_id=? and r.task_id=?
            """,
            UUID.class,
            organization,
            team,
            mission,
            task);
    if (runs.isEmpty() || decisions.current(organization, runs.getFirst()) == null) {
      throw new EntityNotFoundException("Execution review not found");
    }
    return new TaskReview(runs.getFirst());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Review current(UUID organization, UUID run, HumanIdentity reviewer) {
    decisions.requireAdmin(organization, reviewer, false);
    var target = decisions.current(organization, run);
    if (target == null) throw new EntityNotFoundException("Execution review not found");
    return new Review(
        target.attempt(), target.revision(), target.outputSha256(), target.reworkAvailable());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Output output(
      UUID organization, UUID run, HumanIdentity reviewer, UUID attempt, long revision) {
    var review = current(organization, run, reviewer);
    if (!review.attemptId().equals(attempt) || review.revision() != revision) throw new Conflict();
    return new Output(
        review.attemptId(),
        review.revision(),
        review.outputSha256(),
        outputs.read(run, attempt, review.outputSha256()));
  }

  @Transactional
  public void decide(
      UUID organization,
      UUID run,
      HumanIdentity reviewer,
      UUID attemptId,
      Long revision,
      String outputSha256,
      String decision,
      String reason) {
    if (revision == null || !("ACCEPT".equals(decision) || "REWORK".equals(decision))) {
      throw new IllegalArgumentException("Invalid review decision");
    }
    decisions.decide(
        organization,
        reviewer,
        run,
        new ExecutionReviewDecisionStore.Request(
            attemptId,
            revision,
            outputSha256,
            ExecutionReviewDecisionStore.Decision.valueOf(decision),
            reason));
  }
}

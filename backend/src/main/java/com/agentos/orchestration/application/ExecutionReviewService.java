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

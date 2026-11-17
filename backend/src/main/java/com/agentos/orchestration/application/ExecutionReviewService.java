package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionReviewService {
  public record Review(
      UUID attemptId, long revision, String outputSha256, boolean reworkAvailable) {}

  public static class Conflict extends IllegalStateException {
    public Conflict() {
      super("Execution output review decision is unavailable.");
    }
  }

  private final ExecutionReviewDecisionStore decisions;

  ExecutionReviewService(ExecutionReviewDecisionStore decisions) {
    this.decisions = decisions;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Review current(UUID organization, UUID run, HumanIdentity reviewer) {
    decisions.requireAdmin(organization, reviewer, false);
    var target = decisions.current(organization, run);
    if (target == null) throw new EntityNotFoundException("Execution review not found");
    return new Review(
        target.attempt(), target.revision(), target.outputSha256(), target.reworkAvailable());
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

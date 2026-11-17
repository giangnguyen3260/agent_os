package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records a human decision against an immutable review submission. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionReviewDecisionStore {
  enum Decision {
    ACCEPT,
    REWORK
  }

  record Request(
      UUID attempt, long revision, String outputSha256, Decision decision, String reason) {}

  private record Receipt(UUID reviewer, Request request) {}

  private record Target(UUID task, UUID mission) {}

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;

  ExecutionReviewDecisionStore(JdbcTemplate jdbc, ExecutionClaimStore claims) {
    this.jdbc = jdbc;
    this.claims = claims;
  }

  void decide(UUID organization, HumanIdentity reviewer, UUID run, Request request) {
    requireAdmin(organization, reviewer);
    request = canonical(request);
    claims.lockRun(organization, run, false);
    var receipts =
        jdbc.query(
            """
select d.attempt_id,d.reviewer_id,d.source_run_revision,e.output_sha256,d.decision,d.reason
from execution_review_decisions d
join execution_attempt_reconciliations e on e.run_id=d.run_id and e.attempt_id=d.attempt_id
where d.run_id=? and d.attempt_id=?
""",
            (rs, row) ->
                new Receipt(
                    rs.getObject("reviewer_id", UUID.class),
                    new Request(
                        rs.getObject("attempt_id", UUID.class),
                        rs.getLong("source_run_revision"),
                        rs.getString("output_sha256"),
                        Decision.valueOf(rs.getString("decision")),
                        rs.getString("reason"))),
            run,
            request.attempt());
    if (!receipts.isEmpty()) {
      if (receipts.getFirst().equals(new Receipt(reviewer.id(), request))) return;
      throw unavailable();
    }

    var target = requireTarget(organization, run, request);
    var at = claims.now();
    boolean rework = request.decision() == Decision.REWORK;
    if (jdbc.update(
            """
            update tasks set status=?,updated_at=?
            where organization_id=? and id=? and status='IN_REVIEW'
            """,
            rework ? "READY" : "COMPLETED",
            at,
            organization,
            target.task())
        != 1) throw unavailable();
    jdbc.update(
        "update execution_runs set status=?,revision=revision+1 where id=?",
        rework ? "CREATED" : "COMPLETED",
        run);
    jdbc.update(
        """
        insert into execution_review_decisions
          (run_id,attempt_id,reviewer_id,source_run_revision,decision,reason,decided_at)
        values (?,?,?,?,?,?,?)
        """,
        run,
        request.attempt(),
        reviewer.id(),
        request.revision(),
        request.decision().name(),
        request.reason(),
        at);
    jdbc.update(
        """
insert into audit_events (id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)
values (?,?,?,?,'HUMAN',?,?,?,?)
""",
        UUID.randomUUID(),
        organization,
        target.mission(),
        target.task(),
        reviewer.id(),
        rework ? "EXECUTION_REWORK_REQUESTED" : "EXECUTION_OUTPUT_ACCEPTED",
        "{\"runId\":\"" + run + "\",\"attemptId\":\"" + request.attempt() + "\"}",
        at);
  }

  private Target requireTarget(UUID organization, UUID run, Request request) {
    var targets =
        jdbc.query(
            """
select r.task_id,r.mission_id from execution_runs r
join execution_reconciled_review_submissions s on s.run_id=r.id
join execution_attempt_reconciliations e on e.run_id=s.run_id and e.attempt_id=s.attempt_id
join mission_execution_controls c on c.organization_id=r.organization_id
  and c.team_id=r.team_id and c.mission_id=r.mission_id
join missions m on m.organization_id=r.organization_id and m.team_id=r.team_id and m.id=r.mission_id
where r.organization_id=? and r.id=? and r.status='IN_REVIEW' and r.revision=?
  and s.source_run_revision=r.revision-1 and s.attempt_id=? and e.output_sha256=?
  and m.status in ('RUNNING','WAITING_APPROVAL')
  and c.state in ('OPEN','PAUSE_REQUESTED')
  and (?='ACCEPT' or r.max_reworks > (select count(*) from execution_review_decisions d
    where d.run_id=r.id and d.decision='REWORK'))
""",
            (rs, row) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
            organization,
            run,
            request.revision(),
            request.attempt(),
            request.outputSha256(),
            request.decision().name());
    if (targets.isEmpty()) throw unavailable();
    return targets.getFirst();
  }

  private void requireAdmin(UUID organization, HumanIdentity reviewer) {
    if (reviewer == null
        || jdbc.queryForList(
                "select id from organizations where id=? for update", UUID.class, organization)
            .isEmpty()
        || jdbc.queryForList(
                "select id from human_accounts where id=? and enabled=true and credential_version=?"
                    + " for update",
                UUID.class,
                reviewer.id(),
                reviewer.credentialVersion())
            .isEmpty()
        || jdbc.queryForList(
                "select role from organization_memberships where organization_id=? and account_id=?"
                    + " and role='ADMIN' for update",
                String.class,
                organization,
                reviewer.id())
            .isEmpty()) {
      throw new AccessDeniedException("Request denied");
    }
  }

  private static Request canonical(Request request) {
    if (request == null
        || request.attempt() == null
        || request.revision() <= 0
        || request.revision() == Long.MAX_VALUE
        || request.decision() == null
        || request.outputSha256() == null
        || !request.outputSha256().matches("[0-9a-f]{64}")) {
      throw unavailable();
    }
    String reason = request.reason() == null ? null : request.reason().strip();
    if (request.decision() == Decision.ACCEPT
        ? reason != null
        : reason == null || reason.isEmpty() || reason.length() > 1000) throw unavailable();
    return new Request(
        request.attempt(), request.revision(), request.outputSha256(), request.decision(), reason);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Execution output review decision is unavailable.");
  }
}

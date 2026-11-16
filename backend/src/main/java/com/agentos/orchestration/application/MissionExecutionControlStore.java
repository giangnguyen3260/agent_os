package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Used by authorized orchestration commands within their lifecycle transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class MissionExecutionControlStore {
  enum State {
    OPEN,
    PAUSE_REQUESTED,
    CANCEL_REQUESTED
  }

  record Scope(UUID organization, UUID team, UUID mission) {}

  private final JdbcTemplate jdbc;

  MissionExecutionControlStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void initialize(Scope scope) {
    lockMission(scope);
    jdbc.update(
        """
        insert into mission_execution_controls (organization_id,team_id,mission_id,revision,state)
        values (?,?,?,1,'OPEN')
        """,
        scope.organization(),
        scope.team(),
        scope.mission());
  }

  long change(Scope scope, long expectedRevision, State next) {
    if (next == null || expectedRevision <= 0 || expectedRevision == Long.MAX_VALUE) throw denied();
    lockMission(scope);
    if (jdbc.update(
            """
            update mission_execution_controls set revision=revision+1,state=?
            where organization_id=? and team_id=? and mission_id=? and revision=?
              and state<>'CANCEL_REQUESTED' and state<>?
            """,
            next.name(),
            scope.organization(),
            scope.team(),
            scope.mission(),
            expectedRevision,
            next.name())
        != 1) throw denied();

    if (next == State.CANCEL_REQUESTED) requestRunCancellation(scope);
    return expectedRevision + 1;
  }

  // Claim and authorization callers already hold the organization and Run locks.
  long requireOpen(UUID organization, UUID run) {
    var revisions =
        jdbc.queryForList(
            """
            select c.revision from mission_execution_controls c
            where c.organization_id=? and c.state='OPEN' and exists (
              select 1 from execution_runs r where r.id=? and r.organization_id=c.organization_id
                and r.team_id=c.team_id and r.mission_id=c.mission_id
            ) for update
            """,
            Long.class,
            organization,
            run);
    if (revisions.isEmpty()) throw denied();
    return revisions.getFirst();
  }

  private void lockMission(Scope scope) {
    if (jdbc.queryForList(
            "select id from organizations where id=? for update", UUID.class, scope.organization())
        .isEmpty()) throw denied();
    if (jdbc.queryForList(
            """
            select id from missions where organization_id=? and team_id=? and id=?
              and status in ('RUNNING','WAITING_APPROVAL') for update
            """,
            UUID.class,
            scope.organization(),
            scope.team(),
            scope.mission())
        .isEmpty()) {
      throw denied();
    }
  }

  private void requestRunCancellation(Scope scope) {
    jdbc.update(
        """
        update execution_attempts
        set status=case when status='CREATED' then 'CANCELLED' else 'CANCELLING' end,
            outcome_slot=case when status='CREATED' then attempt_number else outcome_slot end
        where status in ('CREATED','RUNNING','WAITING_INPUT','WAITING_APPROVAL') and run_id in (
          select id from execution_runs where organization_id=? and team_id=? and mission_id=?
            and status not in ('COMPLETED','CANCELLED')
        )
        """,
        scope.organization(),
        scope.team(),
        scope.mission());
    jdbc.update(
        """
        update execution_runs set status='CANCELLING',wait_reason=null,revision=revision+1
        where organization_id=? and team_id=? and mission_id=?
          and status not in ('COMPLETED','CANCELLED','CANCELLING')
        """,
        scope.organization(),
        scope.team(),
        scope.mission());
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Mission execution control is unavailable.");
  }
}

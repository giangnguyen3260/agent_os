package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal Start admission. Public ingress requires the worker assembly to be completed. */
@Service
class ExecutionStartService {
  record Receipt(UUID dispatchId, UUID runId, long sourceRevision) {}

  @org.springframework.beans.factory.annotation.Value(
      "${agentos.mission-graph-admission.enabled:false}")
  private boolean graphAdmissionEnabled;

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;

  ExecutionStartService(JdbcTemplate jdbc, ExecutionReviewDecisionStore authority) {
    this.jdbc = jdbc;
    this.authority = authority;
  }

  @Transactional
  public Receipt start(UUID organization, UUID run, long revision, UUID key, HumanIdentity actor) {
    if (organization == null
        || run == null
        || key == null
        || revision < 0
        || revision == Long.MAX_VALUE)
      throw new IllegalArgumentException("Invalid execution Start");
    authority.requireAdmin(organization, actor, true);
    var receipts =
        jdbc.query(
            """
            select id,run_id,source_run_revision from execution_start_dispatch
            where organization_id=? and actor_id=? and idempotency_key=?
            """,
            (rs, n) ->
                new Receipt(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)),
            organization,
            actor.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!receipt.runId().equals(run) || receipt.sourceRevision() != revision) throw unavailable();
      return receipt;
    }
    if (!jdbc.queryForList(
            "select id from execution_start_dispatch where run_id=? and source_run_revision=?",
            UUID.class,
            run,
            revision)
        .isEmpty()) throw unavailable();
    requireEligible(organization, run, revision);
    var receipt = new Receipt(UUID.randomUUID(), run, revision);
    jdbc.update(
        """
insert into execution_start_dispatch(id,organization_id,run_id,source_run_revision,actor_id,idempotency_key,actor_credential_version,state)
values(?,?,?,?,?,?,?,'QUEUED')
""",
        receipt.dispatchId(),
        organization,
        run,
        revision,
        actor.id(),
        key,
        actor.credentialVersion());
    return receipt;
  }

  void requireEligible(UUID organization, UUID run, long revision) {
    new MissionGraphDependencyStore(jdbc, graphAdmissionEnabled).requireStart(organization, run);
    var eligible =
        jdbc.queryForList(
            """
select r.id from execution_runs r
join missions m on m.organization_id=r.organization_id and m.team_id=r.team_id and m.id=r.mission_id
join mission_execution_controls c on c.organization_id=r.organization_id and c.team_id=r.team_id and c.mission_id=r.mission_id
join teams g on g.organization_id=r.organization_id and g.id=r.team_id
join tasks t on t.organization_id=r.organization_id and t.team_id=r.team_id and t.mission_id=r.mission_id and t.id=r.task_id
join accepted_mission_plan_tasks p on p.organization_id=r.organization_id and p.team_id=r.team_id and p.mission_id=r.mission_id and p.plan_revision=r.plan_revision and p.task_id=r.task_id
join agents a on a.organization_id=r.organization_id and a.team_id=r.team_id and a.id=p.owner_agent_id
where exists(select 1 from organizations o where o.id=r.organization_id and o.status='ACTIVE') and r.organization_id=? and r.id=? and r.revision=? and r.status='CREATED'
  and m.status='RUNNING' and c.state='OPEN' and g.status='ACTIVE' and a.status='ACTIVE'
  and t.status='READY' and t.owner_agent_id=p.owner_agent_id
  and t.task_edit_revision=p.task_edit_revision and t.task_owner_revision=p.task_owner_revision
  and t.dependency_revision=p.dependency_revision
  and not exists (select 1 from execution_attempts x where x.run_id=r.id and x.outcome_slot=0)
  and not exists (select 1 from execution_budget_reservations b where b.run_id=r.id and b.status<>'SETTLED')
  and not exists (select 1 from execution_claims x where x.run_id=r.id and x.invalidated_at is null and x.expires_at>current_timestamp)
  and not exists (
    select 1 from accepted_mission_plan_dependencies d
    join tasks prerequisite on prerequisite.organization_id=d.organization_id and prerequisite.team_id=d.team_id
      and prerequisite.mission_id=d.mission_id and prerequisite.id=d.dependency_task_id
    where d.organization_id=r.organization_id and d.team_id=r.team_id and d.mission_id=r.mission_id
      and d.plan_revision=r.plan_revision and d.task_id=r.task_id and prerequisite.status<>'COMPLETED')
""",
            UUID.class,
            organization,
            run,
            revision);
    if (eligible.size() != 1) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Execution Start is unavailable.");
  }
}

package com.agentos.orchestration.application;

import com.agentos.agent.application.AgentModelGrantService;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records the authorization decision inside an orchestration transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionModelAuthorizationStore {
  record Request(UUID action, UUID connection, String model, String sha256, long maximumMicrousd) {}

  record Handoff(UUID connection, String model, long credentialRevision, ByteBuffer body) {}

  private record Target(UUID agent, long credentialRevision, long controlRevision) {}

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;
  private final ExecutionBudgetStore budgets;
  private final MissionExecutionControlStore controls;
  private final AgentModelGrantService grants;
  private final boolean discoveryEnabled;

  ExecutionModelAuthorizationStore(
      JdbcTemplate jdbc,
      ExecutionClaimStore claims,
      ExecutionBudgetStore budgets,
      MissionExecutionControlStore controls,
      AgentModelGrantService grants,
      @Value("${agentos.provider-model-discovery.http-enabled:false}") boolean discoveryEnabled) {
    this.jdbc = jdbc;
    this.claims = claims;
    this.budgets = budgets;
    this.controls = controls;
    this.grants = grants;
    this.discoveryEnabled = discoveryEnabled;
  }

  // Each action admits at most one authorization. Replays never authorize a second send.
  void authorize(ExecutionClaimStore.Token token, Request request) {
    if (!discoveryEnabled
        || request == null
        || request.action() == null
        || request.connection() == null
        || request.model() == null
        || request.model().isBlank()
        || request.model().length() > 256
        || request.sha256() == null
        || !request.sha256().matches("[0-9a-f]{64}")) throw denied();
    var target = requireTarget(token, request.connection(), request.model());
    if (!budgets.reserve(token, request.action(), request.maximumMicrousd())) throw denied();

    claims.requireCurrent(token);
    jdbc.update(
        """
        insert into execution_model_authorizations
          (organization_id,run_id,action_id,attempt_id,fence,agent_id,connection_id,
           credential_revision,model_id,request_sha256,authorized_at,control_revision)
        values (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        token.organization(),
        token.run(),
        request.action(),
        token.attempt(),
        token.fence(),
        target.agent(),
        request.connection(),
        target.credentialRevision(),
        request.model(),
        request.sha256(),
        claims.now(),
        target.controlRevision());
  }

  // Transport may use this body only after the enclosing transaction commits.
  Handoff handoff(
      ExecutionClaimStore.Token token, UUID action, UUID connection, String model, byte[] body) {
    if (!discoveryEnabled
        || action == null
        || connection == null
        || model == null
        || model.isBlank()
        || model.length() > 256
        || body == null
        || body.length == 0) throw denied();
    byte[] exactBody = body.clone();
    var target = requireTarget(token, connection, model);
    String digest = digest(exactBody);
    claims.requireCurrent(token);
    if (jdbc.update(
            """
update execution_model_authorizations a set handed_off_at=?
where a.organization_id=? and a.run_id=? and a.action_id=? and a.attempt_id=? and a.fence=?
  and a.agent_id=? and a.connection_id=? and a.model_id=? and a.request_sha256=?
  and a.credential_revision=? and a.control_revision=? and a.handed_off_at is null
  and exists (select 1 from execution_budget_reservations b
    where b.run_id=a.run_id and b.action_id=a.action_id and b.status='HELD')
""",
            claims.now(),
            token.organization(),
            token.run(),
            action,
            token.attempt(),
            token.fence(),
            target.agent(),
            connection,
            model,
            digest,
            target.credentialRevision(),
            target.controlRevision())
        != 1) throw denied();
    return new Handoff(
        connection,
        model,
        target.credentialRevision(),
        ByteBuffer.wrap(exactBody).asReadOnlyBuffer());
  }

  boolean configurationGranted(UUID organization, UUID agent, UUID connection, String model) {
    return discoveryEnabled && grants.hasCurrentGrant(organization, agent, connection, model);
  }

  private Target requireTarget(ExecutionClaimStore.Token token, UUID connection, String model) {
    claims.requireCurrent(token);
    long controlRevision = controls.requireOpen(token.organization(), token.run());
    var owners =
        jdbc.queryForList(
            """
select p.owner_agent_id
from execution_runs r
join accepted_mission_plan_tasks p on p.organization_id=r.organization_id
  and p.team_id=r.team_id and p.mission_id=r.mission_id
  and p.plan_revision=r.plan_revision and p.task_id=r.task_id
join teams g on g.organization_id=r.organization_id and g.id=r.team_id
join missions m on m.organization_id=r.organization_id and m.team_id=r.team_id and m.id=r.mission_id
join tasks t on t.organization_id=r.organization_id and t.team_id=r.team_id
  and t.mission_id=r.mission_id and t.id=r.task_id
join agents a on a.organization_id=r.organization_id and a.team_id=r.team_id and a.id=p.owner_agent_id
where exists(select 1 from organizations o where o.id=r.organization_id and o.status='ACTIVE') and r.organization_id=? and r.id=? and g.status='ACTIVE' and a.status='ACTIVE'
  and m.status='RUNNING' and t.status='RUNNING' and t.owner_agent_id=p.owner_agent_id
  and t.task_edit_revision=p.task_edit_revision and t.task_owner_revision=p.task_owner_revision
  and t.dependency_revision=p.dependency_revision
""",
            UUID.class,
            token.organization(),
            token.run());
    if (owners.isEmpty()) throw denied();
    if (jdbc.queryForList("select id from provider_deployment_policy where id=1 for update")
        .isEmpty()) throw denied();
    var connectionRevisions =
        jdbc.queryForList(
            """
            select credential_revision from provider_connections
            where organization_id=? and id=? and state='ENABLED' for update
            """,
            Long.class,
            token.organization(),
            connection);
    UUID agent = owners.getFirst();
    if (connectionRevisions.isEmpty()
        || !grants.hasCurrentGrant(token.organization(), agent, connection, model)) throw denied();
    return new Target(agent, connectionRevisions.getFirst(), controlRevision);
  }

  private static String digest(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Model action authorization is unavailable.");
  }
}

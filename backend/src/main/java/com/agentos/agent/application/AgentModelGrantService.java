package com.agentos.agent.application;

import com.agentos.agent.application.AgentDraftService.Conflict;
import com.agentos.auth.HumanIdentity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentModelGrantService {
  public record Request(int expectedRevision, int expectedBindingRevision, boolean enabled) {}

  public enum GrantState {
    ABSENT,
    GRANTED,
    REVOKED,
    STALE,
    UNAVAILABLE
  }

  public record State(
      int revision, int bindingRevision, GrantState state, boolean canGrant, boolean canRevoke) {}

  private record StoredGrant(int revision, int bindingRevision, boolean enabled) {}

  private record Receipt(Request request, boolean canGrant) {}

  private final AgentModelBindingService bindings;
  private final JdbcTemplate jdbc;

  public AgentModelGrantService(AgentModelBindingService bindings, JdbcTemplate jdbc) {
    this.bindings = bindings;
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public State get(UUID organization, HumanIdentity actor, UUID agent) {
    var binding = bindings.get(organization, actor, agent);
    return project(binding, current(organization, agent));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public State put(UUID organization, HumanIdentity actor, UUID agent, UUID key, Request request) {
    boolean draft = bindings.lockEditable(organization, actor, agent);
    var receipts =
        jdbc.query(
            """
            select expected_revision,expected_binding_revision,requested_enabled,response_can_grant
            from agent_model_grant_receipts
            where organization_id=? and agent_id=? and account_id=? and idempotency_key=?
            """,
            (rs, row) ->
                new Receipt(
                    new Request(rs.getInt(1), rs.getInt(2), rs.getBoolean(3)), rs.getBoolean(4)),
            organization,
            agent,
            actor.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!receipt.request().equals(request)) throw new Conflict("IDEMPOTENCY_CONFLICT");
      return committed(request, receipt.canGrant());
    }

    var binding = bindings.state(organization, agent, draft);
    var grant = current(organization, agent);
    if (grant.revision() != request.expectedRevision()
        || binding.revision() != request.expectedBindingRevision())
      throw new Conflict("CONFIGURATION_CONFLICT");
    if (grant.revision() == Integer.MAX_VALUE) throw new Conflict("REVISION_EXHAUSTED");

    if (request.enabled()) {
      if (!draft) throw new Conflict("AGENT_NOT_EDITABLE");
      if (binding.binding() == null) throw new Conflict("MODEL_UNAVAILABLE");
      bindings.lockConnection(organization, binding.binding().connectionId());
      binding = bindings.state(organization, agent, draft);
      if (!currentBinding(binding)) throw new Conflict("MODEL_UNAVAILABLE");
      if (grant.enabled() && grant.bindingRevision() == binding.revision())
        throw new Conflict("NO_CHANGE");
    } else if (!grant.enabled()) {
      throw new Conflict("NO_CHANGE");
    }

    boolean canGrant = !request.enabled() && draft && currentBinding(binding);
    var result = committed(request, canGrant);
    int pinnedRevision = request.enabled() ? binding.revision() : grant.bindingRevision();
    if (grant.revision() == 0) {
      jdbc.update(
          """
insert into agent_model_grants (organization_id,agent_id,revision,binding_revision,enabled)
values (?,?,?,?,?)
""",
          organization,
          agent,
          result.revision(),
          pinnedRevision,
          request.enabled());
    } else if (jdbc.update(
            """
            update agent_model_grants set revision=?,binding_revision=?,enabled=?
            where organization_id=? and agent_id=? and revision=?
            """,
            result.revision(),
            pinnedRevision,
            request.enabled(),
            organization,
            agent,
            request.expectedRevision())
        != 1) {
      throw new Conflict("CONFIGURATION_CONFLICT");
    }
    jdbc.update(
        """
        insert into agent_model_grant_receipts
          (organization_id,agent_id,account_id,idempotency_key,expected_revision,
           expected_binding_revision,requested_enabled,response_can_grant)
        values (?,?,?,?,?,?,?,?)
        """,
        organization,
        agent,
        actor.id(),
        key,
        request.expectedRevision(),
        request.expectedBindingRevision(),
        request.enabled(),
        result.canGrant());
    return result;
  }

  private StoredGrant current(UUID organization, UUID agent) {
    var rows =
        jdbc.query(
            """
            select revision,binding_revision,enabled from agent_model_grants
            where organization_id=? and agent_id=?
            """,
            (rs, row) -> new StoredGrant(rs.getInt(1), rs.getInt(2), rs.getBoolean(3)),
            organization,
            agent);
    return rows.isEmpty() ? new StoredGrant(0, 0, false) : rows.getFirst();
  }

  private static State project(AgentModelBindingService.State binding, StoredGrant grant) {
    boolean pinned = grant.enabled() && grant.bindingRevision() == binding.revision();
    boolean current = currentBinding(binding);
    GrantState state;
    if (grant.revision() == 0) state = GrantState.ABSENT;
    else if (!grant.enabled()) state = GrantState.REVOKED;
    else if (!pinned) state = GrantState.STALE;
    else if (!current) state = GrantState.UNAVAILABLE;
    else state = GrantState.GRANTED;
    return new State(
        grant.revision(),
        binding.revision(),
        state,
        binding.editable() && current && !pinned,
        grant.enabled());
  }

  private static State committed(Request request, boolean canGrant) {
    return new State(
        request.expectedRevision() + 1,
        request.expectedBindingRevision(),
        request.enabled() ? GrantState.GRANTED : GrantState.REVOKED,
        canGrant,
        request.enabled());
  }

  private static boolean currentBinding(AgentModelBindingService.State binding) {
    return binding.binding() != null && "CURRENT".equals(binding.binding().status());
  }
}

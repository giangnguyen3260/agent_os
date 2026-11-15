package com.agentos.agent.application;

import com.agentos.agent.application.AgentDraftService.Conflict;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentModelBindingService {
  public record Request(int expectedRevision, Selection selection) {}

  public record Selection(UUID connectionId, UUID checkId, String modelId) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Binding(
      UUID connectionId,
      String connectionName,
      String provider,
      UUID checkId,
      String modelId,
      String displayName,
      String status) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record State(int revision, boolean editable, Binding binding) {}

  public record Candidate(
      UUID connectionId,
      String connectionName,
      String provider,
      UUID checkId,
      String credentialRevision,
      int modelCount,
      String catalogueState) {}

  public record Connections(List<Candidate> items) {}

  private record Receipt(String digest, String response) {}

  private static final String PROVIDER_ENABLED =
      """
      coalesce(case c.provider
        when 'OPENAI' then p.openai_enabled
        when 'ANTHROPIC' then p.anthropic_enabled
        when 'GOOGLE_GEMINI' then p.gemini_enabled
        else false end, false)
      """;

  private static final String CREDENTIAL_AVAILABLE =
      "coalesce(agentos_list_read.credential_available(c.organization_id,c.id),false)";

  // Compare against the most recently accepted Discovery even if pending or failed.
  // Check freshness only after selecting that attempt; never fall back to older evidence.
  private static final String CANDIDATE_SOURCE =
      """
      from provider_connections c
      join provider_access_checks k
        on k.organization_id=c.organization_id and k.connection_id=c.id
      join provider_model_discovery_snapshots s
        on s.organization_id=c.organization_id and s.connection_id=c.id and s.check_id=k.id
      join provider_model_discovery_entries e
        on e.organization_id=c.organization_id and e.connection_id=c.id and e.check_id=k.id
      join provider_deployment_policy p on p.id=1
      where c.organization_id=? and c.state='ENABLED' and k.check_kind='MODEL_DISCOVERY'
        and k.outcome='ACCESSIBLE' and k.completed_at>?
        and k.connection_revision=c.configuration_revision
        and k.credential_revision=c.credential_revision
        and k.policy_revision=p.revision
        and k.id=(
            select latest.id from provider_access_checks latest
            where latest.organization_id=c.organization_id and latest.connection_id=c.id
              and latest.check_kind='MODEL_DISCOVERY'
            order by latest.accepted_at desc,latest.id desc limit 1
        )
      """
          + " and "
          + PROVIDER_ENABLED
          + " and "
          + CREDENTIAL_AVAILABLE
          + "\n";

  private final AgentDraftService drafts;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AgentModelBindingService(AgentDraftService drafts, JdbcTemplate jdbc, ObjectMapper json) {
    this.drafts = drafts;
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public State get(UUID organization, HumanIdentity actor, UUID agent) {
    drafts.authorize(organization, actor, true, false);
    return state(organization, agent, editable(organization, agent, false));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Connections connections(UUID organization, HumanIdentity actor, UUID agent) {
    drafts.authorize(organization, actor, true, false);
    if (!editable(organization, agent, false)) return new Connections(List.of());
    return new Connections(
        jdbc.query(
            """
            select c.id,c.name,c.provider,k.id as check_id,k.credential_revision,
                   count(*) as model_count,s.catalogue_state
            """
                + CANDIDATE_SOURCE
                + """
                  group by c.id,c.name,c.provider,k.id,k.credential_revision,s.catalogue_state
                  order by c.name,c.id limit 100
                  """,
            (rs, row) ->
                new Candidate(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("provider"),
                    rs.getObject("check_id", UUID.class),
                    Long.toString(rs.getLong("credential_revision")),
                    rs.getInt("model_count"),
                    rs.getString("catalogue_state")),
            organization,
            freshnessCutoff()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public State put(UUID organization, HumanIdentity actor, UUID agent, UUID key, Request request) {
    // Reject unauthorized callers before holding the global policy lock. Recheck under locks.
    drafts.authorize(organization, actor, true, false);
    jdbc.queryForList("select id from organizations where id=? for update", organization);
    jdbc.queryForList("select id from provider_deployment_policy where id=1 for update");
    drafts.authorize(organization, actor, true, true);
    boolean draft = editable(organization, agent, true);
    String digest = digest(request);
    var receipts =
        jdbc.query(
            """
            select body_sha256,original_response from agent_model_binding_receipts
            where organization_id=? and agent_id=? and account_id=? and idempotency_key=?
            """,
            (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)),
            organization,
            agent,
            actor.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!receipt.digest().equals(digest)) throw new Conflict("IDEMPOTENCY_CONFLICT");
      return decode(receipt.response());
    }

    if (!draft) throw new Conflict("AGENT_NOT_EDITABLE");
    var before = state(organization, agent, draft);
    if (before.revision() != request.expectedRevision())
      throw new Conflict("CONFIGURATION_CONFLICT");
    if (before.revision() == Integer.MAX_VALUE) throw new Conflict("REVISION_EXHAUSTED");
    var selected = request.selection();
    var previous =
        before.binding() == null
            ? null
            : new Selection(
                before.binding().connectionId(),
                before.binding().checkId(),
                before.binding().modelId());
    if (java.util.Objects.equals(previous, selected)) throw new Conflict("NO_CHANGE");
    if (selected != null) validateSelection(organization, selected);

    int revision = before.revision() + 1;
    UUID connection = selected == null ? null : selected.connectionId();
    UUID check = selected == null ? null : selected.checkId();
    String model = selected == null ? null : selected.modelId();
    if (before.revision() == 0) {
      jdbc.update(
          """
          insert into agent_model_bindings
              (organization_id,agent_id,revision,connection_id,check_id,model_id)
          values (?,?,?,?,?,?)
          """,
          organization,
          agent,
          revision,
          connection,
          check,
          model);
    } else if (jdbc.update(
            """
            update agent_model_bindings set revision=?,connection_id=?,check_id=?,model_id=?
            where organization_id=? and agent_id=? and revision=?
            """,
            revision,
            connection,
            check,
            model,
            organization,
            agent,
            before.revision())
        != 1) throw new Conflict("CONFIGURATION_CONFLICT");

    var committed = state(organization, agent, true);
    jdbc.update(
        """
        insert into agent_model_binding_receipts
            (organization_id,agent_id,account_id,idempotency_key,body_sha256,original_response)
        values (?,?,?,?,?,?)
        """,
        organization,
        agent,
        actor.id(),
        key,
        digest,
        encode(committed));
    return committed;
  }

  private void validateSelection(UUID organization, Selection selected) {
    // Evidence publication also fences on the provider connection row.
    jdbc.queryForList(
        "select id from provider_connections where organization_id=? and id=? for update",
        organization,
        selected.connectionId());
    var exists =
        jdbc.queryForObject(
            "select count(*)>0\n" + CANDIDATE_SOURCE + " and c.id=? and k.id=? and e.model_id=?",
            Boolean.class,
            organization,
            freshnessCutoff(),
            selected.connectionId(),
            selected.checkId(),
            selected.modelId());
    if (!Boolean.TRUE.equals(exists)) throw new Conflict("MODEL_UNAVAILABLE");
  }

  private boolean editable(UUID organization, UUID agent, boolean lock) {
    var statuses =
        jdbc.queryForList(
            "select status from agents where organization_id=? and id=?"
                + (lock ? " for update" : ""),
            String.class,
            organization,
            agent);
    if (statuses.isEmpty()) throw new EntityNotFoundException("Agent not found");
    return "DRAFT".equals(statuses.getFirst());
  }

  private State state(UUID organization, UUID agent, boolean editable) {
    var rows =
        jdbc.query(
            """
select b.revision,b.connection_id,b.check_id,b.model_id,c.name,c.provider,e.display_name,
case
  when b.connection_id is null then null
  when c.state <> 'ENABLED' or not (
"""
                + PROVIDER_ENABLED
                + " and "
                + CREDENTIAL_AVAILABLE
                + """
) then 'UNAVAILABLE'
when k.credential_revision<>c.credential_revision
  or k.connection_revision<>c.configuration_revision
  or k.policy_revision<>p.revision then 'STALE'
else 'CURRENT'
end as state
from agent_model_bindings b
left join provider_connections c on c.organization_id=b.organization_id and c.id=b.connection_id
left join provider_access_checks k
  on k.organization_id=b.organization_id and k.connection_id=b.connection_id and k.id=b.check_id
left join provider_model_discovery_entries e
  on e.organization_id=b.organization_id and e.connection_id=b.connection_id
  and e.check_id=b.check_id and e.model_id=b.model_id
left join provider_deployment_policy p on p.id=1
where b.organization_id=? and b.agent_id=?
""",
            (rs, row) -> {
              UUID connection = rs.getObject("connection_id", UUID.class);
              Binding binding =
                  connection == null
                      ? null
                      : new Binding(
                          connection,
                          rs.getString("name"),
                          rs.getString("provider"),
                          rs.getObject("check_id", UUID.class),
                          rs.getString("model_id"),
                          rs.getString("display_name"),
                          rs.getString("state"));
              return new State(rs.getInt("revision"), editable, binding);
            },
            organization,
            agent);
    return rows.isEmpty() ? new State(0, editable, null) : rows.getFirst();
  }

  private OffsetDateTime freshnessCutoff() {
    // PostgreSQL current_timestamp would use the start of a transaction that could have waited
    // for locks. Use a real reading after acquiring them. H2 is the disposable test database.
    var now =
        jdbc.execute(
            (ConnectionCallback<OffsetDateTime>)
                connection -> {
                  String clock =
                      "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                          ? "select clock_timestamp()"
                          : "select current_timestamp";
                  try (var stmt = connection.createStatement();
                      var result = stmt.executeQuery(clock)) {
                    if (!result.next())
                      throw new IllegalStateException("Database clock unavailable");
                    return result.getObject(1, OffsetDateTime.class);
                  }
                });
    return now.minusMinutes(15);
  }

  private String digest(Request request) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
    } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Cannot encode model binding");
    }
  }

  private String encode(State state) {
    try {
      return json.writeValueAsString(state);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot encode model binding");
    }
  }

  private State decode(String response) {
    try {
      return json.readValue(response, State.class);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot decode model binding receipt");
    }
  }
}

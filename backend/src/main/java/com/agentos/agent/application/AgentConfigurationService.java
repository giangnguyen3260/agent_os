package com.agentos.agent.application;

import com.agentos.agent.api.dto.AgentConfigurationRequest;
import com.agentos.agent.api.dto.AgentConfigurationResponse;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConfigurationService {
  private final AgentDraftService drafts;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AgentConfigurationService(AgentDraftService drafts, JdbcTemplate jdbc, ObjectMapper json) {
    this.drafts = drafts;
    this.jdbc = jdbc;
    this.json = json;
  }

  private AgentConfigurationResponse detail(UUID org, UUID agent) {
    var identity = drafts.detail(org, agent);
    return jdbc.queryForObject(
        "select a.configuration_revision,c.concurrency,c.max_tokens_per_run,c.monthly_budget"
            + " from agents a left join agent_draft_config c on c.agent_id=a.id"
            + " where a.organization_id=? and a.id=?",
        (rs, n) ->
            new AgentConfigurationResponse(
                identity,
                rs.getInt(1),
                rs.getObject(2, Integer.class),
                rs.getObject(3, Integer.class),
                rs.getBigDecimal(4),
                "USD"),
        org,
        agent);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AgentConfigurationResponse get(UUID org, HumanIdentity human, UUID agent) {
    drafts.authorize(org, human, false, false);
    return detail(org, agent);
  }

  private String required(String value) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > 4000)
      throw new IllegalArgumentException("Invalid required text");
    return value.strip();
  }

  private String optional(String value) {
    if (value != null && value.length() > 2000)
      throw new IllegalArgumentException("Optional text is too long");
    return value;
  }

  private Integer bounded(Integer value, int max) {
    if (value != null && (value < 1 || value > max))
      throw new IllegalArgumentException("Invalid structured limit");
    return value;
  }

  private AgentConfigurationRequest canonical(AgentConfigurationRequest r) {
    if (r == null
        || r.expectedRevision() == null
        || r.expectedRevision() < 0
        || r.expectedRevision() > Integer.MAX_VALUE - 1)
      throw new IllegalArgumentException("Invalid expected revision");
    BigDecimal budget = r.monthlyBudget();
    if (budget != null) {
      if (budget.signum() < 0
          || budget.compareTo(new BigDecimal("1000000")) > 0
          || budget.scale() > 2) throw new IllegalArgumentException("Invalid monthly budget");
      budget = budget.setScale(2, RoundingMode.UNNECESSARY);
    }
    return new AgentConfigurationRequest(
        r.expectedRevision(),
        required(r.responsibilities()),
        required(r.expectedInput()),
        required(r.outputFormat()),
        required(r.acceptanceCriteria()),
        optional(r.model()),
        optional(r.skills()),
        optional(r.tools()),
        optional(r.limits()),
        bounded(r.concurrency(), 1000),
        bounded(r.maxTokensPerRun(), 2000000),
        budget);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(AgentConfigurationRequest r) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(r).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Transactional
  public AgentConfigurationResponse put(
      UUID org, HumanIdentity human, UUID agent, UUID key, AgentConfigurationRequest input) {
    drafts.authorize(org, human, true, true);
    if (key == null) throw new IllegalArgumentException("Idempotency-Key is required");
    var targets =
        jdbc.query(
            "select id from agents where organization_id=? and id=? for update",
            (rs, n) -> rs.getObject(1, UUID.class),
            org,
            agent);
    if (targets.isEmpty()) throw new EntityNotFoundException("Agent not found");
    var r = canonical(input);
    String digest = hash(r);
    var receipts =
        jdbc.query(
            "select body_sha256,original_response from agent_configuration_receipts"
                + " where organization_id=? and account_id=? and agent_id=? and idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            org,
            human.id(),
            agent,
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!digest.equals(receipt[0])) throw new AgentDraftService.Conflict("IDEMPOTENCY_CONFLICT");
      try {
        return json.readValue(receipt[1], AgentConfigurationResponse.class);
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
    var before = detail(org, agent);
    if (!before.agent().status().equals("DRAFT"))
      throw new AgentDraftService.Conflict("AGENT_NOT_EDITABLE");
    if (before.revision() != r.expectedRevision())
      throw new AgentDraftService.Conflict("CONFIGURATION_CONFLICT");
    var a = before.agent();
    var existing =
        new AgentConfigurationRequest(
            before.revision(),
            a.responsibilities(),
            a.expectedInput(),
            a.outputFormat(),
            a.acceptanceCriteria(),
            a.model(),
            a.skills(),
            a.tools(),
            a.limits(),
            before.concurrency(),
            before.maxTokensPerRun(),
            before.monthlyBudget());
    if (r.equals(existing)) throw new IllegalArgumentException("Configuration has no changes");
    // The agent lock also serializes completion of legacy drafts without a config row.
    jdbc.update(
        "insert into"
            + " agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria)"
            + " select ?,?,?,?,? where not exists (select 1 from agent_draft_config where"
            + " agent_id=?)",
        agent,
        r.responsibilities(),
        r.expectedInput(),
        r.outputFormat(),
        r.acceptanceCriteria(),
        agent);
    jdbc.update(
        "update agent_draft_config set"
            + " responsibilities=?,expected_input=?,output_format=?,acceptance_criteria=?,model=?,skills=?,tools=?,limits=?,concurrency=?,max_tokens_per_run=?,monthly_budget=?"
            + " where agent_id=?",
        r.responsibilities(),
        r.expectedInput(),
        r.outputFormat(),
        r.acceptanceCriteria(),
        r.model(),
        r.skills(),
        r.tools(),
        r.limits(),
        r.concurrency(),
        r.maxTokensPerRun(),
        r.monthlyBudget(),
        agent);
    if (jdbc.update(
            "update agents set"
                + " configuration_revision=configuration_revision+1,updated_at=current_timestamp"
                + " where organization_id=? and id=? and configuration_revision=?",
            org,
            agent,
            r.expectedRevision())
        != 1) throw new AgentDraftService.Conflict("CONFIGURATION_CONFLICT");
    var response = detail(org, agent);
    String snapshot = encode(response);
    jdbc.update(
        "insert into"
            + " agent_configuration_versions(organization_id,agent_id,revision,account_id,original_response)"
            + " values (?,?,?,?,?)",
        org,
        agent,
        response.revision(),
        human.id(),
        snapshot);
    jdbc.update(
        "insert into"
            + " agent_configuration_receipts(organization_id,account_id,agent_id,idempotency_key,body_sha256,original_response)"
            + " values (?,?,?,?,?,?)",
        org,
        human.id(),
        agent,
        key,
        digest,
        snapshot);
    return response;
  }
}

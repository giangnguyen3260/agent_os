package com.agentos.agent.application;

import com.agentos.agent.api.dto.AgentDraftRequest;
import com.agentos.agent.api.dto.AgentDraftResponse;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentDraftService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AgentDraftService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public static class Conflict extends RuntimeException {
    public Conflict(String code) {
      super(code);
    }

    public String code() {
      return getMessage();
    }
  }

  public record Created(AgentDraftResponse response, boolean replay) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  public record Candidate(UUID id, String name, String status) {}

  private void authorize(UUID organizationId, HumanIdentity human, boolean admin, boolean lock) {
    // Creation and receipt replay lock in organization/account/membership order.
    String locking = lock ? " for update" : "";
    if (jdbc.query(
            "select id from organizations where id=?" + locking,
            (rs, n) -> rs.getObject(1, UUID.class),
            organizationId)
        .isEmpty()) {
      throw new AccessDeniedException("Request denied");
    }
    if (human == null) throw new AccessDeniedException("Request denied");
    var accounts =
        jdbc.query(
            "select enabled,credential_version from human_accounts where id=?" + locking,
            (rs, n) -> rs.getBoolean(1) && rs.getLong(2) == human.credentialVersion(),
            human.id());
    if (accounts.isEmpty() || !accounts.getFirst())
      throw new AccessDeniedException("Request denied");
    var roles =
        jdbc.query(
            "select role from organization_memberships where organization_id=? and account_id=?"
                + locking,
            (rs, n) -> rs.getString(1),
            organizationId,
            human.id());
    if (roles.isEmpty() || (admin && !roles.getFirst().equals("ADMIN"))) {
      throw new AccessDeniedException("Request denied");
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(AgentDraftRequest request) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(request).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String required(String value, int max) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > max)
      throw new IllegalArgumentException("Invalid required text");
    return value.strip();
  }

  private String optional(String value, int max) {
    if (value != null && value.length() > max)
      throw new IllegalArgumentException("Optional text is too long");
    return value;
  }

  private AgentDraftRequest canonical(AgentDraftRequest r) {
    if (r == null) throw new IllegalArgumentException("Request required");
    return new AgentDraftRequest(
        required(r.name(), 255),
        required(r.role(), 255),
        r.teamId(),
        optional(r.description(), 4000),
        required(r.responsibilities(), 4000),
        required(r.expectedInput(), 4000),
        required(r.outputFormat(), 4000),
        required(r.acceptanceCriteria(), 4000),
        optional(r.model(), 2000),
        optional(r.skills(), 2000),
        optional(r.tools(), 2000),
        optional(r.limits(), 2000));
  }

  @Transactional
  public Created create(
      UUID organizationId, HumanIdentity human, UUID key, AgentDraftRequest input) {
    authorize(organizationId, human, true, true);
    if (key == null) throw new IllegalArgumentException("Idempotency-Key is required");
    var r = canonical(input);
    String digest = hash(r);
    var receipts =
        jdbc.query(
            "select body_sha256,original_response from agent_create_receipts where"
                + " organization_id=? and account_id=? and idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            organizationId,
            human.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!digest.equals(receipt[0])) throw new Conflict("IDEMPOTENCY_CONFLICT");
      try {
        return new Created(json.readValue(receipt[1], AgentDraftResponse.class), true);
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
    if (r.teamId() != null) {
      var teams =
          jdbc.query(
              "select status from teams where organization_id=? and id=? for update",
              (rs, n) -> rs.getString(1),
              organizationId,
              r.teamId());
      if (teams.isEmpty() || !List.of("DRAFT", "ACTIVE").contains(teams.getFirst()))
        throw new Conflict("AGENT_SETUP_CONFLICT");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,?,?,'MID','DRAFT','SUGGEST',current_timestamp,current_timestamp)",
        id,
        organizationId,
        r.teamId(),
        r.name(),
        r.role());
    jdbc.update(
        "insert into"
            + " agent_draft_config(agent_id,description,responsibilities,expected_input,output_format,acceptance_criteria,model,skills,tools,limits)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        id,
        r.description(),
        r.responsibilities(),
        r.expectedInput(),
        r.outputFormat(),
        r.acceptanceCriteria(),
        r.model(),
        r.skills(),
        r.tools(),
        r.limits());
    var response = detail(organizationId, id);
    jdbc.update(
        "insert into"
            + " agent_create_receipts(organization_id,account_id,idempotency_key,body_sha256,original_response)"
            + " values (?,?,?,?,?)",
        organizationId,
        human.id(),
        key,
        digest,
        encode(response));
    return new Created(response, false);
  }

  private static final String SELECT =
      "select"
          + " a.*,c.description,c.responsibilities,c.expected_input,c.output_format,c.acceptance_criteria,c.model,c.skills,c.tools,c.limits"
          + " from agents a left join agent_draft_config c on c.agent_id=a.id";

  private AgentDraftResponse map(ResultSet rs, int row) throws SQLException {
    return new AgentDraftResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("organization_id", UUID.class),
        rs.getObject("team_id", UUID.class),
        rs.getString("name"),
        rs.getString("role"),
        rs.getString("description"),
        rs.getString("responsibilities"),
        rs.getString("expected_input"),
        rs.getString("output_format"),
        rs.getString("acceptance_criteria"),
        rs.getString("model"),
        rs.getString("skills"),
        rs.getString("tools"),
        rs.getString("limits"),
        rs.getString("level"),
        rs.getString("status"),
        rs.getString("autonomy_level"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private AgentDraftResponse detail(UUID org, UUID id) {
    var rows = jdbc.query(SELECT + " where a.organization_id=? and a.id=?", this::map, org, id);
    if (rows.isEmpty()) throw new EntityNotFoundException("Agent not found");
    return rows.getFirst();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AgentDraftResponse get(UUID org, HumanIdentity human, UUID id) {
    authorize(org, human, false, false);
    return detail(org, id);
  }

  private String pattern(int page, String query) {
    String search = query == null ? "" : query;
    if (page < 0 || page > 100000 || search.length() > 100)
      throw new IllegalArgumentException("Invalid page or query");
    return "%"
        + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
        + "%";
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<AgentDraftResponse> list(UUID org, HumanIdentity human, int page, String query) {
    authorize(org, human, false, false);
    String pattern = pattern(page, query),
        where = " where a.organization_id=? and lower(a.name) like ? escape '!'";
    long total =
        jdbc.queryForObject("select count(*) from agents a" + where, Long.class, org, pattern);
    return new Page<>(
        jdbc.query(
            SELECT + where + " order by a.name,a.id limit 20 offset ?",
            this::map,
            org,
            pattern,
            page * 20),
        page,
        20,
        total);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<Candidate> eligible(UUID org, HumanIdentity human, int page, String query) {
    authorize(org, human, true, false);
    String pattern = pattern(page, query),
        where =
            " where organization_id=? and status in ('DRAFT','ACTIVE') and lower(name) like ?"
                + " escape '!'";
    long total =
        jdbc.queryForObject("select count(*) from teams" + where, Long.class, org, pattern);
    return new Page<>(
        jdbc.query(
            "select id,name,status from teams" + where + " order by name,id limit 20 offset ?",
            (rs, n) ->
                new Candidate(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status")),
            org,
            pattern,
            page * 20),
        page,
        20,
        total);
  }
}

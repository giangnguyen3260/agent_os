package com.agentos.organization.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.organization.api.dto.NewTeamDraftRequest;
import com.agentos.organization.api.dto.TeamDetailResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
public class NewTeamDraftService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public NewTeamDraftService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public static class Conflict extends RuntimeException {
    private final String code;

    public Conflict(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  public record Created(TeamDetailResponse response, boolean replay) {}

  public record Candidate(UUID id, String name, String level, String status) {}

  public record Candidates(List<Candidate> items, int page, int size, long total) {}

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

  private NewTeamDraftRequest canonical(NewTeamDraftRequest request) {
    String name = required(request.name(), 255), purpose = required(request.purpose(), 2000);
    if (request.description() != null && request.description().length() > 4000)
      throw new IllegalArgumentException("Description is too long");
    if (!"MANUAL".equals(request.intake())
        || request.concurrency() == null
        || request.concurrency() < 1
        || request.concurrency() > 1000)
      throw new IllegalArgumentException("Invalid intake or concurrency");
    var budget = request.monthlyBudget();
    if (budget == null
        || budget.scale() > 2
        || budget.signum() < 0
        || budget.compareTo(new BigDecimal("1000000")) > 0)
      throw new IllegalArgumentException("Invalid monthly budget");
    try {
      budget = budget.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Budget allows at most two decimals");
    }
    var ids = request.agentIds();
    if (ids == null
        || ids.size() > 50
        || ids.stream().anyMatch(id -> id == null)
        || ids.stream().distinct().count() != ids.size()
        || (request.managerAgentId() != null && ids.contains(request.managerAgentId())))
      throw new IllegalArgumentException("Invalid agentIds");
    return new NewTeamDraftRequest(
        name,
        purpose,
        request.description(),
        request.managerAgentId(),
        ids.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
        "MANUAL",
        request.concurrency(),
        budget);
  }

  private String required(String value, int max) {
    if (value == null || value.strip().isEmpty() || value.strip().length() > max)
      throw new IllegalArgumentException("Invalid required text");
    return value.strip();
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(NewTeamDraftRequest request) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(request).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Transactional
  public Created create(
      UUID organizationId, HumanIdentity human, UUID key, NewTeamDraftRequest input) {
    authorize(organizationId, human, true, true);
    if (key == null) throw new IllegalArgumentException("Idempotency-Key is required");
    var request = canonical(input);
    String digest = hash(request);
    var receipts =
        jdbc.query(
            "select body_sha256,original_response from team_create_receipts where organization_id=?"
                + " and account_id=? and idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            organizationId,
            human.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!digest.equals(receipt[0])) throw new Conflict("IDEMPOTENCY_CONFLICT");
      try {
        return new Created(json.readValue(receipt[1], TeamDetailResponse.class), true);
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
    List<UUID> selected = new ArrayList<>(request.agentIds());
    if (request.managerAgentId() != null) selected.add(request.managerAgentId());
    selected.sort(Comparator.comparing(UUID::toString));
    for (UUID id : selected) {
      var eligible =
          jdbc.query(
              "select team_id,status,level from agents where organization_id=? and id=? for update",
              (rs, n) ->
                  rs.getObject("team_id") == null
                      && List.of("DRAFT", "ACTIVE").contains(rs.getString("status"))
                      && (!id.equals(request.managerAgentId())
                          || "MANAGER".equals(rs.getString("level"))),
              organizationId,
              id);
      if (eligible.isEmpty() || !eligible.getFirst()) throw new Conflict("TEAM_SETUP_CONFLICT");
    }
    UUID teamId = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " teams(id,organization_id,name,purpose,description,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)"
            + " values (?,?,?,?,?,'DRAFT',?,?,?,'USD',current_timestamp,current_timestamp)",
        teamId,
        organizationId,
        request.name(),
        request.purpose(),
        request.description(),
        request.intake(),
        request.concurrency(),
        request.monthlyBudget());
    for (UUID id : selected) {
      if (jdbc.update(
              "update agents set team_id=?,updated_at=current_timestamp where organization_id=? and"
                  + " id=? and team_id is null and status in ('DRAFT','ACTIVE')",
              teamId,
              organizationId,
              id)
          != 1) throw new Conflict("TEAM_SETUP_CONFLICT");
    }
    jdbc.update(
        "update teams set manager_agent_id=? where organization_id=? and id=?",
        request.managerAgentId(),
        organizationId,
        teamId);
    var response = detail(organizationId, teamId);
    jdbc.update(
        "insert into"
            + " team_create_receipts(organization_id,account_id,idempotency_key,body_sha256,original_response)"
            + " values (?,?,?,?,?)",
        organizationId,
        human.id(),
        key,
        digest,
        encode(response));
    return new Created(response, false);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public TeamDetailResponse get(UUID organizationId, HumanIdentity human, UUID teamId) {
    authorize(organizationId, human, false, false);
    return detail(organizationId, teamId);
  }

  private TeamDetailResponse detail(UUID organizationId, UUID teamId) {
    var rows =
        jdbc.query(
            "select * from teams where organization_id=? and id=?",
            (rs, n) -> {
              UUID manager = rs.getObject("manager_agent_id", UUID.class);
              var ids =
                  jdbc.query(
                      "select id from agents where organization_id=? and team_id=? and (cast(? as"
                          + " uuid) is null or id <> ?) order by id",
                      (agent, i) -> agent.getObject(1, UUID.class),
                      organizationId,
                      teamId,
                      manager,
                      manager);
              return new TeamDetailResponse(
                  teamId,
                  organizationId,
                  rs.getString("name"),
                  rs.getString("purpose"),
                  rs.getString("description"),
                  rs.getString("status"),
                  manager,
                  ids,
                  rs.getString("intake"),
                  rs.getObject("concurrency", Integer.class),
                  rs.getBigDecimal("monthly_budget"),
                  rs.getString("currency"),
                  rs.getObject("created_at", OffsetDateTime.class).toInstant());
            },
            organizationId,
            teamId);
    if (rows.isEmpty()) throw new EntityNotFoundException("Team not found");
    return rows.getFirst();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates eligible(UUID organizationId, HumanIdentity human, int page, String query) {
    authorize(organizationId, human, true, false);
    String search = query == null ? "" : query.strip();
    if (page < 0 || page > 100000 || search.length() > 100)
      throw new IllegalArgumentException("Invalid candidate page or query");
    String pattern =
        "%"
            + search
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    String where =
        " where organization_id=? and team_id is null and status in ('DRAFT','ACTIVE') and"
            + " lower(name) like ? escape '!'";
    long total =
        jdbc.queryForObject(
            "select count(*) from agents" + where, Long.class, organizationId, pattern);
    var items =
        jdbc.query(
            "select id,name,level,status from agents"
                + where
                + " order by name,id limit 20 offset ?",
            (rs, n) ->
                new Candidate(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("level"),
                    rs.getString("status")),
            organizationId,
            pattern,
            page * 20);
    return new Candidates(items, page, 20, total);
  }
}

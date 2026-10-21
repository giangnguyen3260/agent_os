package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.api.dto.MissionCreateRequest;
import com.agentos.mission.api.dto.MissionDetailResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
public class MissionCreateService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public MissionCreateService(JdbcTemplate jdbc, ObjectMapper json) {
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

  public record Created(MissionDetailResponse response, boolean replay) {}

  public record Candidate(UUID id, String name, String level, String status) {}

  public record Candidates(List<Candidate> items, int page, int size, long total) {}

  void authorize(UUID organizationId, HumanIdentity human, boolean admin, boolean lock) {
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
    if (roles.isEmpty()
        || !List.of("ADMIN", "MEMBER").contains(roles.getFirst())
        || (admin && !roles.getFirst().equals("ADMIN"))) {
      throw new AccessDeniedException("Request denied");
    }
  }

  private String required(String s, int max) {
    if (s == null || s.strip().isEmpty() || s.strip().length() > max)
      throw new IllegalArgumentException("Invalid required text");
    return s.strip();
  }

  private MissionCreateRequest canonical(MissionCreateRequest r) {
    if (r == null) throw new IllegalArgumentException("Request required");
    if (!List.of("LOW", "NORMAL", "HIGH", "URGENT")
            .contains(r.priority() == null ? "" : r.priority())
        || !"HUMAN_REQUIRED".equals(r.approvalMode()))
      throw new IllegalArgumentException("Invalid mission configuration");
    if (r.context() != null && r.context().length() > 8000)
      throw new IllegalArgumentException("Context too long");
    if (r.deadline() != null) {
      try {
        if (!r.deadline().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
          throw new DateTimeParseException("Invalid date", r.deadline(), 0);
        LocalDate.parse(r.deadline());
      } catch (DateTimeParseException ex) {
        throw new IllegalArgumentException("Invalid deadline");
      }
    }
    BigDecimal money = r.budgetLimit();
    if (money != null) {
      if (money.scale() > 2 || money.signum() < 0 || money.compareTo(new BigDecimal("1000000")) > 0)
        throw new IllegalArgumentException("Invalid budget");
      money = money.setScale(2, RoundingMode.UNNECESSARY);
    }
    return new MissionCreateRequest(
        required(r.title(), 255),
        required(r.goal(), 4000),
        required(r.requiredOutput(), 4000),
        r.managerAgentId(),
        r.priority(),
        r.deadline(),
        money,
        r.approvalMode(),
        r.context() == null ? "" : r.context());
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(UUID team, MissionCreateRequest r) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(List.of(team, r)).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void team(UUID org, UUID team, boolean lock, boolean eligible) {
    var rows =
        jdbc.query(
            "select status from teams where organization_id=? and id=?"
                + (lock ? " for update" : ""),
            (rs, n) -> rs.getString(1),
            org,
            team);
    if (rows.isEmpty()) throw new EntityNotFoundException("Team not found");
    if (eligible && "ARCHIVED".equals(rows.getFirst()))
      throw new Conflict("MISSION_SETUP_CONFLICT");
  }

  @Transactional
  public Created create(
      UUID org, UUID team, HumanIdentity human, UUID key, MissionCreateRequest input) {
    authorize(org, human, true, true);
    if (key == null) throw new IllegalArgumentException("Idempotency-Key required");
    var r = canonical(input);
    String digest = hash(team, r);
    var receipts =
        jdbc.query(
            "select body_sha256,original_response from mission_create_receipts where organization_id=? and account_id=? and idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            org,
            human.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!digest.equals(receipt[0])) throw new Conflict("IDEMPOTENCY_CONFLICT");
      try {
        return new Created(json.readValue(receipt[1], MissionDetailResponse.class), true);
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
    team(org, team, true, true);
    if (r.managerAgentId() != null) {
      var managers =
          jdbc.query(
              "select team_id,level,status from agents where organization_id=? and id=? for update",
              (rs, n) ->
                  team.equals(rs.getObject(1, UUID.class))
                      && "MANAGER".equals(rs.getString(2))
                      && List.of("DRAFT", "ACTIVE").contains(rs.getString(3)),
              org,
              r.managerAgentId());
      if (managers.isEmpty() || !managers.getFirst()) throw new Conflict("MISSION_SETUP_CONFLICT");
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into missions(id,organization_id,team_id,created_by_user_id,manager_agent_id,title,goal,required_output,status,priority,deadline,budget_limit,currency,approval_mode,context,created_at,updated_at) values (?,?,?,?,?,?,?,?,'CREATED',?,?,?,?,?,?,current_timestamp,current_timestamp)",
        id,
        org,
        team,
        human.id(),
        r.managerAgentId(),
        r.title(),
        r.goal(),
        r.requiredOutput(),
        r.priority(),
        r.deadline() == null ? null : LocalDate.parse(r.deadline()),
        r.budgetLimit(),
        "USD",
        r.approvalMode(),
        r.context());
    var response = detail(org, team, id);
    jdbc.update(
        "insert into mission_create_receipts(organization_id,account_id,idempotency_key,body_sha256,original_response) values (?,?,?,?,?)",
        org,
        human.id(),
        key,
        digest,
        encode(response));
    jdbc.update(
        "insert into audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at) values (?,?,?,'HUMAN',?,'MISSION_CREATED',?,current_timestamp)",
        UUID.randomUUID(),
        org,
        id,
        human.id(),
        "{\"status\":\"CREATED\"}");
    return new Created(response, false);
  }

  private MissionDetailResponse detail(UUID org, UUID team, UUID id) {
    var rows =
        jdbc.query(
            "select m.*,t.name as team_name,a.name as manager_name from missions m join teams t on t.organization_id=m.organization_id and t.id=m.team_id left join agents a on a.organization_id=m.organization_id and a.team_id=m.team_id and a.id=m.manager_agent_id where m.organization_id=? and m.team_id=? and m.id=?",
            (rs, n) ->
                new MissionDetailResponse(
                    rs.getObject("id", UUID.class),
                    org,
                    team,
                    rs.getString("team_name"),
                    rs.getObject("manager_agent_id", UUID.class),
                    rs.getString("manager_name"),
                    rs.getString("title"),
                    rs.getString("goal"),
                    rs.getString("required_output"),
                    rs.getString("status"),
                    rs.getString("priority"),
                    rs.getObject("deadline", LocalDate.class),
                    rs.getBigDecimal("budget_limit"),
                    rs.getString("currency"),
                    rs.getString("approval_mode"),
                    rs.getString("context"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()),
            org,
            team,
            id);
    if (rows.isEmpty()) throw new EntityNotFoundException("Mission not found");
    return rows.getFirst();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public MissionDetailResponse get(UUID org, UUID team, HumanIdentity human, UUID id) {
    authorize(org, human, false, false);
    team(org, team, false, false);
    return detail(org, team, id);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates eligible(UUID org, UUID team, HumanIdentity human, int page, String query) {
    authorize(org, human, true, false);
    team(org, team, false, true);
    String search = query == null ? "" : query;
    if (page < 0 || page > 100000 || search.length() > 100)
      throw new IllegalArgumentException("Invalid page or query");
    String pattern =
        "%"
            + search
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    String where =
        " where organization_id=? and team_id=? and level='MANAGER' and status in ('DRAFT','ACTIVE') and lower(name) like ? escape '!'";
    long total =
        jdbc.queryForObject("select count(*) from agents" + where, Long.class, org, team, pattern);
    return new Candidates(
        jdbc.query(
            "select id,name,level,status from agents"
                + where
                + " order by name,id limit 20 offset ?",
            (rs, n) ->
                new Candidate(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            org,
            team,
            pattern,
            page * 20),
        page,
        20,
        total);
  }
}

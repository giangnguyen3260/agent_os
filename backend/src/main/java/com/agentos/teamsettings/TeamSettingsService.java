package com.agentos.teamsettings;

import com.agentos.auth.HumanIdentity;
import com.agentos.teamsettings.TeamSettingsDtos.Candidate;
import com.agentos.teamsettings.TeamSettingsDtos.Candidates;
import com.agentos.teamsettings.TeamSettingsDtos.Command;
import com.agentos.teamsettings.TeamSettingsDtos.Context;
import com.agentos.teamsettings.TeamSettingsDtos.Member;
import com.agentos.teamsettings.TeamSettingsDtos.Receipt;
import com.agentos.teamsettings.TeamSettingsDtos.Result;
import com.agentos.teamsettings.TeamSettingsDtos.State;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamSettingsService {
  private static final DateTimeFormatter MICROS =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
  private static final Set<String> LEVELS = Set.of("JUNIOR", "MID", "SENIOR", "LEAD", "MANAGER");
  private static final Set<String> STATUSES =
      Set.of("DRAFT", "ACTIVE", "DEGRADED", "SUSPENDED", "ARCHIVED");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public TeamSettingsService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private record Agent(
      UUID id,
      UUID organizationId,
      UUID teamId,
      String name,
      String role,
      String level,
      String status,
      UUID reportsTo,
      String updatedAt,
      int configurationRevision) {}

  private record Team(State state, String createdAt, String updatedAt) {}

  private record Snapshot(
      Team team, List<Agent> agents, long memberTotal, int revision, String fingerprint) {}

  private record Stored(UUID teamId, String hash, String response) {}

  private Agent agent(ResultSet rs, int row) throws SQLException {
    return new Agent(
        rs.getObject("id", UUID.class),
        rs.getObject("organization_id", UUID.class),
        rs.getObject("team_id", UUID.class),
        rs.getString("name"),
        rs.getString("role"),
        rs.getString("level"),
        rs.getString("status"),
        rs.getObject("reports_to_agent_id", UUID.class),
        time(rs.getObject("updated_at", OffsetDateTime.class)),
        rs.getInt("configuration_revision"));
  }

  private static String time(OffsetDateTime value) {
    return MICROS.format(value.toInstant());
  }

  private String now() {
    return time(jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class));
  }

  private String authorize(UUID org, HumanIdentity human, boolean lock) {
    String suffix = lock ? " for update" : "";
    if (human == null
        || jdbc.query(
                "select id from organizations where id=?" + suffix,
                (rs, n) -> rs.getObject(1, UUID.class),
                org)
            .isEmpty()) denied();
    var accounts =
        jdbc.query(
            "select enabled,credential_version,display_name from human_accounts where id=?"
                + suffix,
            (rs, n) -> new Object[] {rs.getBoolean(1), rs.getLong(2), rs.getString(3)},
            human.id());
    if (accounts.isEmpty()
        || !Boolean.TRUE.equals(accounts.get(0)[0])
        || ((Long) accounts.get(0)[1]) != human.credentialVersion()) denied();
    var roles =
        jdbc.query(
            "select role from organization_memberships where organization_id=? and account_id=?"
                + suffix,
            (rs, n) -> rs.getString(1),
            org,
            human.id());
    if (roles.isEmpty() || !"ADMIN".equals(roles.get(0))) denied();
    return (String) accounts.get(0)[2];
  }

  private static void denied() {
    throw new TeamSettingsFailure(403, "FORBIDDEN");
  }

  private Team team(UUID org, UUID id, boolean lock) {
    var rows =
        jdbc.query(
            "select * from teams where organization_id=? and id=?" + (lock ? " for update" : ""),
            (rs, n) ->
                new Team(
                    new State(
                        rs.getString("name"),
                        rs.getString("purpose"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getObject("manager_agent_id", UUID.class),
                        List.of(),
                        rs.getString("intake"),
                        rs.getObject("concurrency", Integer.class),
                        rs.getBigDecimal("monthly_budget"),
                        rs.getString("currency")),
                    time(rs.getObject("created_at", OffsetDateTime.class)),
                    time(rs.getObject("updated_at", OffsetDateTime.class))),
            org,
            id);
    if (rows.isEmpty()) throw new TeamSettingsFailure(404, "NOT_FOUND");
    return rows.get(0);
  }

  private List<Agent> members(UUID org, UUID team) {
    return jdbc.query(
        "select * from agents where organization_id=? and team_id=? order by cast(id as varchar)"
            + " limit 201",
        this::agent,
        org,
        team);
  }

  private int revision(UUID org, UUID team) {
    var rows =
        jdbc.query(
            "select revision from team_settings_ledgers where organization_id=? and team_id=?",
            (rs, n) -> rs.getInt(1),
            org,
            team);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /**
   * Fingerprint v1: complete saved Team state + Team updated_at + all assigned Agent
   * id/org/team/name/role/level/status/reportsTo/updatedAt/configurationRevision + ledger revision.
   * Agents are streamed in UUID-string order as canonical JSON + LF, bracket delimited, and hashed
   * into agentsSha256. memberTotal and the bounded Team ID projection are included. Recursively
   * sorted object keys are independent of DB/map order. No projection truncation, resolved actor
   * names, clock or runtime metrics enter this source.
   */
  private Snapshot snapshot(UUID org, UUID id) {
    Team raw = team(org, id, false);
    List<Agent> agents = members(org, id);
    State state =
        withIds(
            raw.state(),
            agents.stream()
                .map(Agent::id)
                .filter(agentId -> !agentId.equals(raw.state().managerAgentId()))
                .toList());
    Team team = new Team(state, raw.createdAt(), raw.updatedAt());
    long total =
        jdbc.queryForObject(
            "select count(*) from agents where organization_id=? and team_id=?",
            Long.class,
            org,
            id);
    MessageDigest agentsDigest = sha256();
    agentsDigest.update((byte) '[');
    // Stream the complete membership fingerprint without materializing an oversized Team.
    jdbc.query(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "select * from agents where organization_id=? and team_id=? order by cast(id as"
                      + " varchar)");
          statement.setObject(1, org);
          statement.setObject(2, id);
          statement.setFetchSize(128);
          return statement;
        },
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              agentsDigest.update(canonical(agent(rs, 0)).getBytes(StandardCharsets.UTF_8));
              agentsDigest.update((byte) '\n');
            });
    agentsDigest.update((byte) ']');
    int revision = revision(org, id);
    return new Snapshot(
        team,
        agents,
        total,
        revision,
        hash(
            Map.of(
                "domain",
                "TEAM_SETTINGS_SNAPSHOT",
                "version",
                1,
                "organizationId",
                org,
                "teamId",
                id,
                "team",
                team,
                "agentsSha256",
                HexFormat.of().formatHex(agentsDigest.digest()),
                "memberTotal",
                total,
                "revision",
                revision)));
  }

  private static State withIds(State state, List<UUID> ids) {
    return new State(
        state.name(),
        state.purpose(),
        state.description(),
        state.status(),
        state.managerAgentId(),
        ids,
        state.intake(),
        state.concurrency(),
        state.monthlyBudget(),
        state.currency());
  }

  private List<String> blockers(Snapshot snapshot) {
    var result = new ArrayList<String>();
    if ("ARCHIVED".equals(snapshot.team().state().status())) result.add("TEAM_ARCHIVED");
    if (snapshot.memberTotal() > 200) result.add("TEAM_SIZE_UNSUPPORTED");
    String currency = snapshot.team().state().currency();
    if (currency != null && !"USD".equals(currency)) result.add("UNSUPPORTED_CURRENCY");
    return List.copyOf(result);
  }

  private List<String> removal(Agent agent) {
    var result = new ArrayList<String>();
    if (!"DRAFT".equals(agent.status())) result.add("AGENT_NOT_DRAFT");
    if (agent.reportsTo() != null) result.add("AGENT_REPORTS_TO");
    if (!LEVELS.contains(agent.level()) || !STATUSES.contains(agent.status()))
      result.add("AGENT_UNSUPPORTED");
    // Deliberately global: history in a foreign Team/org must also protect the reference.
    if (exists("select count(*) from agents where reports_to_agent_id=?", agent.id()))
      result.add("AGENT_REFERENCED");
    if (exists("select count(*) from missions where manager_agent_id=?", agent.id())
        || exists("select count(*) from tasks where owner_agent_id=?", agent.id())
        || exists(
            "select count(*) from approval_requests where requested_by_agent_id=?", agent.id())
        || exists(
            "select count(*) from audit_events where actor_type='AGENT' and actor_id=?",
            agent.id())) result.add("AGENT_HISTORY_REFERENCED");
    return List.copyOf(result);
  }

  private boolean exists(String sql, UUID id) {
    return jdbc.queryForObject(sql, Long.class, id) > 0;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID id, HumanIdentity human) {
    authorize(org, human, false);
    Snapshot source = snapshot(org, id);
    List<String> blockers = blockers(source);
    boolean draft = "DRAFT".equals(source.team().state().status());
    var members =
        source.agents().stream()
            .limit(201)
            .map(
                agent -> {
                  var reasons = new ArrayList<>(removal(agent));
                  if (!draft) reasons.add("TEAM_NOT_DRAFT");
                  return new Member(
                      agent.id(),
                      agent.name(),
                      agent.level(),
                      agent.status(),
                      reasons.isEmpty(),
                      List.copyOf(reasons));
                })
            .toList();
    // Bound every membership projection, including the IDs in the state.
    Set<UUID> visible = new HashSet<>(source.agents().stream().limit(201).map(Agent::id).toList());
    State state =
        withIds(
            source.team().state(),
            source.team().state().agentIds().stream().filter(visible::contains).toList());
    return new Context(
        org,
        id,
        now(),
        source.revision(),
        source.fingerprint(),
        state,
        members,
        source.memberTotal(),
        blockers.isEmpty(),
        blockers.isEmpty() && draft,
        blockers);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates candidates(UUID org, UUID id, HumanIdentity human, int page, String query) {
    authorize(org, human, false);
    Snapshot source = snapshot(org, id);
    if (!blockers(source).isEmpty() || !"DRAFT".equals(source.team().state().status()))
      throw TeamSettingsFailure.blocked();
    if (page < 0 || page > 100000) throw TeamSettingsFailure.bad();
    String search =
        TeamSettingsWire.safe(query == null ? "" : query, Integer.MAX_VALUE, false).strip();
    if (search.codePointCount(0, search.length()) > 100) throw TeamSettingsFailure.bad();
    String pattern =
        "%"
            + search
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    String where =
        " where organization_id=? and team_id is null and reports_to_agent_id is null and status in"
            + " ('DRAFT','ACTIVE') and level in ('JUNIOR','MID','SENIOR','LEAD','MANAGER') and"
            + " lower(name) like ? escape '!'";
    long total =
        jdbc.queryForObject("select count(*) from agents" + where, Long.class, org, pattern);
    var items =
        jdbc.query(
            "select id,name,level,status from agents"
                + where
                + " order by lower(name),cast(id as varchar) limit 20 offset ?",
            (rs, n) ->
                new Candidate(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            org,
            pattern,
            page * 20);
    return new Candidates(org, id, now(), items, page, 20, total);
  }

  private Stored stored(UUID org, HumanIdentity human, UUID key) {
    var rows =
        jdbc.query(
            "select team_id,body_sha256,original_response from team_settings_receipts"
                + " where organization_id=? and actor_id=? and idempotency_key=?",
            (rs, n) -> new Stored(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            org,
            human.id(),
            key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Receipt decode(String value) {
    try {
      return json.readValue(value, Receipt.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid saved receipt", ex);
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Receipt receipt(UUID org, UUID id, HumanIdentity human, UUID key) {
    authorize(org, human, false);
    team(org, id, false);
    Stored stored = stored(org, human, key);
    return stored == null || !stored.teamId().equals(id) ? null : decode(stored.response());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result update(UUID org, UUID id, HumanIdentity human, UUID key, Command command) {
    authorize(org, human, true); // Organization mutex MUST precede every other row lock.
    team(org, id, true);
    String digest =
        hash(
            Map.of(
                "domain",
                "TEAM_SETTINGS",
                "version",
                1,
                "actorId",
                human.id(),
                "organizationId",
                org,
                "teamId",
                id,
                "body",
                command));
    Stored stored = stored(org, human, key);
    if (stored != null) {
      if (!stored.teamId().equals(id) || !stored.hash().equals(digest))
        throw new TeamSettingsFailure(409, "IDEMPOTENCY_CONFLICT");
      authorize(org, human, false);
      return new Result(decode(stored.response()), true);
    }
    Set<UUID> desired = new TreeSet<>(Comparator.comparing(UUID::toString));
    desired.addAll(command.agentIds());
    if (command.managerAgentId() != null) desired.add(command.managerAgentId());
    Set<UUID> union = new TreeSet<>(Comparator.comparing(UUID::toString));
    List<Agent> initialMembers = members(org, id);
    if (initialMembers.size() > 200) throw TeamSettingsFailure.blocked();
    initialMembers.forEach(agent -> union.add(agent.id()));
    union.addAll(desired);
    var locked = new TreeMap<String, Agent>();
    for (UUID agentId : union) {
      var rows =
          jdbc.query(
              "select * from agents where organization_id=? and id=? for update",
              this::agent,
              org,
              agentId);
      if (rows.isEmpty()) throw TeamSettingsFailure.blocked();
      locked.put(agentId.toString(), rows.get(0));
    }
    jdbc.update(
        "insert into team_settings_ledgers(organization_id,team_id,revision) values (?,?,0) on"
            + " conflict do nothing",
        org,
        id);
    jdbc.queryForObject(
        "select revision from team_settings_ledgers where organization_id=? and team_id=? for"
            + " update",
        Integer.class,
        org,
        id);
    String actorName = authorize(org, human, false); // Recheck after all lock waits.
    Snapshot source = snapshot(org, id);
    if (source.revision() != command.expectedRevision()
        || !source.fingerprint().equals(command.expectedFingerprint()))
      throw new TeamSettingsFailure(409, "TEAM_SETTINGS_CONFLICT");
    if (!blockers(source).isEmpty()) throw TeamSettingsFailure.blocked();
    State before = source.team().state();
    Set<UUID> current = new HashSet<>(source.agents().stream().map(Agent::id).toList());
    boolean membershipChanged =
        !current.equals(desired)
            || !Objects.equals(before.managerAgentId(), command.managerAgentId());
    if (!Set.of("DRAFT", "ACTIVE").contains(before.status())
        || (membershipChanged && !"DRAFT".equals(before.status())))
      throw TeamSettingsFailure.blocked();
    for (Agent agent : source.agents()) {
      if ((!LEVELS.contains(agent.level()) || !STATUSES.contains(agent.status()))
          && !(agent.id().equals(before.managerAgentId())
              && Objects.equals(before.managerAgentId(), command.managerAgentId())
              && !membershipChanged)) throw TeamSettingsFailure.blocked();
      if (!desired.contains(agent.id()) && !removal(agent).isEmpty())
        throw TeamSettingsFailure.blocked();
    }
    if (before.managerAgentId() != null && !current.contains(before.managerAgentId()))
      throw TeamSettingsFailure.blocked();
    for (UUID agentId : desired) {
      Agent agent = locked.get(agentId.toString());
      if (agent == null
          || (!current.contains(agentId)
              && (agent.teamId() != null || agent.reportsTo() != null || !eligible(agent))))
        throw TeamSettingsFailure.blocked();
      if (agentId.equals(command.managerAgentId())
          && !Objects.equals(before.managerAgentId(), command.managerAgentId())
          && (!"MANAGER".equals(agent.level()) || !eligible(agent)))
        throw TeamSettingsFailure.blocked();
    }
    State after =
        new State(
            command.name(),
            command.purpose(),
            command.description(),
            before.status(),
            command.managerAgentId(),
            command.agentIds(),
            "MANUAL",
            command.concurrency(),
            command.monthlyBudget(),
            "USD");
    if (canonical(before).equals(canonical(after)))
      throw new TeamSettingsFailure(409, "TEAM_SETTINGS_NO_CHANGES");
    if (source.revision() == Integer.MAX_VALUE)
      throw new TeamSettingsFailure(409, "TEAM_SETTINGS_REVISION_EXHAUSTED");
    String recordedAt = now();
    OffsetDateTime timestamp = OffsetDateTime.parse(recordedAt);
    if (membershipChanged) {
      jdbc.update(
          "update teams set manager_agent_id=null where organization_id=? and id=?", org, id);
      for (UUID agentId : current.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
        if (!desired.contains(agentId)
            && jdbc.update(
                    "update agents set team_id=null,updated_at=?"
                        + " where organization_id=? and id=? and team_id=?",
                    timestamp,
                    org,
                    agentId,
                    id)
                != 1) throw TeamSettingsFailure.blocked();
      }
      for (UUID agentId : desired) {
        if (!current.contains(agentId)
            && jdbc.update(
                    "update agents set team_id=?,updated_at=? where organization_id=? and id=? and"
                        + " team_id is null and reports_to_agent_id is null and status in"
                        + " ('DRAFT','ACTIVE')",
                    id,
                    timestamp,
                    org,
                    agentId)
                != 1) throw TeamSettingsFailure.blocked();
      }
    }
    jdbc.update(
        "update teams set"
            + " name=?,purpose=?,description=?,manager_agent_id=?,intake='MANUAL',concurrency=?,monthly_budget=?,currency='USD',updated_at=?"
            + " where organization_id=? and id=?",
        after.name(),
        after.purpose(),
        after.description(),
        after.managerAgentId(),
        after.concurrency(),
        after.monthlyBudget(),
        timestamp,
        org,
        id);
    int revision = source.revision() + 1;
    if (jdbc.update(
            "update team_settings_ledgers set revision=? where organization_id=? and team_id=? and"
                + " revision=?",
            revision,
            org,
            id,
            source.revision())
        != 1) throw new TeamSettingsFailure(409, "TEAM_SETTINGS_CONFLICT");
    UUID eventId = UUID.randomUUID();
    Receipt receipt =
        new Receipt(
            "UPDATE_TEAM_SETTINGS",
            org,
            id,
            key,
            revision,
            eventId,
            human.id(),
            actorName,
            recordedAt,
            before,
            after);
    jdbc.update(
        "insert into"
            + " team_settings_events(organization_id,team_id,revision,event_id,actor_id,actor_name,recorded_at,before_state,after_state)"
            + " values (?,?,?,?,?,?,?,?,?)",
        org,
        id,
        revision,
        eventId,
        human.id(),
        actorName,
        timestamp,
        encode(before),
        encode(after));
    jdbc.update(
        "insert into"
            + " team_settings_receipts(organization_id,actor_id,idempotency_key,team_id,event_id,body_sha256,original_response)"
            + " values (?,?,?,?,?,?,?)",
        org,
        human.id(),
        key,
        id,
        eventId,
        digest,
        encode(receipt));
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'HUMAN',?,'TEAM_SETTINGS_UPDATED',?,?)",
        UUID.randomUUID(),
        org,
        human.id(),
        encode(Map.of("eventId", eventId, "revision", revision)),
        timestamp);
    return new Result(receipt, false);
  }

  private static boolean eligible(Agent agent) {
    return Set.of("DRAFT", "ACTIVE").contains(agent.status()) && LEVELS.contains(agent.level());
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  // Convert money consistently even for legacy JDBC scales, recursively sort every object key.
  String canonical(Object value) {
    return encode(sort(json.valueToTree(value)));
  }

  private JsonNode sort(JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = json.createObjectNode();
      var fields = new TreeSet<String>();
      node.fieldNames().forEachRemaining(fields::add);
      fields.forEach(
          field -> {
            JsonNode child = node.get(field);
            result.set(
                field,
                "monthlyBudget".equals(field) && child.isNumber()
                    ? json.getNodeFactory().numberNode(child.decimalValue().setScale(2))
                    : sort(child));
          });
      return result;
    }
    if (node.isArray()) {
      var result = json.createArrayNode();
      node.forEach(item -> result.add(sort(item)));
      return result;
    }
    if (node.isFloatingPointNumber())
      return json.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros());
    return node;
  }

  String hash(Object value) {
    return HexFormat.of()
        .formatHex(sha256().digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}

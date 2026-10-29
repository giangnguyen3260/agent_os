package com.agentos.taskowner;

import com.agentos.auth.HumanIdentity;
import com.agentos.taskowner.TaskOwnerDtos.Agent;
import com.agentos.taskowner.TaskOwnerDtos.Candidate;
import com.agentos.taskowner.TaskOwnerDtos.Candidates;
import com.agentos.taskowner.TaskOwnerDtos.Command;
import com.agentos.taskowner.TaskOwnerDtos.Context;
import com.agentos.taskowner.TaskOwnerDtos.Owner;
import com.agentos.taskowner.TaskOwnerDtos.Receipt;
import com.agentos.taskowner.TaskOwnerDtos.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskOwnerService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public TaskOwnerService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private record Scope(UUID organizationId, UUID teamId, UUID missionId, UUID taskId) {}

  private record Snapshot(
      String taskTitle,
      UUID rawOwner,
      Owner owner,
      String taskStatus,
      int revision,
      String teamName,
      String teamStatus,
      String missionTitle,
      String missionStatus,
      boolean paused,
      String fingerprint) {}

  private record Stored(
      UUID eventId,
      String digest,
      String response,
      int revision,
      OffsetDateTime eventTime,
      OffsetDateTime receiptTime,
      UUID rawBefore,
      UUID rawAfter,
      String beforeJson,
      String afterJson) {}

  private static TaskOwnerFailure failure(int status, String code) {
    return new TaskOwnerFailure(status, code);
  }

  private void authorize(UUID org, HumanIdentity human, boolean lock) {
    String suffix = lock ? " for update" : "";
    if (human == null
        || jdbc.queryForList("select id from organizations where id=?" + suffix, org).isEmpty())
      throw failure(403, "FORBIDDEN");
    var accounts =
        jdbc.query(
            "select enabled,credential_version from human_accounts where id=?" + suffix,
            (rs, n) -> rs.getBoolean(1) && rs.getLong(2) == human.credentialVersion(),
            human.id());
    if (accounts.isEmpty() || !accounts.get(0)) throw failure(403, "FORBIDDEN");
    var roles =
        jdbc.query(
            "select role from organization_memberships where organization_id=? and account_id=?"
                + suffix,
            (rs, n) -> rs.getString(1),
            org,
            human.id());
    if (roles.isEmpty() || !"ADMIN".equals(roles.get(0))) throw failure(403, "FORBIDDEN");
  }

  private void resolve(Scope s, boolean lock) {
    String suffix = lock ? " for update" : "";
    if (jdbc.queryForList(
                "select id from teams where organization_id=? and id=?" + suffix,
                s.organizationId(),
                s.teamId())
            .isEmpty()
        || jdbc.queryForList(
                "select id from missions where organization_id=? and team_id=? and id=?" + suffix,
                s.organizationId(),
                s.teamId(),
                s.missionId())
            .isEmpty()
        || jdbc.queryForList(
                "select id from tasks where organization_id=? and team_id=? and mission_id=? and"
                    + " id=?"
                    + suffix,
                s.organizationId(),
                s.teamId(),
                s.missionId(),
                s.taskId())
            .isEmpty()) throw failure(404, "NOT_FOUND");
  }

  private Snapshot snapshot(Scope s) {
    return jdbc.queryForObject(
        "select t.*,g.name as team_name,g.status as team_status,m.title as mission_title,m.status"
            + " as mission_status,m.task_intake_paused,m.task_intake_version,g.updated_at as team_updated_at,m.updated_at as mission_updated_at,g.created_at as team_created_at,m.created_at as mission_created_at from tasks t join"
            + " teams g on g.organization_id=t.organization_id and g.id=t.team_id join missions m"
            + " on m.organization_id=t.organization_id and m.team_id=t.team_id and"
            + " m.id=t.mission_id where t.organization_id=? and t.team_id=? and t.mission_id=? and"
            + " t.id=?",
        (rs, n) -> {
          UUID rawOwner = rs.getObject("owner_agent_id", UUID.class);
          Agent agent = agent(s, rawOwner);
          Owner owner =
              new Owner(
                  rawOwner == null ? "UNASSIGNED" : agent == null ? "UNAVAILABLE" : "ASSIGNED",
                  agent);
          var dependencies =
              jdbc.query(
                  "select dependency_task_id from task_dependencies where organization_id=? and"
                      + " team_id=? and mission_id=? and task_id=? order by cast(dependency_task_id"
                      + " as varchar)",
                  (r, i) -> r.getString(1),
                  s.organizationId(),
                  s.teamId(),
                  s.missionId(),
                  s.taskId());
          // Ordered tuple is the versioned canonical source; null legacy values remain null.
          String fingerprint =
              hash(
                  Arrays.asList(
                      "TASK_OWNER_SNAPSHOT_V1",
                      s,
                      rs.getString("title"),
                      rs.getString("description"),
                      rs.getString("acceptance_criteria"),
                      rs.getString("priority"),
                      agentToken(s, rawOwner),
                      rs.getString("status"),
                      rs.getInt("task_edit_revision"),
                      rs.getInt("task_owner_revision"),
                      rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString(),
                      rs.getString("owner_agent_id"),
                      rs.getObject("created_at", OffsetDateTime.class).toInstant().toString(),
                      rs.getString("pending_reason"),
                      rs.getString("waiting_since"),
                      rs.getString("resolver"),
                      dependencies,
                      safeStored(rs.getString("team_name"), 255, false),
                      rs.getString("team_status"),
                      safeStored(rs.getString("mission_title"), 255, false),
                      rs.getString("mission_status"),
                      rs.getBoolean("task_intake_paused"),
                      rs.getLong("task_intake_version"),
                      rs.getObject("team_updated_at", OffsetDateTime.class).toInstant().toString(),
                      rs.getObject("mission_updated_at", OffsetDateTime.class)
                          .toInstant()
                          .toString(),
                      rs.getObject("team_created_at", OffsetDateTime.class).toInstant().toString(),
                      rs.getObject("mission_created_at", OffsetDateTime.class)
                          .toInstant()
                          .toString()));
          return new Snapshot(
              safeStored(rs.getString("title"), 255, true),
              rawOwner,
              owner,
              rs.getString("status"),
              rs.getInt("task_owner_revision"),
              safeStored(rs.getString("team_name"), 255, false),
              rs.getString("team_status"),
              safeStored(rs.getString("mission_title"), 255, false),
              rs.getString("mission_status"),
              rs.getBoolean("task_intake_paused"),
              fingerprint);
        },
        s.organizationId(),
        s.teamId(),
        s.missionId(),
        s.taskId());
  }

  private List<String> blockers(Snapshot s) {
    var reasons = new ArrayList<String>();
    boolean taskKnown =
        Set.of(
                "DRAFT",
                "READY",
                "RUNNING",
                "WAITING",
                "BLOCKED",
                "IN_REVIEW",
                "COMPLETED",
                "FAILED",
                "CANCELLED")
            .contains(s.taskStatus());
    boolean teamKnown = Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(s.teamStatus());
    boolean missionKnown =
        Set.of(
                "CREATED",
                "PLANNING",
                "READY",
                "RUNNING",
                "WAITING_APPROVAL",
                "REVIEW",
                "COMPLETED",
                "FAILED",
                "CANCELLED")
            .contains(s.missionStatus());
    if (taskKnown && !"DRAFT".equals(s.taskStatus())) reasons.add("TASK_NOT_DRAFT");
    if ("ARCHIVED".equals(s.teamStatus())) reasons.add("TEAM_ARCHIVED");
    if (missionKnown
        && !Set.of("CREATED", "READY", "RUNNING", "WAITING_APPROVAL").contains(s.missionStatus()))
      reasons.add("MISSION_NOT_EDITABLE");
    if (s.paused()) reasons.add("INTAKE_PAUSED");
    if (s.revision() == Integer.MAX_VALUE) reasons.add("REVISION_EXHAUSTED");
    if (!taskKnown || !teamKnown || !missionKnown) reasons.add("UNKNOWN_STATE");
    return List.copyOf(reasons);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID team, UUID mission, UUID task, HumanIdentity human) {
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    Snapshot s = snapshot(scope);
    var blockers = blockers(s);
    return new Context(
        org,
        team,
        mission,
        task,
        now(),
        s.revision(),
        s.fingerprint(),
        s.teamName(),
        s.missionTitle(),
        s.taskTitle(),
        s.taskStatus(),
        s.owner(),
        blockers.isEmpty(),
        blockers);
  }

  private String safeStored(String value, int max, boolean blank) {
    try {
      return TaskOwnerWire.text(value, max, blank);
    } catch (TaskOwnerFailure ex) {
      throw failure(500, "TASK_OWNER_UNAVAILABLE");
    }
  }

  private Candidate candidateRow(Scope scope, java.sql.ResultSet rs) throws java.sql.SQLException {
    UUID id = rs.getObject("id", UUID.class);
    String name = safeStored(rs.getString("name"), 255, false);
    String level = safeStored(rs.getString("level"), 50, false);
    String status = safeStored(rs.getString("status"), 50, false);
    String fp =
        hash(
            List.of(
                "TASK_OWNER_CANDIDATE_V1",
                scope.organizationId(),
                scope.teamId(),
                id,
                name,
                level,
                status,
                rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString()));
    return new Candidate(id, name, level, status, fp);
  }

  private Candidate scopedCandidate(Scope scope, UUID id) {
    if (id == null) return null;
    var rows =
        jdbc.query(
            "select id,name,level,status,updated_at from agents where organization_id=? and team_id=? and id=?",
            (rs, n) -> candidateRow(scope, rs),
            scope.organizationId(),
            scope.teamId(),
            id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Agent agent(Scope scope, UUID id) {
    Candidate c = scopedCandidate(scope, id);
    return c == null ? null : new Agent(c.id(), c.name(), c.level(), c.status());
  }

  private String agentToken(Scope scope, UUID id) {
    Candidate c = scopedCandidate(scope, id);
    return c == null ? null : c.fingerprint();
  }

  private Candidate eligible(Scope scope, UUID id) {
    Candidate c = scopedCandidate(scope, id);
    if (c == null) throw failure(404, "NOT_FOUND");
    if (!Set.of("DRAFT", "ACTIVE").contains(c.status()))
      throw failure(409, "TASK_OWNER_NOT_ELIGIBLE");
    return c;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidate candidate(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, UUID agentId) {
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    if (!blockers(snapshot(scope)).isEmpty()) throw failure(409, "TASK_OWNER_BLOCKED");
    return eligible(scope, agentId);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates owners(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, int page, String query) {
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    if (page < 0 || page > 100000) throw TaskOwnerFailure.bad();
    String normalized = TaskOwnerWire.query(query);
    if (!blockers(snapshot(scope)).isEmpty()) throw failure(409, "TASK_OWNER_BLOCKED");
    String pattern =
        "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    String where =
        " from agents where organization_id=? and team_id=? and status in ('DRAFT','ACTIVE') and lower(name) like lower(?) escape '!'";
    long total = jdbc.queryForObject("select count(*)" + where, Long.class, org, team, pattern);
    if (total < 0 || total > 9007199254740991L) throw failure(500, "TASK_OWNER_UNAVAILABLE");
    var items =
        jdbc.query(
            "select id,name,level,status,updated_at"
                + where
                + " order by name,id limit 20 offset ?",
            (rs, n) -> candidateRow(scope, rs),
            org,
            team,
            pattern,
            page * 20);
    return new Candidates(
        org, team, mission, task, page, 20, total, normalized, List.copyOf(items));
  }

  private Stored stored(Scope s, HumanIdentity human, UUID key) {
    var rows =
        jdbc.query(
            "select r.event_id,r.body_sha256,r.original_response,e.revision,e.created_at,"
                + "r.created_at,e.before_owner_agent_id,e.after_owner_agent_id,e.before_json,e.after_json"
                + " from task_owner_receipts r left join task_owner_events e on e.id=r.event_id"
                + " and e.organization_id=r.organization_id and e.team_id=r.team_id"
                + " and e.mission_id=r.mission_id and e.task_id=r.task_id and e.actor_id=r.actor_id"
                + " where r.organization_id=? and r.team_id=? and r.mission_id=? and r.task_id=?"
                + " and r.actor_id=? and r.idempotency_key=?",
            (rs, n) ->
                new Stored(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getInt(4),
                    rs.getObject(5, OffsetDateTime.class),
                    rs.getObject(6, OffsetDateTime.class),
                    rs.getObject(7, UUID.class),
                    rs.getObject(8, UUID.class),
                    rs.getString(9),
                    rs.getString(10)),
            s.organizationId(),
            s.teamId(),
            s.missionId(),
            s.taskId(),
            human.id(),
            key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Receipt decode(Scope scope, Stored saved) {
    try {
      ObjectMapper strict =
          json.copy()
              .enable(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .enable(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      strict.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      strict.enable(
          com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
      strict.disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
      strict.disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
      var tree = strict.readTree(saved.response());
      validateReceiptIds(tree);
      if (!tree.path("revision").isIntegralNumber() || !tree.path("revision").canConvertToInt())
        throw new IllegalArgumentException();
      Receipt r = strict.treeToValue(tree, Receipt.class);
      if (r.organizationId() == null
          || r.teamId() == null
          || r.missionId() == null
          || r.taskId() == null
          || r.eventId() == null
          || r.revision() < 1
          || r.committedAt() == null) throw new IllegalArgumentException();
      if (!scope.organizationId().equals(r.organizationId())
          || !scope.teamId().equals(r.teamId())
          || !scope.missionId().equals(r.missionId())
          || !scope.taskId().equals(r.taskId())
          || !saved.eventId().equals(r.eventId())
          || saved.digest() == null
          || !saved.digest().matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      if (!r.committedAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z"))
        throw new IllegalArgumentException();
      var time = java.time.Instant.parse(r.committedAt());
      validateOwner(r.before(), false);
      validateOwner(r.after(), true);
      if (r.revision() != saved.revision()
          || saved.eventTime() == null
          || saved.receiptTime() == null
          || !time.equals(saved.eventTime().toInstant())
          || !time.equals(saved.receiptTime().toInstant())
          || !r.before().equals(strict.readValue(saved.beforeJson(), Owner.class))
          || !r.after().equals(strict.readValue(saved.afterJson(), Owner.class))
          || java.util.Objects.equals(saved.rawBefore(), saved.rawAfter()))
        throw new IllegalArgumentException();
      validateProvenance(r.before(), saved.rawBefore());
      validateProvenance(r.after(), saved.rawAfter());
      return r;
    } catch (Exception ex) {
      throw failure(500, "TASK_OWNER_UNAVAILABLE");
    }
  }

  private void validateReceiptIds(com.fasterxml.jackson.databind.JsonNode tree) {
    for (String field : List.of("organizationId", "teamId", "missionId", "taskId", "eventId")) {
      if (!tree.path(field).isTextual()) throw new IllegalArgumentException();
      TaskOwnerWire.uuid(tree.path(field).textValue());
    }
    for (String field : List.of("before", "after")) {
      var agent = tree.path(field).path("agent");
      if (!agent.isMissingNode() && !agent.isNull()) {
        if (!agent.path("id").isTextual()) throw new IllegalArgumentException();
        TaskOwnerWire.uuid(agent.path("id").textValue());
      }
    }
  }

  private void validateProvenance(Owner owner, UUID raw) {
    if ("UNASSIGNED".equals(owner.state())
        ? raw != null
        : raw == null || (owner.agent() != null && !raw.equals(owner.agent().id())))
      throw new IllegalArgumentException();
  }

  private void validateOwner(Owner owner, boolean after) {
    if (owner == null) throw new IllegalArgumentException();
    if ("ASSIGNED".equals(owner.state())) {
      Agent a = owner.agent();
      if (a == null || a.id() == null) throw new IllegalArgumentException();
      safeStored(a.name(), 255, false);
      safeStored(a.level(), 50, false);
      safeStored(a.status(), 50, false);
      if (after && !Set.of("DRAFT", "ACTIVE").contains(a.status()))
        throw new IllegalArgumentException();
    } else if (owner.agent() != null
        || !("UNASSIGNED".equals(owner.state()) || (!after && "UNAVAILABLE".equals(owner.state()))))
      throw new IllegalArgumentException();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Receipt receipt(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, UUID key) {
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    Stored saved = stored(scope, human, key);
    return saved == null ? null : decode(scope, saved);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result update(
      UUID org,
      UUID team,
      UUID mission,
      UUID task,
      HumanIdentity human,
      UUID key,
      Command command) {
    authorize(org, human, true);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, true);
    authorize(org, human, false);
    validateCommand(command, key);
    String digest = hash(List.of("TASK_OWNER_COMMAND_V1", scope, human.id(), command));
    Stored saved = stored(scope, human, key);
    if (saved != null) {
      Receipt original = decode(scope, saved);
      if (!digest.equals(saved.digest())) throw failure(409, "IDEMPOTENCY_CONFLICT");
      return new Result(original, true);
    }
    Snapshot initial = snapshot(scope);
    java.util.TreeSet<UUID> locks =
        new java.util.TreeSet<>(java.util.Comparator.comparing(UUID::toString));
    if (initial.rawOwner() != null) locks.add(initial.rawOwner());
    if (command.ownerAgentId() != null) locks.add(command.ownerAgentId());
    for (UUID id : locks)
      jdbc.queryForList(
          "select id from agents where organization_id=? and id=? for update", org, id);
    authorize(org, human, false);
    Snapshot source = snapshot(scope);
    if (!blockers(source).isEmpty()) throw failure(409, "TASK_OWNER_BLOCKED");
    if (source.revision() != command.expectedRevision()
        || !source.fingerprint().equals(command.expectedFingerprint()))
      throw failure(409, "TASK_OWNER_CONFLICT");
    if (java.util.Objects.equals(source.rawOwner(), command.ownerAgentId()))
      throw failure(409, "TASK_OWNER_NO_CHANGE");
    Owner after = new Owner("UNASSIGNED", null);
    if (command.ownerAgentId() != null) {
      Candidate target = scopedCandidate(scope, command.ownerAgentId());
      if (target == null || !Set.of("DRAFT", "ACTIVE").contains(target.status()))
        throw failure(409, "TASK_OWNER_NOT_ELIGIBLE");
      if (!target.fingerprint().equals(command.ownerFingerprint()))
        throw failure(409, "TASK_OWNER_CANDIDATE_CONFLICT");
      after =
          new Owner(
              "ASSIGNED", new Agent(target.id(), target.name(), target.level(), target.status()));
    }
    int revision = source.revision() + 1;
    String time = now();
    OffsetDateTime timestamp = OffsetDateTime.parse(time);
    UUID event = UUID.randomUUID();
    if (jdbc.update(
            "update tasks set owner_agent_id=?,task_owner_revision=?,updated_at=? where organization_id=? and team_id=? and mission_id=? and id=? and task_owner_revision=?",
            command.ownerAgentId(),
            revision,
            timestamp,
            org,
            team,
            mission,
            task,
            source.revision())
        != 1) throw failure(409, "TASK_OWNER_CONFLICT");
    Receipt receipt =
        new Receipt(org, team, mission, task, event, revision, time, source.owner(), after);
    jdbc.update(
        "insert into"
            + " task_owner_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_owner_agent_id,after_owner_agent_id,before_json,after_json)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
        event,
        org,
        team,
        mission,
        task,
        revision,
        human.id(),
        timestamp,
        source.rawOwner(),
        command.ownerAgentId(),
        encode(source.owner()),
        encode(after));
    jdbc.update(
        "insert into"
            + " task_owner_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        org,
        team,
        mission,
        task,
        human.id(),
        key,
        event,
        digest,
        encode(receipt),
        timestamp);
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,?,?,'HUMAN',?,'TASK_OWNER_UPDATED',?,?)",
        UUID.randomUUID(),
        org,
        mission,
        task,
        human.id(),
        encode(Map.of("eventId", event, "revision", revision)),
        timestamp);
    return new Result(receipt, false);
  }

  private void validateCommand(Command command, UUID key) {
    if (command == null
        || key == null
        || command.expectedRevision() < 0
        || command.expectedFingerprint() == null
        || !command.expectedFingerprint().matches("[0-9a-f]{64}")) throw TaskOwnerFailure.bad();
    TaskOwnerWire.uuid(key.toString());
    if (command.ownerAgentId() == null) {
      if (command.ownerFingerprint() != null) throw TaskOwnerFailure.bad();
    } else {
      TaskOwnerWire.uuid(command.ownerAgentId().toString());
      if (command.ownerFingerprint() == null || !command.ownerFingerprint().matches("[0-9a-f]{64}"))
        throw TaskOwnerFailure.bad();
    }
  }

  private String now() {
    return new DateTimeFormatterBuilder()
        .appendInstant(6)
        .toFormatter()
        .format(jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class).toInstant());
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}

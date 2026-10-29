package com.agentos.taskediting;

import com.agentos.auth.HumanIdentity;
import com.agentos.taskediting.TaskDraftEditDtos.Command;
import com.agentos.taskediting.TaskDraftEditDtos.Context;
import com.agentos.taskediting.TaskDraftEditDtos.Receipt;
import com.agentos.taskediting.TaskDraftEditDtos.Result;
import com.agentos.taskediting.TaskDraftEditDtos.Values;
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
public class TaskDraftEditService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public TaskDraftEditService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private record Scope(UUID organizationId, UUID teamId, UUID missionId, UUID taskId) {}

  private record Snapshot(
      Values values,
      String taskStatus,
      int revision,
      String teamName,
      String teamStatus,
      String missionTitle,
      String missionStatus,
      boolean paused,
      String fingerprint) {}

  private record Stored(String digest, String response) {}

  private static TaskDraftEditFailure failure(int status, String code) {
    return new TaskDraftEditFailure(status, code);
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
            + " as mission_status,m.task_intake_paused,m.task_intake_version from tasks t join"
            + " teams g on g.organization_id=t.organization_id and g.id=t.team_id join missions m"
            + " on m.organization_id=t.organization_id and m.team_id=t.team_id and"
            + " m.id=t.mission_id where t.organization_id=? and t.team_id=? and t.mission_id=? and"
            + " t.id=?",
        (rs, n) -> {
          Values values =
              new Values(
                  rs.getString("title"),
                  rs.getString("description"),
                  rs.getString("acceptance_criteria"),
                  rs.getString("priority"));
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
                      "TASK_DRAFT_EDIT_SNAPSHOT_V2",
                      s,
                      values,
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
                      rs.getString("team_name"),
                      rs.getString("team_status"),
                      rs.getString("mission_title"),
                      rs.getString("mission_status"),
                      rs.getBoolean("task_intake_paused"),
                      rs.getLong("task_intake_version")));
          return new Snapshot(
              values,
              rs.getString("status"),
              rs.getInt("task_edit_revision"),
              rs.getString("team_name"),
              rs.getString("team_status"),
              rs.getString("mission_title"),
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
        s.taskStatus(),
        s.values(),
        blockers.isEmpty(),
        blockers);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public TaskDraftEditDtos.History history(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, int page) {
    if (page < 0 || page > 100000) throw TaskDraftEditFailure.bad();
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    long total =
        jdbc.queryForObject(
            "select count(*) from task_edit_events where organization_id=? and team_id=? and mission_id=? and task_id=?",
            Long.class,
            org,
            team,
            mission,
            task);
    var items =
        jdbc.query(
            "select e.id,e.revision,e.created_at,a.display_name,e.before_json,e.after_json"
                + " from task_edit_events e left join human_accounts a on a.id=e.actor_id"
                + " where e.organization_id=? and e.team_id=? and e.mission_id=? and e.task_id=?"
                + " order by e.revision desc limit 20 offset ?",
            (rs, n) -> {
              UUID eventId = rs.getObject("id", UUID.class);
              int revision = rs.getInt("revision");
              OffsetDateTime time = rs.getObject("created_at", OffsetDateTime.class);
              String actorName = rs.getString("display_name");
              if (eventId == null || revision < 1 || time == null || actorName == null)
                throw new TaskHistoryFailure();
              TaskDraftEditWire.historyActorName(actorName);
              Values before = TaskDraftEditWire.historyValues(rs.getString("before_json"));
              Values after = TaskDraftEditWire.historyValues(rs.getString("after_json"), true);
              if (before.equals(after)) throw new TaskHistoryFailure();
              return new TaskDraftEditDtos.HistoryItem(
                  eventId, revision, time.toInstant().toString(), actorName, before, after);
            },
            org,
            team,
            mission,
            task,
            page * 20);
    return new TaskDraftEditDtos.History(
        org, team, mission, task, page, 20, total, List.copyOf(items));
  }

  private Stored stored(Scope s, HumanIdentity human, UUID key) {
    var rows =
        jdbc.query(
            "select body_sha256,original_response from task_edit_receipts where organization_id=?"
                + " and team_id=? and mission_id=? and task_id=? and actor_id=? and"
                + " idempotency_key=?",
            (rs, n) -> new Stored(rs.getString(1), rs.getString(2)),
            s.organizationId(),
            s.teamId(),
            s.missionId(),
            s.taskId(),
            human.id(),
            key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Receipt decode(String response) {
    try {
      return json.readValue(response, Receipt.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid saved task edit receipt", ex);
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Receipt receipt(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, UUID key) {
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    Stored saved = stored(scope, human, key);
    return saved == null ? null : decode(saved.response());
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
    String digest = hash(List.of("TASK_DRAFT_EDIT_COMMAND_V1", scope, human.id(), command));
    Stored saved = stored(scope, human, key);
    if (saved != null) {
      if (!saved.digest().equals(digest)) throw failure(409, "IDEMPOTENCY_CONFLICT");
      return new Result(decode(saved.response()), true);
    }
    Snapshot source = snapshot(scope);
    if (source.revision() != command.expectedRevision()
        || !source.fingerprint().equals(command.expectedFingerprint()))
      throw failure(409, "TASK_EDIT_CONFLICT");
    if (!blockers(source).isEmpty()) throw failure(409, "TASK_EDIT_BLOCKED");
    Values after = command.values();
    if (source.values().equals(after)) throw failure(409, "TASK_EDIT_NO_CHANGE");
    int revision = source.revision() + 1;
    String time = now();
    OffsetDateTime timestamp = OffsetDateTime.parse(time);
    UUID event = UUID.randomUUID();
    if (jdbc.update(
            "update tasks set"
                + " title=?,description=?,acceptance_criteria=?,priority=?,updated_at=?,task_edit_revision=?"
                + " where organization_id=? and team_id=? and mission_id=? and id=? and"
                + " task_edit_revision=?",
            after.title(),
            after.description(),
            after.acceptanceCriteria(),
            after.priority(),
            timestamp,
            revision,
            org,
            team,
            mission,
            task,
            source.revision())
        != 1) throw failure(409, "TASK_EDIT_CONFLICT");
    Receipt receipt =
        new Receipt(org, team, mission, task, event, revision, time, source.values(), after);
    jdbc.update(
        "insert into"
            + " task_edit_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        event,
        org,
        team,
        mission,
        task,
        revision,
        human.id(),
        timestamp,
        encode(source.values()),
        encode(after));
    jdbc.update(
        "insert into"
            + " task_edit_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response)"
            + " values (?,?,?,?,?,?,?,?,?)",
        org,
        team,
        mission,
        task,
        human.id(),
        key,
        event,
        digest,
        encode(receipt));
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,?,?,'HUMAN',?,'TASK_DRAFT_UPDATED',?,?)",
        UUID.randomUUID(),
        org,
        mission,
        task,
        human.id(),
        encode(Map.of("eventId", event, "revision", revision)),
        timestamp);
    return new Result(receipt, false);
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

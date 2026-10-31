package com.agentos.taskdependencies;

import com.agentos.auth.HumanIdentity;
import com.agentos.taskdependencies.TaskDependenciesDtos.Candidate;
import com.agentos.taskdependencies.TaskDependenciesDtos.Candidates;
import com.agentos.taskdependencies.TaskDependenciesDtos.Command;
import com.agentos.taskdependencies.TaskDependenciesDtos.Context;
import com.agentos.taskdependencies.TaskDependenciesDtos.Receipt;
import com.agentos.taskdependencies.TaskDependenciesDtos.Result;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskDependenciesService {
  private final JdbcTemplate jdbc;
  private static final ObjectMapper STRICT =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final String WHERE = " organization_id=? and team_id=? and mission_id=?";

  private record Scope(UUID org, UUID team, UUID mission, UUID task) {
    List<String> tuple() {
      return List.of(org.toString(), team.toString(), mission.toString(), task.toString());
    }
  }

  private record Snapshot(
      TaskDependenciesGraph graph,
      TaskDependenciesGraph.Node source,
      String teamName,
      String teamStatus,
      String missionTitle,
      String missionStatus,
      boolean paused,
      String fingerprint) {}

  private record Saved(
      UUID event,
      String digest,
      String response,
      int revision,
      OffsetDateTime eventTime,
      OffsetDateTime receiptTime,
      String before,
      String after) {}

  public TaskDependenciesService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static TaskDependenciesFailure failure(int status, String code) {
    return new TaskDependenciesFailure(status, code);
  }

  private static TaskDependenciesFailure conflict(String suffix) {
    return failure(409, "TASK_DEPENDENCIES_" + suffix);
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
    if (roles.size() != 1 || !"ADMIN".equals(roles.get(0))) throw failure(403, "FORBIDDEN");
  }

  private void resolve(Scope s, boolean lock) {
    String suffix = lock ? " for update" : "";
    if (jdbc.queryForList(
                "select id from teams where organization_id=? and id=?" + suffix, s.org(), s.team())
            .isEmpty()
        || jdbc.queryForList(
                "select id from missions where organization_id=? and team_id=? and id=?" + suffix,
                s.org(),
                s.team(),
                s.mission())
            .isEmpty()
        || jdbc.queryForList(
                "select id from tasks where" + WHERE + " and id=?",
                s.org(),
                s.team(),
                s.mission(),
                s.task())
            .isEmpty()) throw failure(404, "NOT_FOUND");
  }

  private void lockTasks(Scope s) {
    String after = "";
    while (true) {
      var ids =
          jdbc.query(
              "select id from tasks where"
                  + WHERE
                  + " and cast(id as varchar)>? order by cast(id as varchar) limit 512",
              (rs, n) -> rs.getObject(1, UUID.class),
              s.org(),
              s.team(),
              s.mission(),
              after);
      for (UUID id : ids)
        jdbc.queryForList(
            "select id from tasks where" + WHERE + " and id=? for update",
            s.org(),
            s.team(),
            s.mission(),
            id);
      if (ids.size() < 512) return;
      after = ids.get(ids.size() - 1).toString();
    }
  }

  private Snapshot snapshot(Scope s) {
    var graph = TaskDependenciesGraph.read(jdbc, s.org(), s.team(), s.mission());
    var source = graph.nodes.get(s.task());
    if (source == null) throw failure(404, "NOT_FOUND");
    return jdbc.queryForObject(
        "select"
            + " g.name,g.status,g.created_at,g.updated_at,m.title,m.status,m.created_at,m.updated_at,m.task_intake_paused,m.task_intake_version"
            + " from teams g join missions m on m.organization_id=g.organization_id and"
            + " m.team_id=g.id where g.organization_id=? and g.id=? and m.id=?",
        (rs, n) -> {
          String teamName = TaskDependenciesGraph.text(rs.getString(1), 255, false),
              teamStatus = TaskDependenciesGraph.text(rs.getString(2), 50, false),
              missionTitle = TaskDependenciesGraph.text(rs.getString(5), 255, false),
              missionStatus = TaskDependenciesGraph.text(rs.getString(6), 50, false);
          boolean paused = rs.getBoolean(9);
          if (rs.wasNull()) throw TaskDependenciesGraph.unavailable();
          long version = rs.getLong(10);
          if (rs.wasNull() || version < 0) throw TaskDependenciesGraph.unavailable();
          var parent =
              List.of(
                  teamName,
                  teamStatus,
                  TaskDependenciesGraph.time(rs.getObject(3, OffsetDateTime.class)),
                  TaskDependenciesGraph.time(rs.getObject(4, OffsetDateTime.class)),
                  missionTitle,
                  missionStatus,
                  TaskDependenciesGraph.time(rs.getObject(7, OffsetDateTime.class)),
                  TaskDependenciesGraph.time(rs.getObject(8, OffsetDateTime.class)),
                  paused,
                  version);
          String fingerprint =
              TaskDependenciesGraph.hash(
                  List.of(
                      "TASK_DEPENDENCIES_SNAPSHOT_V1",
                      s.tuple(),
                      source.source(),
                      parent,
                      graph.fingerprint));
          return new Snapshot(
              graph,
              source,
              teamName,
              teamStatus,
              missionTitle,
              missionStatus,
              paused,
              fingerprint);
        },
        s.org(),
        s.team(),
        s.mission());
  }

  private List<String> blockers(Snapshot s) {
    var result = new ArrayList<String>();
    boolean taskKnown = TaskDependenciesGraph.STATUSES.contains(s.source().status());
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
    if (taskKnown && !"DRAFT".equals(s.source().status())) result.add("TASK_NOT_DRAFT");
    if ("ARCHIVED".equals(s.teamStatus())) result.add("TEAM_ARCHIVED");
    if (missionKnown
        && !Set.of("CREATED", "READY", "RUNNING", "WAITING_APPROVAL").contains(s.missionStatus()))
      result.add("MISSION_NOT_EDITABLE");
    if (s.paused()) result.add("INTAKE_PAUSED");
    if (s.source().dependency() == Integer.MAX_VALUE) result.add("REVISION_EXHAUSTED");
    if (!taskKnown
        || !teamKnown
        || !missionKnown
        || s.graph().nodes.values().stream()
            .anyMatch(node -> !TaskDependenciesGraph.STATUSES.contains(node.status())))
      result.add("UNKNOWN_STATE");
    return List.copyOf(result);
  }

  private Snapshot read(Scope s, HumanIdentity human) {
    authorize(s.org(), human, false);
    resolve(s, false);
    return snapshot(s);
  }

  private void writable(Snapshot s) {
    if (!blockers(s).isEmpty()) throw conflict("BLOCKED");
  }

  private Candidate candidate(
      Scope s, TaskDependenciesGraph graph, TaskDependenciesGraph.Node node) {
    return new Candidate(
        node.id(),
        node.title(),
        node.status(),
        TaskDependenciesGraph.hash(
            List.of("TASK_DEPENDENCIES_TARGET_V1", s.tuple(), graph.fingerprint, node.tuple())));
  }

  private String now() {
    // Persist at database microsecond precision so H2/PostgreSQL replay agrees exactly.
    OffsetDateTime timestamp =
        jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (timestamp == null) throw TaskDependenciesGraph.unavailable();
    return timestamp.toInstant().truncatedTo(ChronoUnit.MICROS).toString();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID team, UUID mission, UUID task, HumanIdentity human) {
    Scope scope = new Scope(org, team, mission, task);
    Snapshot s = read(scope, human);
    var blockers = blockers(s);
    return new Context(
        org,
        team,
        mission,
        task,
        now(),
        s.source().dependency(),
        s.fingerprint(),
        s.teamName(),
        s.missionTitle(),
        s.source().title(),
        s.source().status(),
        s.graph().selected(task),
        blockers.isEmpty(),
        blockers);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidate candidate(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, UUID target) {
    Scope scope = new Scope(org, team, mission, task);
    Snapshot s = read(scope, human);
    writable(s);
    var node = s.graph().nodes.get(target);
    if (node == null) throw failure(404, "NOT_FOUND");
    if (s.graph().reaching(task).contains(target)) throw conflict("NOT_ELIGIBLE");
    return candidate(scope, s.graph(), node);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Candidates dependencies(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, int page, String query) {
    Scope scope = new Scope(org, team, mission, task);
    Snapshot s = read(scope, human);
    writable(s);
    if (page < 0 || page > 100000) throw TaskDependenciesFailure.bad();
    String normalized = TaskDependenciesWire.query(query),
        match = normalized.toLowerCase(Locale.ROOT);
    var excluded = s.graph().reaching(task);
    var nodes =
        s.graph().nodes.values().stream()
            .filter(
                node ->
                    !excluded.contains(node.id())
                        && node.title().toLowerCase(Locale.ROOT).contains(match))
            .sorted(
                (a, b) -> {
                  int order = codepointCompare(a.title(), b.title());
                  return order == 0 ? a.id().toString().compareTo(b.id().toString()) : order;
                })
            .toList();
    var items =
        nodes.stream()
            .skip(page * 20L)
            .limit(20)
            .map(node -> candidate(scope, s.graph(), node))
            .toList();
    return new Candidates(org, team, mission, task, page, 20, nodes.size(), normalized, items);
  }

  private static int codepointCompare(String a, String b) {
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      int x = a.codePointAt(i), y = b.codePointAt(j);
      if (x != y) return Integer.compare(x, y);
      i += Character.charCount(x);
      j += Character.charCount(y);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private Saved stored(Scope s, HumanIdentity human, UUID key) {
    var rows =
        jdbc.query(
            "select"
                + " r.event_id,r.body_sha256,r.original_response,e.revision,e.created_at,r.created_at,e.before_json,e.after_json"
                + " from task_dependency_receipts r left join task_dependency_events e on"
                + " e.id=r.event_id and e.organization_id=r.organization_id and e.team_id=r.team_id"
                + " and e.mission_id=r.mission_id and e.task_id=r.task_id and e.actor_id=r.actor_id"
                + " where r.organization_id=? and r.team_id=? and r.mission_id=? and r.task_id=?"
                + " and r.actor_id=? and r.idempotency_key=?",
            (rs, n) ->
                new Saved(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getInt(4),
                    rs.getObject(5, OffsetDateTime.class),
                    rs.getObject(6, OffsetDateTime.class),
                    rs.getString(7),
                    rs.getString(8)),
            s.org(),
            s.team(),
            s.mission(),
            s.task(),
            human.id(),
            key);
    if (rows.size() > 1) throw TaskDependenciesGraph.unavailable();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static TaskDependenciesFailure historyUnavailable() {
    return failure(500, "TASK_DEPENDENCY_HISTORY_UNAVAILABLE");
  }

  private static List<TaskDependenciesDtos.Task> historySnapshot(String value) {
    try {
      return historical(tree(value));
    } catch (Exception ex) {
      throw historyUnavailable();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public TaskDependenciesDtos.History history(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, int page) {
    authorize(org, human, false);
    Scope scope = new Scope(org, team, mission, task);
    resolve(scope, false);
    if (page < 0 || page > 100000) throw TaskDependenciesFailure.bad();
    try {
      var names =
          jdbc.queryForObject(
              "select g.name,m.title,t.title from tasks t join teams g"
                  + " on g.organization_id=t.organization_id and g.id=t.team_id"
                  + " join missions m on m.organization_id=t.organization_id"
                  + " and m.team_id=t.team_id and m.id=t.mission_id"
                  + " where t.organization_id=? and t.team_id=? and t.mission_id=? and t.id=?",
              (rs, n) ->
                  List.of(
                      TaskDependenciesGraph.text(rs.getString(1), 255, false),
                      TaskDependenciesGraph.text(rs.getString(2), 255, false),
                      TaskDependenciesGraph.text(rs.getString(3), 255, true)),
              org,
              team,
              mission,
              task);
      String where =
          " where e.organization_id=? and e.team_id=? and e.mission_id=? and e.task_id=?";
      long total =
          jdbc.queryForObject(
              "select count(*) from task_dependency_events e" + where,
              Long.class,
              org,
              team,
              mission,
              task);
      if (total < 0 || total > 9007199254740991L) throw historyUnavailable();
      var items =
          jdbc.query(
              "select"
                  + " e.id,e.revision,e.created_at,a.display_name,e.before_json,e.after_json,e.actor_id"
                  + " from task_dependency_events e left join organization_memberships h on"
                  + " h.organization_id=e.organization_id and h.account_id=e.actor_id left join"
                  + " human_accounts a on a.id=h.account_id"
                  + where
                  + " order by e.revision desc limit 20 offset ?",
              (rs, n) -> {
                UUID event = TaskDependenciesWire.uuid(rs.getObject(1, UUID.class).toString());
                TaskDependenciesWire.uuid(rs.getObject(7, UUID.class).toString());
                int revision = rs.getInt(2);
                if (rs.wasNull() || revision < 1) throw historyUnavailable();
                String time = TaskDependenciesGraph.time(rs.getObject(3, OffsetDateTime.class));
                String actor = rs.getString(4);
                if (actor != null) actor = TaskDependenciesGraph.text(actor, 255, false);
                var before = historySnapshot(rs.getString(5));
                var after = historySnapshot(rs.getString(6));
                if (before.stream()
                        .map(TaskDependenciesDtos.Task::id)
                        .toList()
                        .equals(after.stream().map(TaskDependenciesDtos.Task::id).toList())
                    || before.stream().anyMatch(t -> t.id().equals(task))
                    || after.stream().anyMatch(t -> t.id().equals(task)))
                  throw historyUnavailable();
                return new TaskDependenciesDtos.HistoryItem(
                    event, revision, time, actor, before, after);
              },
              org,
              team,
              mission,
              task,
              page * 20);
      if (items.size() != Math.min(20L, Math.max(total - page * 20L, 0L)))
        throw historyUnavailable();
      var ids = new HashSet<UUID>();
      long previous = (long) Integer.MAX_VALUE + 1;
      for (var item : items) {
        if (!ids.add(item.eventId()) || item.revision() >= previous) throw historyUnavailable();
        previous = item.revision();
      }
      return new TaskDependenciesDtos.History(
          org,
          team,
          mission,
          task,
          names.get(0),
          names.get(1),
          names.get(2),
          page,
          20,
          total,
          List.copyOf(items));
    } catch (RuntimeException ex) {
      throw historyUnavailable();
    }
  }

  private static JsonNode tree(String value) throws IOException {
    if (value == null) throw TaskDependenciesGraph.unavailable();
    try (var parser = STRICT.getFactory().createParser(value)) {
      JsonNode node = STRICT.readTree(parser);
      if (node == null || parser.nextToken() != null) throw TaskDependenciesGraph.unavailable();
      return node;
    }
  }

  private static List<TaskDependenciesDtos.Task> historical(JsonNode node) {
    if (node == null || !node.isArray() || node.size() > 20)
      throw TaskDependenciesGraph.unavailable();
    var result = new ArrayList<TaskDependenciesDtos.Task>();
    String previous = "";
    for (JsonNode row : node) {
      TaskDependenciesWire.exact(row, Set.of("id", "title", "status"));
      UUID id = TaskDependenciesWire.uuid(TaskDependenciesWire.string(row.get("id")));
      String
          title =
              TaskDependenciesGraph.text(TaskDependenciesWire.string(row.get("title")), 255, true),
          status =
              TaskDependenciesGraph.text(TaskDependenciesWire.string(row.get("status")), 50, false);
      if (!TaskDependenciesGraph.STATUSES.contains(status)
          || previous.compareTo(id.toString()) >= 0) throw TaskDependenciesGraph.unavailable();
      previous = id.toString();
      result.add(new TaskDependenciesDtos.Task(id, title, status));
    }
    return List.copyOf(result);
  }

  private Receipt decode(Scope s, Saved saved) {
    try {
      var node = tree(saved.response());
      TaskDependenciesWire.exact(
          node,
          Set.of(
              "organizationId",
              "teamId",
              "missionId",
              "taskId",
              "eventId",
              "revision",
              "committedAt",
              "before",
              "after"));
      var ids = new ArrayList<UUID>();
      for (String field : List.of("organizationId", "teamId", "missionId", "taskId", "eventId"))
        ids.add(TaskDependenciesWire.uuid(TaskDependenciesWire.string(node.get(field))));
      int revision = TaskDependenciesWire.integer(node.get("revision"), 1, Integer.MAX_VALUE);
      String time = TaskDependenciesWire.string(node.get("committedAt"));
      if (!time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z"))
        throw TaskDependenciesGraph.unavailable();
      var before = historical(node.get("before"));
      var after = historical(node.get("after"));
      if (!ids.subList(0, 4).equals(List.of(s.org(), s.team(), s.mission(), s.task()))
          || !ids.get(4).equals(saved.event())
          || saved.digest() == null
          || !saved.digest().matches("[0-9a-f]{64}")
          || revision != saved.revision()
          || !Instant.parse(time)
              .equals(Instant.parse(TaskDependenciesGraph.time(saved.eventTime())))
          || !Instant.parse(time)
              .equals(Instant.parse(TaskDependenciesGraph.time(saved.receiptTime())))
          || !before.equals(historical(tree(saved.before())))
          || !after.equals(historical(tree(saved.after())))
          || before.stream()
              .map(TaskDependenciesDtos.Task::id)
              .toList()
              .equals(after.stream().map(TaskDependenciesDtos.Task::id).toList())
          || before.stream().anyMatch(t -> t.id().equals(s.task()))
          || after.stream().anyMatch(t -> t.id().equals(s.task())))
        throw TaskDependenciesGraph.unavailable();
      return new Receipt(
          s.org(), s.team(), s.mission(), s.task(), saved.event(), revision, time, before, after);
    } catch (Exception ex) {
      throw TaskDependenciesGraph.unavailable();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Receipt receipt(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, UUID key) {
    Scope s = new Scope(org, team, mission, task);
    authorize(org, human, false);
    resolve(s, false);
    Saved saved = stored(s, human, key);
    return saved == null ? null : decode(s, saved);
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
    Scope s = new Scope(org, team, mission, task);
    // Validate and canonicalize even for non-HTTP callers; no mutable reads precede replay.
    if (command == null || key == null) throw TaskDependenciesFailure.bad();
    TaskDependenciesWire.uuid(key.toString());
    Command canonical;
    try {
      canonical =
          TaskDependenciesWire.read(
              new ByteArrayInputStream(
                  TaskDependenciesGraph.encode(command).getBytes(StandardCharsets.UTF_8)),
              task);
    } catch (IOException ex) {
      throw TaskDependenciesFailure.bad();
    }
    authorize(org, human, true);
    resolve(s, true);
    lockTasks(s);
    authorize(org, human, false);
    resolve(s, false);
    String digest =
        TaskDependenciesGraph.hash(
            List.of(
                "TASK_DEPENDENCIES_COMMAND_V1",
                s.tuple(),
                human.id().toString(),
                canonical.expectedRevision(),
                canonical.expectedFingerprint(),
                canonical.dependencies().stream()
                    .map(t -> List.of(t.taskId().toString(), t.fingerprint()))
                    .toList()));
    Saved saved = stored(s, human, key);
    if (saved != null) {
      Receipt original = decode(s, saved);
      if (!digest.equals(saved.digest())) throw failure(409, "IDEMPOTENCY_CONFLICT");
      return new Result(original, true);
    }
    Snapshot current = snapshot(s);
    TaskDependenciesGraph graph = current.graph();
    if (current.source().dependency() != canonical.expectedRevision()
        || !current.fingerprint().equals(canonical.expectedFingerprint()))
      throw conflict("CONFLICT");
    writable(current);
    var excluded = graph.reaching(task);
    var desired = new HashSet<UUID>();
    for (var target : canonical.dependencies()) {
      var node = graph.nodes.get(target.taskId());
      if (node == null
          || excluded.contains(target.taskId())
          || !TaskDependenciesGraph.STATUSES.contains(node.status()))
        throw conflict("NOT_ELIGIBLE");
      if (!candidate(s, graph, node).fingerprint().equals(target.fingerprint()))
        throw conflict("CANDIDATE_CONFLICT");
      desired.add(target.taskId());
    }
    var originalIds = graph.edges.get(task);
    var before = graph.selected(task);
    var after = graph.tasks(desired);
    graph.edges.put(task, desired);
    graph.acyclic(true);
    if (originalIds.equals(desired)) throw conflict("NO_CHANGE");
    int revision = current.source().dependency() + 1;
    String time = now();
    var timestamp = OffsetDateTime.parse(time);
    UUID event = UUID.randomUUID();
    if (jdbc.update(
            "update tasks set dependency_revision=?,updated_at=? where"
                + WHERE
                + " and id=? and dependency_revision=?",
            revision,
            timestamp,
            org,
            team,
            mission,
            task,
            current.source().dependency())
        != 1) throw conflict("CONFLICT");
    jdbc.update(
        "delete from task_dependencies where" + WHERE + " and task_id=?", org, team, mission, task);
    for (var target : canonical.dependencies())
      jdbc.update(
          "insert into"
              + " task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)"
              + " values (?,?,?,?,?)",
          org,
          team,
          mission,
          task,
          target.taskId());
    Receipt receipt = new Receipt(org, team, mission, task, event, revision, time, before, after);
    jdbc.update(
        "insert into"
            + " task_dependency_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        event,
        org,
        team,
        mission,
        task,
        revision,
        human.id(),
        timestamp,
        TaskDependenciesGraph.encode(before),
        TaskDependenciesGraph.encode(after));
    jdbc.update(
        "insert into"
            + " task_dependency_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        org,
        team,
        mission,
        task,
        human.id(),
        key,
        event,
        digest,
        TaskDependenciesGraph.encode(receipt),
        timestamp);
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,?,?,'HUMAN',?,'TASK_DEPENDENCIES_UPDATED',?,?)",
        UUID.randomUUID(),
        org,
        mission,
        task,
        human.id(),
        TaskDependenciesGraph.encode(Map.of("eventId", event, "revision", revision)),
        timestamp);
    return new Result(receipt, false);
  }
}

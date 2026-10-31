package com.agentos.taskdependencies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Full scoped graph, with raw edge integrity checks and iterative linear cycle validation. */
public final class TaskDependenciesGraph {
  static final Set<String> STATUSES =
      Set.of(
          "DRAFT",
          "READY",
          "RUNNING",
          "WAITING",
          "BLOCKED",
          "IN_REVIEW",
          "COMPLETED",
          "FAILED",
          "CANCELLED");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SCOPE = " organization_id=? and team_id=? and mission_id=?";
  final Map<UUID, Node> nodes = new TreeMap<>(Comparator.comparing(UUID::toString));
  final Map<UUID, Set<UUID>> edges = new HashMap<>();
  String fingerprint;

  record Node(
      UUID id,
      String title,
      String status,
      String created,
      String updated,
      int edit,
      int owner,
      int dependency,
      List<Object> source) {
    List<Object> tuple() {
      return List.of(id.toString(), title, status, created, updated, edit, owner, dependency);
    }

    TaskDependenciesDtos.Task task() {
      return new TaskDependenciesDtos.Task(id, title, status);
    }
  }

  private TaskDependenciesGraph() {}

  public static void validate(JdbcTemplate jdbc, UUID org, UUID team, UUID mission) {
    try {
      read(jdbc, org, team, mission);
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  static TaskDependenciesGraph read(JdbcTemplate jdbc, UUID org, UUID team, UUID mission) {
    TaskDependenciesGraph graph = new TaskDependenciesGraph();
    // Cursor fetches bound each JDBC batch; the complete map is retained for exact validation.
    jdbc.query(
        connection -> {
          var statement = connection.prepareStatement("select * from tasks where" + SCOPE);
          statement.setFetchSize(512);
          statement.setQueryTimeout(30);
          statement.setObject(1, org);
          statement.setObject(2, team);
          statement.setObject(3, mission);
          return statement;
        },
        (RowCallbackHandler)
            rs -> {
              Node node = node(rs);
              if (graph.nodes.put(node.id(), node) != null) throw unavailable();
              graph.edges.put(node.id(), new HashSet<>());
            });
    // UUID-only EXISTS detects either endpoint in this scope, even when edge scope is corrupt.
    String where =
        " from task_dependencies d where (d.organization_id=? and d.team_id=? and d.mission_id=?)"
            + " or exists (select 1 from tasks t where t.organization_id=? and t.team_id=? and"
            + " t.mission_id=? and (t.id=d.task_id or t.id=d.dependency_task_id))";
    jdbc.query(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "select d.organization_id,d.team_id,d.mission_id,d.task_id,d.dependency_task_id"
                      + where);
          statement.setFetchSize(512);
          statement.setQueryTimeout(30);
          for (int i = 0; i < 2; i++) {
            statement.setObject(i * 3 + 1, org);
            statement.setObject(i * 3 + 2, team);
            statement.setObject(i * 3 + 3, mission);
          }
          return statement;
        },
        (RowCallbackHandler)
            rs -> {
              UUID source = id(rs.getObject(4, UUID.class)),
                  target = id(rs.getObject(5, UUID.class));
              if (!org.equals(rs.getObject(1, UUID.class))
                  || !team.equals(rs.getObject(2, UUID.class))
                  || !mission.equals(rs.getObject(3, UUID.class))
                  || !graph.nodes.containsKey(source)
                  || !graph.nodes.containsKey(target)
                  || source.equals(target)
                  || !graph.edges.get(source).add(target)
                  || graph.edges.get(source).size() > 20) throw unavailable();
            });
    graph.acyclic(false);
    var tuples = graph.nodes.values().stream().map(Node::tuple).toList();
    var edgeTuples = new ArrayList<List<String>>();
    graph
        .nodes
        .keySet()
        .forEach(
            source ->
                graph.edges.get(source).stream()
                    .sorted(Comparator.comparing(UUID::toString))
                    .forEach(
                        target -> edgeTuples.add(List.of(source.toString(), target.toString()))));
    graph.fingerprint =
        hash(
            List.of(
                "TASK_DEPENDENCIES_GRAPH_V1",
                List.of(org.toString(), team.toString(), mission.toString()),
                tuples,
                edgeTuples));
    return graph;
  }

  private static Node node(ResultSet rs) throws SQLException {
    UUID id = id(rs.getObject("id", UUID.class));
    String title = text(rs.getString("title"), 255, true),
        status = text(rs.getString("status"), 50, false);
    String created = time(rs.getObject("created_at", OffsetDateTime.class)),
        updated = time(rs.getObject("updated_at", OffsetDateTime.class));
    int edit = revision(rs, "task_edit_revision"),
        owner = revision(rs, "task_owner_revision"),
        dependency = revision(rs, "dependency_revision");
    UUID rawOwner = rs.getObject("owner_agent_id", UUID.class);
    if (rawOwner != null) id(rawOwner);
    String description = nullablePrivateText(rs.getString("description"), 8000),
        acceptance = nullablePrivateText(rs.getString("acceptance_criteria"), 4000),
        priority = nullableText(rs.getString("priority"), 10);
    if (priority != null && !Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority))
      throw unavailable();
    var waiting = rs.getObject("waiting_since", OffsetDateTime.class);
    return new Node(
        id,
        title,
        status,
        created,
        updated,
        edit,
        owner,
        dependency,
        Arrays.asList(
            title,
            description,
            acceptance,
            priority,
            rawOwner == null ? null : rawOwner.toString(),
            status,
            nullablePrivateText(rs.getString("pending_reason"), Integer.MAX_VALUE),
            waiting == null ? null : time(waiting),
            nullablePrivateText(rs.getString("resolver"), 255),
            created,
            updated,
            edit,
            owner,
            dependency));
  }

  /** Private fingerprint input: preserve nullable legacy multiline text without normalization. */
  private static String nullablePrivateText(String value, int max) {
    if (value == null) return null;
    if (value.codePointCount(0, value.length()) > max) throw unavailable();
    // Match TaskDraftEditWire's nullable history text rules, including TAB, CR and LF.
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c < 32 && c != '\t' && c != '\r' && c != '\n') || c == 127) throw unavailable();
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw unavailable();
      } else if (Character.isLowSurrogate(c)) throw unavailable();
    }
    return value;
  }

  private static String nullableText(String value, int max) {
    return value == null ? null : text(value, max, true);
  }

  private static int revision(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    if (rs.wasNull() || value < 0 || value > Integer.MAX_VALUE) throw unavailable();
    return (int) value;
  }

  static String text(String value, int max, boolean blank) {
    try {
      return TaskDependenciesWire.text(value, max, blank);
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  static UUID id(UUID value) {
    try {
      return TaskDependenciesWire.uuid(value == null ? null : value.toString());
    } catch (RuntimeException ex) {
      throw unavailable();
    }
  }

  static String time(OffsetDateTime value) {
    if (value == null) throw unavailable();
    String result = value.toInstant().toString();
    if (!result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z"))
      throw unavailable();
    return result;
  }

  static TaskDependenciesFailure unavailable() {
    return new TaskDependenciesFailure(500, "TASK_DEPENDENCIES_UNAVAILABLE");
  }

  static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw unavailable();
    }
  }

  static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw unavailable();
    }
  }

  void acyclic(boolean prospective) {
    var incoming = new HashMap<UUID, Integer>();
    nodes.keySet().forEach(id -> incoming.put(id, 0));
    edges
        .values()
        .forEach(
            targets ->
                targets.forEach(target -> incoming.compute(target, (id, count) -> count + 1)));
    var queue = new ArrayDeque<UUID>();
    incoming.forEach(
        (id, count) -> {
          if (count == 0) queue.add(id);
        });
    int count = 0;
    while (!queue.isEmpty()) {
      UUID id = queue.remove();
      count++;
      for (UUID target : edges.get(id))
        if (incoming.compute(target, (key, n) -> n - 1) == 0) queue.add(target);
    }
    if (count != nodes.size()) {
      if (prospective) throw new TaskDependenciesFailure(409, "TASK_DEPENDENCIES_NOT_ELIGIBLE");
      throw unavailable();
    }
  }

  Set<UUID> reaching(UUID source) {
    var reverse = new HashMap<UUID, List<UUID>>();
    nodes.keySet().forEach(id -> reverse.put(id, new ArrayList<>()));
    edges.forEach((id, targets) -> targets.forEach(target -> reverse.get(target).add(id)));
    var found = new HashSet<UUID>();
    var queue = new ArrayDeque<UUID>();
    found.add(source);
    queue.add(source);
    while (!queue.isEmpty())
      for (UUID id : reverse.get(queue.remove())) if (found.add(id)) queue.add(id);
    return found;
  }

  List<TaskDependenciesDtos.Task> selected(UUID source) {
    return tasks(edges.get(source));
  }

  List<TaskDependenciesDtos.Task> tasks(Set<UUID> ids) {
    return ids.stream()
        .sorted(Comparator.comparing(UUID::toString))
        .map(id -> nodes.get(id).task())
        .toList();
  }
}

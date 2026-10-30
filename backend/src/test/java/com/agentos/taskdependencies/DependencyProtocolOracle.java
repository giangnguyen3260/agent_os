package com.agentos.taskdependencies;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Frozen pre-slice tuple encodings, independent of production hash and snapshot helpers. */
final class DependencyProtocolOracle {
  record Scope(UUID organizationId, UUID teamId, UUID missionId, UUID taskId) {}

  static Scope scope(DependencyHttpFixture f) {
    return new Scope(f.org, f.team, f.mission, f.task);
  }

  static String hash(DependencyHttpFixture f, Object tuple) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(f.json.writeValueAsBytes(tuple)));
  }

  static String time(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant().toString();
  }

  static List<Object> legacyTuple(DependencyHttpFixture f, boolean owner) {
    return f.jdbc.queryForObject(
        "select t.*,g.name as team_name,g.status as team_status,g.created_at as"
            + " team_created,g.updated_at as team_updated,m.title as mission_title,m.status as"
            + " mission_status,m.created_at as mission_created,m.updated_at as"
            + " mission_updated,m.task_intake_paused,m.task_intake_version from tasks t join teams"
            + " g on g.id=t.team_id join missions m on m.id=t.mission_id where t.id=?",
        (rs, n) -> {
          var values = f.json.createObjectNode();
          values.put("title", rs.getString("title"));
          values.put("description", rs.getString("description"));
          values.put("acceptanceCriteria", rs.getString("acceptance_criteria"));
          values.put("priority", rs.getString("priority"));
          List<Object> tuple = new ArrayList<>();
          tuple.add(owner ? "TASK_OWNER_SNAPSHOT_V1" : "TASK_DRAFT_EDIT_SNAPSHOT_V2");
          tuple.add(scope(f));
          if (owner) {
            tuple.addAll(
                Arrays.asList(
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("acceptance_criteria"),
                    rs.getString("priority"),
                    null));
          } else tuple.add(values);
          tuple.addAll(
              Arrays.asList(
                  rs.getString("status"),
                  rs.getInt("task_edit_revision"),
                  rs.getInt("task_owner_revision"),
                  time(rs, "updated_at"),
                  rs.getString("owner_agent_id"),
                  time(rs, "created_at"),
                  rs.getString("pending_reason"),
                  rs.getString("waiting_since"),
                  rs.getString("resolver")));
          tuple.add(
              f.jdbc.query(
                  "select dependency_task_id from task_dependencies where task_id=? order by"
                      + " cast(dependency_task_id as varchar)",
                  (r, i) -> r.getString(1),
                  f.task));
          tuple.addAll(
              Arrays.asList(
                  rs.getString("team_name"),
                  rs.getString("team_status"),
                  rs.getString("mission_title"),
                  rs.getString("mission_status"),
                  rs.getBoolean("task_intake_paused"),
                  rs.getLong("task_intake_version")));
          if (owner)
            tuple.addAll(
                List.of(
                    time(rs, "team_updated"),
                    time(rs, "mission_updated"),
                    time(rs, "team_created"),
                    time(rs, "mission_created")));
          return tuple;
        },
        f.task);
  }

  static String instant(Object value) {
    if (value == null) return null;
    return ((OffsetDateTime) value).toInstant().toString();
  }

  static Object identity(Object value) {
    return value == null ? null : value.toString();
  }

  static List<Object> node(java.util.Map<String, Object> row) {
    return List.of(
        identity(row.get("id")),
        row.get("title"),
        row.get("status"),
        instant(row.get("created_at")),
        instant(row.get("updated_at")),
        row.get("task_edit_revision"),
        row.get("task_owner_revision"),
        row.get("dependency_revision"));
  }

  static String graph(DependencyHttpFixture f) throws Exception {
    List<Object> nodes = new ArrayList<>();
    for (var row :
        f.jdbc.queryForList(
            "select * from tasks where organization_id=? and team_id=? and mission_id=? order by"
                + " cast(id as varchar)",
            f.org,
            f.team,
            f.mission)) nodes.add(node(row));
    List<Object> edges = new ArrayList<>();
    for (var row :
        f.jdbc.queryForList(
            "select * from task_dependencies where organization_id=? and team_id=? and mission_id=?"
                + " order by cast(task_id as varchar),cast(dependency_task_id as varchar)",
            f.org,
            f.team,
            f.mission))
      edges.add(List.of(identity(row.get("task_id")), identity(row.get("dependency_task_id"))));
    return hash(
        f,
        List.of(
            "TASK_DEPENDENCIES_GRAPH_V1",
            List.of(f.org.toString(), f.team.toString(), f.mission.toString()),
            nodes,
            edges));
  }

  static String source(DependencyHttpFixture f) throws Exception {
    var t = f.sourceRow();
    var p = f.jdbc.queryForMap("select * from teams where id=?", f.team);
    var m = f.jdbc.queryForMap("select * from missions where id=?", f.mission);
    var source =
        Arrays.asList(
            t.get("title"),
            t.get("description"),
            t.get("acceptance_criteria"),
            t.get("priority"),
            identity(t.get("owner_agent_id")),
            t.get("status"),
            t.get("pending_reason"),
            instant(t.get("waiting_since")),
            t.get("resolver"),
            instant(t.get("created_at")),
            instant(t.get("updated_at")),
            t.get("task_edit_revision"),
            t.get("task_owner_revision"),
            t.get("dependency_revision"));
    var parent =
        List.of(
            p.get("name"),
            p.get("status"),
            instant(p.get("created_at")),
            instant(p.get("updated_at")),
            m.get("title"),
            m.get("status"),
            instant(m.get("created_at")),
            instant(m.get("updated_at")),
            m.get("task_intake_paused"),
            m.get("task_intake_version"));
    return hash(
        f,
        List.of(
            "TASK_DEPENDENCIES_SNAPSHOT_V1",
            List.of(f.org.toString(), f.team.toString(), f.mission.toString(), f.task.toString()),
            source,
            parent,
            graph(f)));
  }

  static String target(DependencyHttpFixture f, UUID target) throws Exception {
    return hash(
        f,
        List.of(
            "TASK_DEPENDENCIES_TARGET_V1",
            List.of(f.org.toString(), f.team.toString(), f.mission.toString(), f.task.toString()),
            graph(f),
            node(f.jdbc.queryForMap("select * from tasks where id=?", target))));
  }
}

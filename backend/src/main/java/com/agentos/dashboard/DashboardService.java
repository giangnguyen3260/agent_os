package com.agentos.dashboard;

import jakarta.persistence.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
  public static final int SIZE = 20;
  // Keep LIMIT/OFFSET within signed integer range on both supported databases.
  public static final int MAX_PAGE = Integer.MAX_VALUE / SIZE;
  private final JdbcTemplate jdbc;

  public DashboardService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Page<T>(List<T> items, int page, int size, long total) {}

  public record Snapshot(
      long running,
      long pending,
      long review,
      long totalTeams,
      long totalAgents,
      long activeAgents) {}

  public record Unavailable(boolean available, String reason) {}

  public record Summary(
      Instant generatedAt,
      Snapshot snapshot,
      Unavailable capacity,
      Unavailable performance,
      Page<Team> teams) {}

  public record Team(
      UUID id,
      String name,
      String status,
      long agentCount,
      long running,
      long pending,
      long review,
      String health,
      String healthReason) {}

  public record Task(
      UUID id,
      String title,
      String status,
      UUID teamId,
      String teamName,
      UUID missionId,
      String pendingReason,
      Instant waitingSince,
      String resolver) {}

  private void validate(int page) {
    if (page < 0 || page > MAX_PAGE)
      throw new IllegalArgumentException("Page must be between 0 and " + MAX_PAGE);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static final String COUNTS =
      """
      count(case when status = 'RUNNING' then 1 end) as running,
      count(case when status in ('WAITING','BLOCKED') then 1 end) as pending,
      count(case when status = 'IN_REVIEW' then 1 end) as review
      """;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Summary summary(UUID org, int page) {
    validate(page);
    // The first database statement establishes the transaction's snapshot time.
    Instant generatedAt =
        jdbc.queryForObject("select current_timestamp", OffsetDateTime.class).toInstant();
    long[] tasks =
        jdbc.queryForObject(
            "select " + COUNTS + " from tasks where organization_id = ?",
            (rs, n) ->
                new long[] {rs.getLong("running"), rs.getLong("pending"), rs.getLong("review")},
            org);
    long totalTeams =
        jdbc.queryForObject(
            "select count(*) from teams where organization_id = ?", Long.class, org);
    long[] agents =
        jdbc.queryForObject(
            "select count(*) as total, count(case when status = 'ACTIVE' then 1 end) as active from"
                + " agents where organization_id = ?",
            (rs, n) -> new long[] {rs.getLong("total"), rs.getLong("active")},
            org);
    var teams =
        jdbc.query(
            """
select t.id,t.name,t.status,coalesce(a.agent_count,0) as agent_count,
  coalesce(a.managers,0) as managers,coalesce(a.degraded,0) as degraded,
  coalesce(k.running,0) as running,coalesce(k.pending,0) as pending,coalesce(k.review,0) as review
from (select id,name,status from teams where organization_id = ? order by name,id limit ? offset ?) t
left join (select team_id,count(*) as agent_count,
  count(case when status = 'ACTIVE' and level = 'MANAGER' then 1 end) as managers,
  count(case when status = 'DEGRADED' then 1 end) as degraded
  from agents where organization_id = ? group by team_id) a on a.team_id = t.id
left join (select team_id,
"""
                + COUNTS
                + """
                    from tasks where organization_id = ? group by team_id) k on k.team_id = t.id
                  order by t.name,t.id
                  """,
            (rs, n) -> {
              String health;
              String reason;
              if (rs.getString("status").equals("ARCHIVED")) {
                health = "ARCHIVED";
                reason = "Team is archived";
              } else if (rs.getLong("managers") == 0) {
                health = "SETUP_REQUIRED";
                reason = "No active manager agent";
              } else if (rs.getLong("pending") > 0 || rs.getLong("degraded") > 0) {
                health = "ATTENTION";
                reason = "Pending tasks or degraded agents require attention";
              } else {
                health = "NORMAL";
                reason = "Active manager present; no pending tasks or degraded agents";
              }
              return new Team(
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getString("status"),
                  rs.getLong("agent_count"),
                  rs.getLong("running"),
                  rs.getLong("pending"),
                  rs.getLong("review"),
                  health,
                  reason);
            },
            org,
            SIZE,
            (long) page * SIZE,
            org,
            org);
    return new Summary(
        generatedAt,
        new Snapshot(tasks[0], tasks[1], tasks[2], totalTeams, agents[0], agents[1]),
        new Unavailable(false, "Resource allocator is not available"),
        new Unavailable(false, "Execution attempts and billing ledger are not available"),
        new Page<>(teams, page, SIZE, totalTeams));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<Task> tasks(UUID org, String state, UUID team, int page) {
    validate(page);
    String predicate =
        switch (state) {
          case "RUNNING" -> "k.status = 'RUNNING'";
          case "PENDING" -> "k.status in ('WAITING','BLOCKED')";
          case "REVIEW" -> "k.status = 'IN_REVIEW'";
          default -> throw new IllegalArgumentException("Unknown task state");
        };
    if (team != null
        && jdbc.queryForObject(
                "select count(*) from teams where organization_id = ? and id = ?",
                Long.class,
                org,
                team)
            == 0) throw new EntityNotFoundException("Team not found");
    List<Object> args = new ArrayList<>();
    args.add(org);
    String where = " where k.organization_id = ? and " + predicate;
    if (team != null) {
      where += " and k.team_id = ?";
      args.add(team);
    }
    long total =
        jdbc.queryForObject("select count(*) from tasks k" + where, Long.class, args.toArray());
    args.add(SIZE);
    args.add((long) page * SIZE);
    var items =
        jdbc.query(
            "select k.*,t.name as team_name from tasks k join teams t on t.id = k.team_id and"
                + " t.organization_id = k.organization_id"
                + where
                + " order by k.created_at,k.id limit ? offset ?",
            (rs, n) ->
                new Task(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("team_name"),
                    rs.getObject("mission_id", UUID.class),
                    rs.getString("pending_reason"),
                    instant(rs, "waiting_since"),
                    rs.getString("resolver")),
            args.toArray());
    return new Page<>(items, page, SIZE, total);
  }
}

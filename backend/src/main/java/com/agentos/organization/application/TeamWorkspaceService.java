package com.agentos.organization.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
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
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TeamWorkspaceService {
  private final JdbcTemplate jdbc;
  private final NewTeamDraftService drafts;

  public TeamWorkspaceService(JdbcTemplate jdbc, NewTeamDraftService drafts) {
    this.jdbc = jdbc;
    this.drafts = drafts;
  }

  public enum Lane {
    INTAKE(List.of("DRAFT")),
    READY(List.of("READY")),
    RUNNING(List.of("RUNNING")),
    REVIEW(List.of("IN_REVIEW")),
    PENDING(List.of("WAITING", "BLOCKED")),
    DONE(List.of("COMPLETED")),
    FAILED(List.of("FAILED")),
    CANCELLED(List.of("CANCELLED"));
    private final List<String> statuses;

    Lane(List<String> statuses) {
      this.statuses = statuses;
    }
  }

  public record Team(
      UUID id,
      UUID organizationId,
      String name,
      String purpose,
      String status,
      UUID managerAgentId,
      String managerName,
      String intake,
      Integer concurrency,
      BigDecimal monthlyBudget,
      String currency) {}

  public record Summary(
      long agentCount, long totalTasks, long runningTasks, long pendingTasks, long reviewTasks) {}

  public record Task(
      UUID id,
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String title,
      String status,
      String pendingReason,
      Instant waitingSince,
      String resolver) {}

  public record LaneItems(Lane lane, long total, List<Task> items) {}

  public record Workspace(Instant generatedAt, Team team, Summary summary, List<LaneItems> lanes) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  public record Activity(UUID id, String eventType, String actorType, Instant createdAt) {}

  private static final String TASK_FROM =
      " from tasks t join missions m on m.id=t.mission_id and m.organization_id=t.organization_id"
          + " and m.team_id=t.team_id where t.organization_id=? and t.team_id=?";
  private static final String ACTIVITY_FROM =
      """
      from audit_events e where e.organization_id=?
      and (e.task_id is not null or e.mission_id is not null)
      and (e.task_id is null or exists (
        select 1 from tasks t join missions tm on tm.id=t.mission_id
          and tm.organization_id=t.organization_id and tm.team_id=t.team_id
        where t.id=e.task_id and t.organization_id=e.organization_id and t.team_id=?))
      and (e.mission_id is null or exists (
        select 1 from missions m where m.id=e.mission_id
          and m.organization_id=e.organization_id and m.team_id=?))
      """;

  private Team team(UUID org, UUID teamId, HumanIdentity human) {
    drafts.authorize(org, human, false, false);
    var rows =
        jdbc.query(
            """
            select t.id,t.organization_id,t.name,t.purpose,t.status,t.manager_agent_id,
              a.name as manager_name,t.intake,t.concurrency,t.monthly_budget,t.currency
            from teams t left join agents a on a.id=t.manager_agent_id
              and a.organization_id=t.organization_id and a.team_id=t.id
            where t.organization_id=? and t.id=?
            """,
            (rs, n) ->
                new Team(
                    rs.getObject("id", UUID.class),
                    org,
                    rs.getString("name"),
                    rs.getString("purpose"),
                    rs.getString("status"),
                    rs.getObject("manager_agent_id", UUID.class),
                    rs.getString("manager_name"),
                    rs.getString("intake"),
                    rs.getObject("concurrency", Integer.class),
                    rs.getBigDecimal("monthly_budget"),
                    rs.getString("currency")),
            org,
            teamId);
    if (rows.isEmpty()) throw new EntityNotFoundException("Team not found");
    return rows.getFirst();
  }

  public Workspace get(UUID org, UUID teamId, HumanIdentity human) {
    Team team = team(org, teamId, human);
    long agents =
        jdbc.queryForObject(
            "select count(*) from agents where organization_id=? and team_id=?",
            Long.class,
            org,
            teamId);
    Summary summary =
        jdbc.queryForObject(
            """
            select count(*) as total,
              count(case when t.status='RUNNING' then 1 end) as running,
              count(case when t.status in ('WAITING','BLOCKED') then 1 end) as pending,
              count(case when t.status='IN_REVIEW' then 1 end) as review
            """
                + TASK_FROM,
            (rs, n) ->
                new Summary(
                    agents,
                    rs.getLong("total"),
                    rs.getLong("running"),
                    rs.getLong("pending"),
                    rs.getLong("review")),
            org,
            teamId);
    List<LaneItems> lanes = new ArrayList<>();
    for (Lane lane : Lane.values()) {
      var page = taskPage(org, teamId, lane, 0, 5);
      lanes.add(new LaneItems(lane, page.total(), page.items()));
    }
    return new Workspace(Instant.now(), team, summary, lanes);
  }

  public Page<Task> tasks(UUID org, UUID teamId, HumanIdentity human, String lane, int page) {
    team(org, teamId, human);
    validatePage(page);
    Lane parsed;
    try {
      parsed = Lane.valueOf(lane);
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new IllegalArgumentException("Invalid lane");
    }
    return taskPage(org, teamId, parsed, page, 20);
  }

  private void validatePage(int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
  }

  private Page<Task> taskPage(UUID org, UUID teamId, Lane lane, int page, int size) {
    String where =
        TASK_FROM
            + " and t.status in ("
            + String.join(",", java.util.Collections.nCopies(lane.statuses.size(), "?"))
            + ")";
    List<Object> args = new ArrayList<>(List.of(org, teamId));
    args.addAll(lane.statuses);
    long total = jdbc.queryForObject("select count(*)" + where, Long.class, args.toArray());
    args.add(size);
    args.add(page * size);
    var items =
        jdbc.query(
            "select"
                + " t.id,t.organization_id,t.team_id,t.mission_id,t.title,t.status,t.pending_reason,t.waiting_since,t.resolver"
                + where
                + " order by t.created_at desc,t.id limit ? offset ?",
            (rs, n) ->
                new Task(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getObject("team_id", UUID.class),
                    rs.getObject("mission_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("pending_reason"),
                    instant(rs, "waiting_since"),
                    rs.getString("resolver")),
            args.toArray());
    return new Page<>(items, page, size, total);
  }

  public Page<Activity> activity(UUID org, UUID teamId, HumanIdentity human, int page) {
    team(org, teamId, human);
    validatePage(page);
    long total =
        jdbc.queryForObject("select count(*) " + ACTIVITY_FROM, Long.class, org, teamId, teamId);
    var items =
        jdbc.query(
            "select e.id,e.event_type,e.actor_type,e.created_at "
                + ACTIVITY_FROM
                + " order by e.created_at desc,e.id limit ? offset ?",
            (rs, n) ->
                new Activity(
                    rs.getObject("id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("actor_type"),
                    instant(rs, "created_at")),
            org,
            teamId,
            teamId,
            20,
            page * 20);
    return new Page<>(items, page, 20, total);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

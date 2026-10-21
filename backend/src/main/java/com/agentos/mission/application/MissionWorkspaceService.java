package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
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
public class MissionWorkspaceService {
  private final JdbcTemplate jdbc;
  private final MissionCreateService authorization;

  public MissionWorkspaceService(JdbcTemplate jdbc, MissionCreateService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  public enum Status {
    ALL,
    PENDING,
    DRAFT,
    READY,
    RUNNING,
    WAITING,
    BLOCKED,
    IN_REVIEW,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  public record Mission(
      UUID id,
      UUID organizationId,
      UUID teamId,
      String teamName,
      String title,
      String status,
      String priority,
      UUID managerAgentId,
      String managerName) {}

  public record Summary(
      long totalTasks,
      long completedTasks,
      long runningTasks,
      long pendingTasks,
      long reviewTasks) {}

  public record Workspace(Instant generatedAt, Mission mission, Summary summary) {}

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

  public record Activity(UUID id, String eventType, String actorType, Instant createdAt) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  private static final String TASK_FROM =
      " from tasks t where t.organization_id=? and t.team_id=? and t.mission_id=?";
  private static final String ACTIVITY_FROM =
      """
      from audit_events e where e.organization_id=?
      and (e.mission_id is not null or e.task_id is not null)
      and (e.mission_id is null or e.mission_id=?)
      and (e.task_id is null or exists (
        select 1 from tasks t where t.id=e.task_id and t.organization_id=e.organization_id
        and t.team_id=? and t.mission_id=?))
      """;

  private Mission mission(UUID org, UUID team, UUID mission, HumanIdentity human) {
    authorization.authorize(org, human, false, false);
    var rows =
        jdbc.query(
            """
        select m.id,m.title,m.status,m.priority,t.name as team_name,
          a.id as manager_id,a.name as manager_name
        from missions m join teams t on t.id=m.team_id and t.organization_id=m.organization_id
        left join agents a on a.id=m.manager_agent_id
          and a.organization_id=m.organization_id and a.team_id=m.team_id
        where m.organization_id=? and m.team_id=? and m.id=?
        """,
            (rs, n) ->
                new Mission(
                    rs.getObject("id", UUID.class),
                    org,
                    team,
                    rs.getString("team_name"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("priority"),
                    rs.getObject("manager_id", UUID.class),
                    rs.getString("manager_name")),
            org,
            team,
            mission);
    if (rows.isEmpty()) throw new EntityNotFoundException("Mission not found");
    return rows.getFirst();
  }

  public Workspace get(UUID org, UUID team, UUID id, HumanIdentity human) {
    Mission mission = mission(org, team, id, human);
    Summary summary =
        jdbc.queryForObject(
            """
        select count(*) as total,
          count(case when t.status='COMPLETED' then 1 end) as completed,
          count(case when t.status='RUNNING' then 1 end) as running,
          count(case when t.status in ('WAITING','BLOCKED') then 1 end) as pending,
          count(case when t.status='IN_REVIEW' then 1 end) as review
        """
                + TASK_FROM,
            (rs, n) ->
                new Summary(
                    rs.getLong("total"),
                    rs.getLong("completed"),
                    rs.getLong("running"),
                    rs.getLong("pending"),
                    rs.getLong("review")),
            org,
            team,
            id);
    return new Workspace(Instant.now(), mission, summary);
  }

  private void validatePage(int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
  }

  public Page<Task> tasks(
      UUID org, UUID team, UUID id, HumanIdentity human, int page, Status status) {
    mission(org, team, id, human);
    validatePage(page);
    if (status == null) throw new IllegalArgumentException("Invalid status");
    String where = TASK_FROM;
    List<Object> args = new ArrayList<>(List.of(org, team, id));
    if (status == Status.PENDING) where += " and t.status in ('WAITING','BLOCKED')";
    else if (status != Status.ALL) {
      where += " and t.status=?";
      args.add(status.name());
    }
    long total = jdbc.queryForObject("select count(*)" + where, Long.class, args.toArray());
    args.add(page * 20);
    var items =
        jdbc.query(
            "select t.id,t.organization_id,t.team_id,t.mission_id,t.title,t.status,"
                + "t.pending_reason,t.waiting_since,t.resolver"
                + where
                + " order by t.created_at desc,t.id limit 20 offset ?",
            (rs, n) -> {
              OffsetDateTime waiting = rs.getObject("waiting_since", OffsetDateTime.class);
              return new Task(
                  rs.getObject("id", UUID.class),
                  org,
                  team,
                  id,
                  rs.getString("title"),
                  rs.getString("status"),
                  rs.getString("pending_reason"),
                  waiting == null ? null : waiting.toInstant(),
                  rs.getString("resolver"));
            },
            args.toArray());
    return new Page<>(items, page, 20, total);
  }

  public Page<Activity> activity(UUID org, UUID team, UUID id, HumanIdentity human, int page) {
    mission(org, team, id, human);
    validatePage(page);
    long total =
        jdbc.queryForObject("select count(*) " + ACTIVITY_FROM, Long.class, org, id, team, id);
    var items =
        jdbc.query(
            "select e.id,e.event_type,e.actor_type,e.created_at "
                + ACTIVITY_FROM
                + " order by e.created_at desc,e.id limit 20 offset ?",
            (rs, n) ->
                new Activity(
                    rs.getObject("id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("actor_type"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()),
            org,
            id,
            team,
            id,
            page * 20);
    return new Page<>(items, page, 20, total);
  }
}

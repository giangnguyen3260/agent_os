package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TaskDetailService {
  private final JdbcTemplate jdbc;
  private final MissionCreateService authorization;

  public TaskDetailService(JdbcTemplate jdbc, MissionCreateService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  public record Task(
      UUID id,
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String title,
      String status,
      String pendingReason,
      Instant waitingSince,
      String resolver,
      Instant createdAt,
      Instant updatedAt) {}

  public record Mission(UUID id, String title) {}

  public record Team(UUID id, String name) {}

  public record Detail(Instant generatedAt, Task task, Mission mission, Team team) {}

  public record Activity(UUID id, String eventType, String actorType, Instant createdAt) {}

  public record Page(List<Activity> items, int page, int size, long total) {}

  private static final String ACTIVITY_FROM =
      """
      from audit_events e where e.organization_id=? and e.task_id=?
      and (e.mission_id is null or e.mission_id=?)
      """;

  public Detail get(UUID org, UUID team, UUID mission, UUID task, HumanIdentity human) {
    authorization.authorize(org, human, false, false);
    var rows =
        jdbc.query(
            """
            select t.id,t.title,t.status,t.pending_reason,t.waiting_since,t.resolver,
              t.created_at,t.updated_at,m.title as mission_title,tm.name as team_name
            from tasks t
            join missions m on m.id=t.mission_id and m.organization_id=t.organization_id
              and m.team_id=t.team_id
            join teams tm on tm.id=m.team_id and tm.organization_id=m.organization_id
            where t.organization_id=? and t.team_id=? and t.mission_id=? and t.id=?
            """,
            (rs, n) -> {
              OffsetDateTime waiting = rs.getObject("waiting_since", OffsetDateTime.class);
              return new Detail(
                  Instant.now(),
                  new Task(
                      rs.getObject("id", UUID.class),
                      org,
                      team,
                      mission,
                      rs.getString("title"),
                      rs.getString("status"),
                      rs.getString("pending_reason"),
                      waiting == null ? null : waiting.toInstant(),
                      rs.getString("resolver"),
                      rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                      rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
                  new Mission(mission, rs.getString("mission_title")),
                  new Team(team, rs.getString("team_name")));
            },
            org,
            team,
            mission,
            task);
    if (rows.isEmpty()) throw new EntityNotFoundException("Task not found");
    return rows.getFirst();
  }

  public Page activity(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity human, int page) {
    get(org, team, mission, task, human);
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    long total =
        jdbc.queryForObject("select count(*) " + ACTIVITY_FROM, Long.class, org, task, mission);
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
            task,
            mission,
            page * 20);
    return new Page(items, page, 20, total);
  }
}

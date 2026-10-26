package com.agentos.trace;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TraceExplorerService {
  private static final String VISIBLE_FROM =
      """
      from audit_events e
      left join tasks t on t.id=e.task_id and t.organization_id=e.organization_id
      join missions m on m.id=coalesce(e.mission_id,t.mission_id)
        and m.organization_id=e.organization_id
      left join teams tm on tm.id=m.team_id and tm.organization_id=e.organization_id
      where e.organization_id=?
        and (m.team_id is null or tm.id is not null)
        and (e.task_id is null or (t.id is not null and t.mission_id=m.id
          and t.team_id=m.team_id and tm.id is not null))
      """;
  private static final String PROJECTION =
      """
      select e.id,e.event_type,e.actor_type,e.created_at,
        tm.id as team_id,tm.name as team_name,m.id as mission_id,m.title as mission_title,
        t.id as task_id,t.title as task_title
      """;
  private final JdbcTemplate jdbc;

  public TraceExplorerService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private void authorize(UUID organizationId, HumanIdentity human) {
    // MissionCreateService.authorize is package-private. Keep its read invariants here
    // until a shared public authorization entry point is available.
    if (human == null) throw new AccessDeniedException("Request denied");
    Long matches =
        jdbc.queryForObject(
            """
            select count(*) from organizations o
            join organization_memberships membership on membership.organization_id=o.id
            join human_accounts account on account.id=membership.account_id
            where o.id=? and account.id=? and account.enabled=true
              and account.credential_version=? and membership.role in ('ADMIN','MEMBER')
            """,
            Long.class,
            organizationId,
            human.id(),
            human.credentialVersion());
    if (matches == null || matches != 1) throw new AccessDeniedException("Request denied");
  }

  public TraceResponse.Page list(UUID org, HumanIdentity human, TraceQuery query) {
    authorize(org, human);
    var arguments = new ArrayList<Object>();
    arguments.add(org);
    arguments.add(query.pattern());
    String source = VISIBLE_FROM + " and lower(e.event_type) like ? escape '!'";
    if (query.missionId() != null) {
      source += " and m.id=?";
      arguments.add(query.missionId());
    }
    if (query.taskId() != null) {
      source += " and t.id=?";
      arguments.add(query.taskId());
    }
    long total = jdbc.queryForObject("select count(*) " + source, Long.class, arguments.toArray());
    arguments.add(query.page() * 20);
    var items =
        jdbc.query(
            PROJECTION + source + " order by e.created_at desc,e.id asc limit 20 offset ?",
            this::event,
            arguments.toArray());
    return new TraceResponse.Page(
        org, Instant.now(), "UNAVAILABLE", items, query.page(), 20, total);
  }

  public TraceResponse.Detail detail(UUID org, HumanIdentity human, UUID eventId) {
    authorize(org, human);
    var events = jdbc.query(PROJECTION + VISIBLE_FROM + " and e.id=?", this::event, org, eventId);
    if (events.isEmpty()) throw new EntityNotFoundException("Event not found");
    return new TraceResponse.Detail(org, Instant.now(), "UNAVAILABLE", events.getFirst());
  }

  private TraceResponse.Event event(ResultSet row, int index) throws SQLException {
    UUID team = row.getObject("team_id", UUID.class);
    UUID task = row.getObject("task_id", UUID.class);
    return new TraceResponse.Event(
        row.getObject("id", UUID.class),
        row.getString("event_type"),
        row.getString("actor_type"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        team == null ? null : new TraceResponse.Team(team, row.getString("team_name")),
        new TraceResponse.Reference(
            row.getObject("mission_id", UUID.class), row.getString("mission_title")),
        task == null ? null : new TraceResponse.Reference(task, row.getString("task_title")));
  }
}

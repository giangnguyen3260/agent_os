package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.api.dto.MissionDirectoryResponse;
import com.agentos.mission.api.dto.MissionDirectoryResponse.Item;
import com.agentos.mission.api.dto.MissionDirectoryResponse.Team;
import com.agentos.mission.domain.MissionStatus;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionDirectoryService {
  private final JdbcTemplate jdbc;
  private final MissionCreateService authorization;

  public MissionDirectoryService(JdbcTemplate jdbc, MissionCreateService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public MissionDirectoryResponse directory(
      UUID org, HumanIdentity human, int page, String query, MissionStatus status, UUID teamId) {
    authorization.authorize(org, human, false, false);
    String search = query == null ? "" : query.strip();
    if (page < 0 || page > 100000 || search.length() > 200) {
      throw new IllegalArgumentException("Invalid page or query");
    }
    Team team = null;
    if (teamId != null) {
      List<Team> teams =
          jdbc.query(
              "select id,name,status from teams where organization_id=? and id=?",
              (rs, n) -> new Team(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
              org,
              teamId);
      if (teams.isEmpty()) throw new EntityNotFoundException("Team not found");
      team = teams.getFirst();
    }
    String pattern =
        "%"
            + search
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    String where = " where m.organization_id=? and lower(m.title) like ? escape '!'";
    List<Object> args = new ArrayList<>(List.of(org, pattern));
    if (status != null) {
      where += " and m.status=?";
      args.add(status.name());
    }
    if (teamId != null) {
      where += " and m.team_id=?";
      args.add(teamId);
    }
    long total =
        jdbc.queryForObject("select count(*) from missions m" + where, Long.class, args.toArray());
    args.add(page * 20);
    // Bound task lookups to the selected page; the task primary key makes counts unique.
    String pageSql =
        "select m.* from missions m" + where + " order by m.created_at desc,m.id limit 20 offset ?";
    List<Item> items =
        jdbc.query(
            """
select m.id,m.organization_id,m.title,m.status,m.priority,m.team_id,t.name as team_name,
       a.id as manager_agent_id,a.name as manager_name,m.created_at,
       (select count(*) from tasks c where c.organization_id=m.organization_id
         and c.team_id=m.team_id and c.mission_id=m.id) as total_tasks,
       (select count(*) from tasks c where c.organization_id=m.organization_id
         and c.team_id=m.team_id and c.mission_id=m.id
         and c.status='COMPLETED') as completed_tasks,
       (select count(*) from tasks c where c.organization_id=m.organization_id
         and c.team_id=m.team_id and c.mission_id=m.id
         and c.status in ('WAITING','BLOCKED')) as pending_tasks
from (
"""
                + pageSql
                + ") m "
                + """
                  left join teams t on t.organization_id=m.organization_id and t.id=m.team_id
                  left join agents a on a.organization_id=m.organization_id and a.team_id=m.team_id
                                       and a.id=m.manager_agent_id
                  """
                + " order by m.created_at desc,m.id",
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("priority"),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("team_name"),
                    rs.getObject("manager_agent_id", UUID.class),
                    rs.getString("manager_name"),
                    rs.getLong("total_tasks"),
                    rs.getLong("completed_tasks"),
                    rs.getLong("pending_tasks"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()),
            args.toArray());
    return new MissionDirectoryResponse(items, page, 20, total, team);
  }
}

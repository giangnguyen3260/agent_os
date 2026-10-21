package com.agentos.organization.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.organization.domain.TeamStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamDirectoryService {
  private final JdbcTemplate jdbc;
  private final NewTeamDraftService drafts;

  public TeamDirectoryService(JdbcTemplate jdbc, NewTeamDraftService drafts) {
    this.jdbc = jdbc;
    this.drafts = drafts;
  }

  public record Item(
      UUID id,
      UUID organizationId,
      String name,
      String purpose,
      String status,
      UUID managerAgentId,
      String managerName,
      long agentCount,
      long totalTasks,
      long runningTasks,
      long pendingTasks,
      long reviewTasks) {}

  public record Page(List<Item> items, int page, int size, long total) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(UUID org, HumanIdentity human, int page, String query, TeamStatus status) {
    drafts.authorize(org, human, false, false);
    if (page < 0 || page > 100000 || (query != null && query.length() > 200))
      throw new IllegalArgumentException("Invalid directory page or query");
    String search = query == null ? "" : query.strip();
    String pattern =
        "%"
            + search
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    String where =
        " where t.organization_id=? and (lower(t.name) like ? escape '!'"
            + " or lower(t.purpose) like ? escape '!')";
    List<Object> args = new ArrayList<>(List.of(org, pattern, pattern));
    if (status != null) {
      where += " and t.status=?";
      args.add(status.name());
    }
    long total =
        jdbc.queryForObject("select count(*) from teams t" + where, Long.class, args.toArray());
    args.add(20);
    args.add(page * 20);
    args.add(org);
    args.add(org);
    var items =
        jdbc.query(
            """
select t.id,t.organization_id,t.name,t.purpose,t.status,t.manager_agent_id,m.name as manager_name,
  coalesce(a.agent_count,0) as agent_count,coalesce(k.total,0) as total_tasks,
  coalesce(k.running,0) as running_tasks,coalesce(k.pending,0) as pending_tasks,
  coalesce(k.review,0) as review_tasks
from (select t.* from teams t
"""
                + where
                + " order by t.created_at desc,t.id limit ? offset ?) t\n"
                + """
left join agents m on m.id=t.manager_agent_id and m.organization_id=t.organization_id and m.team_id=t.id
left join (select team_id,count(*) as agent_count from agents
  where organization_id=? group by team_id) a on a.team_id=t.id
left join (select team_id,count(*) as total,
  count(case when status='RUNNING' then 1 end) as running,
  count(case when status in ('WAITING','BLOCKED') then 1 end) as pending,
  count(case when status='IN_REVIEW' then 1 end) as review
  from tasks where organization_id=? group by team_id) k on k.team_id=t.id
order by t.created_at desc,t.id
""",
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("purpose"),
                    rs.getString("status"),
                    rs.getObject("manager_agent_id", UUID.class),
                    rs.getString("manager_name"),
                    rs.getLong("agent_count"),
                    rs.getLong("total_tasks"),
                    rs.getLong("running_tasks"),
                    rs.getLong("pending_tasks"),
                    rs.getLong("review_tasks")),
            args.toArray());
    return new Page(items, page, 20, total);
  }
}

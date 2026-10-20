package com.agentos.agent.application;

import com.agentos.agent.api.dto.AgentDirectoryResponse;
import com.agentos.agent.api.dto.AgentDirectoryResponse.Item;
import com.agentos.agent.domain.AgentStatus;
import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentDirectoryService {
  private static final int PAGE_SIZE = 20;
  private final AgentDraftService drafts;
  private final JdbcTemplate jdbc;

  public AgentDirectoryService(AgentDraftService drafts, JdbcTemplate jdbc) {
    this.drafts = drafts;
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AgentDirectoryResponse list(
      UUID org, HumanIdentity human, int page, String query, AgentStatus status, UUID teamId) {
    drafts.authorize(org, human, false, false);
    String search = query == null ? "" : query;
    if (page < 0 || page > 100000 || search.length() > 200) {
      throw new IllegalArgumentException("Invalid page or query");
    }
    if (teamId != null
        && jdbc.queryForObject(
                "select count(*) from teams where organization_id=? and id=?",
                Long.class,
                org,
                teamId)
            == 0) {
      throw new EntityNotFoundException("Team not found");
    }
    String pattern =
        "%"
            + search
                .strip()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
            + "%";
    String where =
        " where a.organization_id=? and (lower(a.name) like ? escape '!'"
            + " or lower(a.role) like ? escape '!')";
    var args = new ArrayList<Object>();
    args.add(org);
    args.add(pattern);
    args.add(pattern);
    if (status != null) {
      where += " and a.status=?";
      args.add(status.name());
    }
    if (teamId != null) {
      where += " and a.team_id=?";
      args.add(teamId);
    }
    long total =
        jdbc.queryForObject("select count(*) from agents a" + where, Long.class, args.toArray());
    args.add(PAGE_SIZE);
    args.add(page * PAGE_SIZE);
    var items =
        jdbc.query(
            "select a.id,a.organization_id,a.name,a.role,a.status,a.team_id,t.name as team_name,"
                + "a.configuration_revision,c.concurrency from agents a"
                + " left join teams t on t.id=a.team_id and t.organization_id=a.organization_id"
                + " left join agent_draft_config c on c.agent_id=a.id"
                + where
                + " order by a.created_at desc,a.id limit ? offset ?",
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("role"),
                    AgentStatus.valueOf(rs.getString("status")),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("team_name"),
                    rs.getInt("configuration_revision"),
                    rs.getObject("concurrency", Integer.class)),
            args.toArray());
    return new AgentDirectoryResponse(items, page, PAGE_SIZE, total);
  }
}

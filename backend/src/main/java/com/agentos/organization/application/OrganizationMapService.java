package com.agentos.organization.application;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationMapService {
  private final JdbcTemplate jdbc;
  private final NewTeamDraftService drafts;

  public OrganizationMapService(JdbcTemplate jdbc, NewTeamDraftService drafts) {
    this.jdbc = jdbc;
    this.drafts = drafts;
  }

  public record Team(
      UUID id,
      String name,
      String purpose,
      String status,
      UUID managerAgentId,
      String managerName,
      boolean managerUnavailable,
      long agentCount,
      String intake) {}

  public record Teams(List<Team> items, int page, int size, long total) {}

  public record Overview(
      UUID organizationId,
      String name,
      long teamTotal,
      long agentTotal,
      long unassignedTotal,
      Teams teams) {}

  public record Agent(
      UUID id,
      String name,
      String role,
      String level,
      String status,
      UUID reportsToAgentId,
      String reportsToName,
      boolean reportingUnavailable) {}

  public record Agents(
      UUID organizationId,
      UUID teamId,
      Team team,
      List<Agent> items,
      int page,
      int size,
      long total) {}

  // Only the validated manager identity is projected; raw pointers never enter the DTO.
  private static final String TEAM_SELECT =
      """
      select t.id,t.name,t.purpose,t.status,t.intake,m.id as manager_id,
        m.name as manager_name,
        case when t.manager_agent_id is not null and m.id is null then true else false end
          as manager_unavailable,
        (select count(*) from agents a
          where a.organization_id=t.organization_id and a.team_id=t.id) as agent_count
      from teams t left join agents m on m.id=t.manager_agent_id
        and m.organization_id=t.organization_id and m.team_id=t.id
      """;

  private Team team(ResultSet rs, int row) throws SQLException {
    return new Team(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("purpose"),
        rs.getString("status"),
        rs.getObject("manager_id", UUID.class),
        rs.getString("manager_name"),
        rs.getBoolean("manager_unavailable"),
        rs.getLong("agent_count"),
        rs.getString("intake"));
  }

  private String pattern(int page, String query) {
    String search = query == null ? "" : query.strip();
    if (page < 0 || page > 100000 || search.length() > 200)
      throw new IllegalArgumentException("Invalid map page or query");
    return "%"
        + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
        + "%";
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Overview overview(UUID org, HumanIdentity human, int page, String query, String status) {
    drafts.authorize(org, human, false, false);
    String search = pattern(page, query);
    if (status != null && !List.of("DRAFT", "ACTIVE", "ARCHIVED").contains(status))
      throw new IllegalArgumentException("Invalid map status");
    String where =
        " where t.organization_id=? and (lower(t.name) like ? escape '!'"
            + " or lower(t.purpose) like ? escape '!')";
    List<Object> args = new ArrayList<>(List.of(org, search, search));
    if (status != null) {
      where += " and t.status=?";
      args.add(status);
    }
    long total =
        jdbc.queryForObject("select count(*) from teams t" + where, Long.class, args.toArray());
    args.add(page * 20);
    var items =
        jdbc.query(
            TEAM_SELECT + where + " order by lower(t.name),t.id limit 20 offset ?",
            this::team,
            args.toArray());
    String name =
        jdbc.queryForObject("select name from organizations where id=?", String.class, org);
    long teamTotal =
        jdbc.queryForObject("select count(*) from teams where organization_id=?", Long.class, org);
    long agentTotal =
        jdbc.queryForObject(
            """
            select count(*) from agents a where a.organization_id=? and
              (a.team_id is null or exists (select 1 from teams t
                where t.id=a.team_id and t.organization_id=a.organization_id))
            """,
            Long.class,
            org);
    long unassigned =
        jdbc.queryForObject(
            "select count(*) from agents where organization_id=? and team_id is null",
            Long.class,
            org);
    return new Overview(
        org, name, teamTotal, agentTotal, unassigned, new Teams(items, page, 20, total));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Agents agents(UUID org, HumanIdentity human, UUID teamId, int page, String query) {
    drafts.authorize(org, human, false, false);
    String search = pattern(page, query);
    Team selected = null;
    if (teamId != null) {
      var rows =
          jdbc.query(
              TEAM_SELECT + " where t.organization_id=? and t.id=?", this::team, org, teamId);
      if (rows.isEmpty()) throw new EntityNotFoundException("Team not found");
      selected = rows.getFirst();
    }
    String where =
        " where a.organization_id=? and "
            + (teamId == null ? "a.team_id is null" : "a.team_id=?")
            + " and (lower(a.name) like ? escape '!' or lower(a.role) like ? escape '!')";
    List<Object> args = new ArrayList<>();
    args.add(org);
    if (teamId != null) args.add(teamId);
    args.add(search);
    args.add(search);
    long total =
        jdbc.queryForObject("select count(*) from agents a" + where, Long.class, args.toArray());
    args.add(page * 20);
    var items =
        jdbc.query(
            """
select a.id,a.name,a.role,a.level,a.status,r.id as reporting_id,r.name as reporting_name,
  case when a.reports_to_agent_id is not null and r.id is null then true else false end
    as reporting_unavailable
from agents a left join agents r on r.id=a.reports_to_agent_id and r.id<>a.id
  and r.organization_id=a.organization_id
  and (r.team_id=a.team_id or (r.team_id is null and a.team_id is null))
"""
                + where
                + " order by lower(a.name),a.id limit 20 offset ?",
            (rs, n) ->
                new Agent(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("role"),
                    rs.getString("level"),
                    rs.getString("status"),
                    rs.getObject("reporting_id", UUID.class),
                    rs.getString("reporting_name"),
                    rs.getBoolean("reporting_unavailable")),
            args.toArray());
    return new Agents(org, teamId, selected, items, page, 20, total);
  }
}

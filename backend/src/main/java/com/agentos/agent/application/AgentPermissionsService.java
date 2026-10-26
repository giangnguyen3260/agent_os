package com.agentos.agent.application;

import com.agentos.agent.api.dto.AgentPermissionsResponse;
import com.agentos.agent.api.dto.AgentPermissionsResponse.Request;
import com.agentos.agent.api.dto.AgentPermissionsResponse.Team;
import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentPermissionsService {
  private final AgentDraftService drafts;
  private final JdbcTemplate jdbc;

  public AgentPermissionsService(AgentDraftService drafts, JdbcTemplate jdbc) {
    this.drafts = drafts;
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AgentPermissionsResponse get(UUID org, HumanIdentity human, UUID agent) {
    drafts.authorize(org, human, false, false);
    var rows =
        jdbc.query(
            "select a.id,a.name,a.status,a.configuration_revision,a.team_id,"
                + " t.id as safe_team_id,t.name as team_name,t.status as team_status,"
                + " c.agent_id as configuration_id,c.model,c.tools,c.skills"
                + " from agents a left join agent_draft_config c on c.agent_id=a.id"
                + " left join teams t on t.id=a.team_id and t.organization_id=a.organization_id"
                + " where a.organization_id=? and a.id=?",
            (rs, n) -> {
              UUID safeTeam = rs.getObject("safe_team_id", UUID.class);
              String teamState =
                  rs.getObject("team_id") == null
                      ? "UNASSIGNED"
                      : safeTeam == null ? "UNAVAILABLE" : "ASSIGNED";
              return new AgentPermissionsResponse(
                  org,
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getString("status"),
                  rs.getInt("configuration_revision"),
                  Instant.now(),
                  teamState,
                  safeTeam == null
                      ? null
                      : new Team(safeTeam, rs.getString("team_name"), rs.getString("team_status")),
                  rs.getObject("configuration_id") != null,
                  // No authoritative Agent authorization source or evaluator is integrated.
                  "UNAVAILABLE",
                  List.of(
                      new Request("MODEL", rs.getString("model"), null, null, null),
                      new Request("TOOLS", rs.getString("tools"), null, null, null),
                      new Request("SKILLS", rs.getString("skills"), null, null, null)));
            },
            org,
            agent);
    if (rows.isEmpty()) throw new EntityNotFoundException("Agent not found");
    return rows.getFirst();
  }
}

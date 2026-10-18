package com.agentos.agent.application;

import com.agentos.agent.api.dto.AgentResponse;
import com.agentos.agent.api.dto.CreateAgentRequest;
import com.agentos.agent.domain.AgentLevel;
import com.agentos.agent.domain.AutonomyLevel;
import com.agentos.agent.infrastructure.AgentJpaEntity;
import com.agentos.agent.infrastructure.AgentJpaRepository;
import com.agentos.organization.application.OrganizationService;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

  private final AgentJpaRepository agents;
  private final OrganizationService organizations;

  public AgentService(AgentJpaRepository agents, OrganizationService organizations) {
    this.agents = agents;
    this.organizations = organizations;
  }

  @Transactional
  public AgentResponse create(UUID organizationId, CreateAgentRequest request) {
    organizations.get(organizationId);
    if (request.teamId() != null) {
      organizations.getTeam(organizationId, request.teamId());
    }
    var entity =
        new AgentJpaEntity(
            UUID.randomUUID(),
            organizationId,
            request.teamId(),
            request.name(),
            request.role(),
            request.level() == null ? AgentLevel.MID : request.level(),
            request.autonomyLevel() == null ? AutonomyLevel.SUGGEST : request.autonomyLevel());
    return toResponse(agents.save(entity));
  }

  @Transactional(readOnly = true)
  public List<AgentResponse> list(UUID organizationId) {
    return agents.findByOrganizationId(organizationId).stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public AgentResponse get(UUID organizationId, UUID agentId) {
    var agent =
        agents
            .findById(agentId)
            .filter(item -> item.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> new EntityNotFoundException("Agent not found"));
    return toResponse(agent);
  }

  private AgentResponse toResponse(AgentJpaEntity entity) {
    return new AgentResponse(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getTeamId(),
        entity.getName(),
        entity.getRole(),
        entity.getLevel(),
        entity.getStatus(),
        entity.getAutonomyLevel(),
        entity.getCreatedAt());
  }
}

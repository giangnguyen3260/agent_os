package com.agentos.mission.application;

import com.agentos.mission.api.dto.CreateMissionRequest;
import com.agentos.mission.api.dto.MissionResponse;
import com.agentos.mission.domain.MissionPriority;
import com.agentos.mission.infrastructure.MissionJpaEntity;
import com.agentos.mission.infrastructure.MissionJpaRepository;
import com.agentos.observability.application.AuditService;
import com.agentos.observability.domain.AuditEventType;
import com.agentos.organization.infrastructure.OrganizationJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionService {

  private final MissionJpaRepository missions;
  private final OrganizationJpaRepository organizations;
  private final AuditService auditService;
  private final com.agentos.organization.infrastructure.TeamJpaRepository teams;
  private final com.agentos.agent.infrastructure.AgentJpaRepository agents;

  public MissionService(
      MissionJpaRepository missions,
      OrganizationJpaRepository organizations,
      AuditService auditService,
      com.agentos.organization.infrastructure.TeamJpaRepository teams,
      com.agentos.agent.infrastructure.AgentJpaRepository agents) {
    this.missions = missions;
    this.organizations = organizations;
    this.auditService = auditService;
    this.teams = teams;
    this.agents = agents;
  }

  @Transactional
  public MissionResponse create(UUID organizationId, CreateMissionRequest request) {
    if (!organizations.existsById(organizationId)) {
      throw new EntityNotFoundException("Organization not found");
    }
    if (request.teamId() != null
        && teams
            .findById(request.teamId())
            .filter(team -> team.getOrganizationId().equals(organizationId))
            .isEmpty()) throw new IllegalArgumentException("Team does not belong to organization");
    if (request.managerAgentId() != null
        && agents
            .findById(request.managerAgentId())
            .filter(agent -> agent.getOrganizationId().equals(organizationId))
            .isEmpty())
      throw new IllegalArgumentException("Manager does not belong to organization");
    var entity =
        new MissionJpaEntity(
            UUID.randomUUID(),
            organizationId,
            request.teamId(),
            request.managerAgentId(),
            request.title(),
            request.goal(),
            request.priority() == null ? MissionPriority.NORMAL : request.priority(),
            request.budgetLimit());
    var saved = missions.save(entity);
    auditService.record(
        organizationId,
        saved.getId(),
        AuditEventType.MISSION_CREATED,
        "{\"title\":\"" + saved.getTitle() + "\"}");
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
  public List<MissionResponse> list(UUID organizationId) {
    return missions.findByOrganizationId(organizationId).stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public MissionResponse get(UUID organizationId, UUID missionId) {
    var mission =
        missions
            .findById(missionId)
            .filter(item -> item.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> new EntityNotFoundException("Mission not found"));
    return toResponse(mission);
  }

  @Transactional
  public MissionResponse requestPlanning(UUID organizationId, UUID missionId) {
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "Planning is not implemented");
  }

  private MissionResponse toResponse(MissionJpaEntity entity) {
    return new MissionResponse(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getTeamId(),
        entity.getManagerAgentId(),
        entity.getTitle(),
        entity.getGoal(),
        entity.getStatus(),
        entity.getPriority(),
        entity.getBudgetLimit(),
        entity.getCreatedAt());
  }
}

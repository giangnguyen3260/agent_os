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

  public MissionService(
      MissionJpaRepository missions,
      OrganizationJpaRepository organizations,
      AuditService auditService) {
    this.missions = missions;
    this.organizations = organizations;
    this.auditService = auditService;
  }

  @Transactional
  public MissionResponse create(UUID organizationId, CreateMissionRequest request) {
    if (!organizations.existsById(organizationId)) {
      throw new EntityNotFoundException("Organization not found");
    }
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
    var mission =
        missions
            .findById(missionId)
            .filter(item -> item.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> new EntityNotFoundException("Mission not found"));
    auditService.record(organizationId, missionId, AuditEventType.MISSION_PLANNING_REQUESTED, "{}");
    return toResponse(mission);
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

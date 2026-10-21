package com.agentos.mission.application;

import com.agentos.mission.api.dto.CreateMissionRequest;
import com.agentos.mission.api.dto.MissionResponse;
import com.agentos.mission.infrastructure.MissionJpaEntity;
import com.agentos.mission.infrastructure.MissionJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionService {

  private final MissionJpaRepository missions;

  public MissionService(MissionJpaRepository missions) {
    this.missions = missions;
  }

  @Transactional
  public MissionResponse create(UUID organizationId, CreateMissionRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.GONE, "Use scoped Team mission creation");
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

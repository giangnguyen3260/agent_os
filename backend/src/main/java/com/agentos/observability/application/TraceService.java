package com.agentos.observability.application;

import com.agentos.observability.api.dto.AuditEventResponse;
import com.agentos.observability.infrastructure.AuditEventJpaEntity;
import com.agentos.observability.infrastructure.AuditEventJpaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceService {

  private final AuditEventJpaRepository auditEvents;
  private final com.agentos.mission.infrastructure.MissionJpaRepository missions;

  public TraceService(
      AuditEventJpaRepository auditEvents,
      com.agentos.mission.infrastructure.MissionJpaRepository missions) {
    this.auditEvents = auditEvents;
    this.missions = missions;
  }

  @Transactional(readOnly = true)
  public List<AuditEventResponse> listAuditEvents(UUID organizationId) {
    return auditEvents.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<AuditEventResponse> missionTrace(UUID organizationId, UUID missionId) {
    if (missions
        .findById(missionId)
        .filter(mission -> mission.getOrganizationId().equals(organizationId))
        .isEmpty()) throw new jakarta.persistence.EntityNotFoundException("Mission not found");
    return auditEvents.findByMissionIdOrderByCreatedAtAsc(missionId).stream()
        .filter(event -> event.getOrganizationId().equals(organizationId))
        .map(this::toResponse)
        .toList();
  }

  private AuditEventResponse toResponse(AuditEventJpaEntity entity) {
    return new AuditEventResponse(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getMissionId(),
        entity.getEventType(),
        entity.getPayload(),
        entity.getCreatedAt());
  }
}

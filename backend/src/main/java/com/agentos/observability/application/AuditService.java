package com.agentos.observability.application;

import com.agentos.observability.infrastructure.AuditEventJpaEntity;
import com.agentos.observability.infrastructure.AuditEventJpaRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

  private final AuditEventJpaRepository auditEvents;

  public AuditService(AuditEventJpaRepository auditEvents) {
    this.auditEvents = auditEvents;
  }

  @Transactional
  public void record(UUID organizationId, UUID missionId, String eventType, String payload) {
    auditEvents.save(new AuditEventJpaEntity(organizationId, missionId, eventType, payload));
  }
}

package com.agentos.observability.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventJpaEntity, UUID> {
  List<AuditEventJpaEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

  List<AuditEventJpaEntity> findByMissionIdOrderByCreatedAtAsc(UUID missionId);
}

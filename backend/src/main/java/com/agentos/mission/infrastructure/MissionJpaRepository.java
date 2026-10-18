package com.agentos.mission.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionJpaRepository extends JpaRepository<MissionJpaEntity, UUID> {
  List<MissionJpaEntity> findByOrganizationId(UUID organizationId);
}

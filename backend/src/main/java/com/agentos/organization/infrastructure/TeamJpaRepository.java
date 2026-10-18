package com.agentos.organization.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamJpaRepository extends JpaRepository<TeamJpaEntity, UUID> {
  List<TeamJpaEntity> findByOrganizationId(UUID organizationId);
}

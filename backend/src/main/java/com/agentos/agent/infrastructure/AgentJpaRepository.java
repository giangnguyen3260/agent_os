package com.agentos.agent.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentJpaRepository extends JpaRepository<AgentJpaEntity, UUID> {
  List<AgentJpaEntity> findByOrganizationId(UUID organizationId);
}

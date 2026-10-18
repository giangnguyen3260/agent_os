package com.agentos.organization.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationJpaRepository extends JpaRepository<OrganizationJpaEntity, UUID> {
  boolean existsBySlug(String slug);
}

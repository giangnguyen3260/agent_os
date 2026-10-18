package com.agentos.organization.infrastructure;

import com.agentos.organization.domain.TeamStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class TeamJpaEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String name;

  private String purpose;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TeamStatus status;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected TeamJpaEntity() {}

  public TeamJpaEntity(UUID id, UUID organizationId, String name, String purpose) {
    this.id = id;
    this.organizationId = organizationId;
    this.name = name;
    this.purpose = purpose;
    this.status = TeamStatus.ACTIVE;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public String getPurpose() {
    return purpose;
  }

  public TeamStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

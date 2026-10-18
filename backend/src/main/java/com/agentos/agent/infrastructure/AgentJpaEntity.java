package com.agentos.agent.infrastructure;

import com.agentos.agent.domain.AgentLevel;
import com.agentos.agent.domain.AgentStatus;
import com.agentos.agent.domain.AutonomyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agents")
public class AgentJpaEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  private UUID teamId;

  private UUID reportsToAgentId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AgentLevel level;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AgentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AutonomyLevel autonomyLevel;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected AgentJpaEntity() {}

  public AgentJpaEntity(
      UUID id,
      UUID organizationId,
      UUID teamId,
      String name,
      String role,
      AgentLevel level,
      AutonomyLevel autonomyLevel) {
    this.id = id;
    this.organizationId = organizationId;
    this.teamId = teamId;
    this.name = name;
    this.role = role;
    this.level = level;
    this.autonomyLevel = autonomyLevel;
    this.status = AgentStatus.DRAFT;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public UUID getReportsToAgentId() {
    return reportsToAgentId;
  }

  public String getName() {
    return name;
  }

  public String getRole() {
    return role;
  }

  public AgentLevel getLevel() {
    return level;
  }

  public AgentStatus getStatus() {
    return status;
  }

  public AutonomyLevel getAutonomyLevel() {
    return autonomyLevel;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

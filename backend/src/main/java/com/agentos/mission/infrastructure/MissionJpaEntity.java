package com.agentos.mission.infrastructure;

import com.agentos.mission.domain.MissionPriority;
import com.agentos.mission.domain.MissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "missions")
public class MissionJpaEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  private UUID teamId;

  private UUID createdByUserId;

  private UUID managerAgentId;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, columnDefinition = "text")
  private String goal;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MissionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MissionPriority priority;

  private BigDecimal budgetLimit;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected MissionJpaEntity() {}

  public MissionJpaEntity(
      UUID id,
      UUID organizationId,
      UUID teamId,
      UUID managerAgentId,
      String title,
      String goal,
      MissionPriority priority,
      BigDecimal budgetLimit) {
    this.id = id;
    this.organizationId = organizationId;
    this.teamId = teamId;
    this.managerAgentId = managerAgentId;
    this.title = title;
    this.goal = goal;
    this.priority = priority;
    this.budgetLimit = budgetLimit;
    this.status = MissionStatus.CREATED;
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

  public UUID getManagerAgentId() {
    return managerAgentId;
  }

  public String getTitle() {
    return title;
  }

  public String getGoal() {
    return goal;
  }

  public MissionStatus getStatus() {
    return status;
  }

  public MissionPriority getPriority() {
    return priority;
  }

  public BigDecimal getBudgetLimit() {
    return budgetLimit;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

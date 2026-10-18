package com.agentos.observability.infrastructure;

import com.agentos.common.domain.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEventJpaEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private UUID organizationId;

  private UUID missionId;

  private UUID taskId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ActorType actorType;

  private UUID actorId;

  @Column(nullable = false)
  private String eventType;

  @Column(nullable = false, columnDefinition = "text")
  private String payload;

  @Column(nullable = false)
  private Instant createdAt;

  protected AuditEventJpaEntity() {}

  public AuditEventJpaEntity(
      UUID organizationId, UUID missionId, String eventType, String payload) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.missionId = missionId;
    this.actorType = ActorType.SYSTEM;
    this.eventType = eventType;
    this.payload = payload;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getMissionId() {
    return missionId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

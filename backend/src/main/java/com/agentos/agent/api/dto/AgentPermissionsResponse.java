package com.agentos.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AgentPermissionsResponse(
    UUID organizationId,
    UUID agentId,
    String name,
    String status,
    int configurationRevision,
    Instant checkedAt,
    String teamState,
    Team team,
    boolean configurationPresent,
    String sourceStatus,
    List<Request> requests) {
  public record Team(UUID id, String name, String status) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Request(
      String kind, String configured, String effective, String grantedBy, String scope) {}
}

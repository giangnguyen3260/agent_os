package com.agentos.observability.domain;

public final class AuditEventType {
  public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";
  public static final String TEAM_CREATED = "TEAM_CREATED";
  public static final String AGENT_CREATED = "AGENT_CREATED";
  public static final String MISSION_CREATED = "MISSION_CREATED";
  public static final String MISSION_PLANNING_REQUESTED = "MISSION_PLANNING_REQUESTED";

  private AuditEventType() {}
}

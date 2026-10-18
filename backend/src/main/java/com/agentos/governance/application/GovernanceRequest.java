package com.agentos.governance.application;

import java.util.UUID;

public record GovernanceRequest(
    UUID organizationId, UUID agentId, String actionType, String capability) {}

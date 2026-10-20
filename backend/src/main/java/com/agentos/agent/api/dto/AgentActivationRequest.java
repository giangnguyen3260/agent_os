package com.agentos.agent.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record AgentActivationRequest(
    @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer expectedRevision) {}

package com.agentos.organization.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTeamRequest(@NotBlank String name, String purpose) {}

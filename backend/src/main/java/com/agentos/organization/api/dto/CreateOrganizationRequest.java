package com.agentos.organization.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateOrganizationRequest(@NotBlank String name, String slug, String description) {}

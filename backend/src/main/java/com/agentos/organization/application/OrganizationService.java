package com.agentos.organization.application;

import com.agentos.organization.api.dto.CreateOrganizationRequest;
import com.agentos.organization.api.dto.CreateTeamRequest;
import com.agentos.organization.api.dto.OrganizationResponse;
import com.agentos.organization.api.dto.TeamResponse;
import com.agentos.organization.infrastructure.OrganizationJpaEntity;
import com.agentos.organization.infrastructure.OrganizationJpaRepository;
import com.agentos.organization.infrastructure.TeamJpaEntity;
import com.agentos.organization.infrastructure.TeamJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

  private final OrganizationJpaRepository organizations;
  private final TeamJpaRepository teams;

  public OrganizationService(OrganizationJpaRepository organizations, TeamJpaRepository teams) {
    this.organizations = organizations;
    this.teams = teams;
  }

  @Transactional
  public OrganizationResponse create(CreateOrganizationRequest request) {
    String slug =
        request.slug() == null || request.slug().isBlank()
            ? slugify(request.name())
            : slugify(request.slug());
    if (organizations.existsBySlug(slug)) {
      throw new IllegalArgumentException("Organization slug already exists");
    }
    var saved =
        organizations.save(
            new OrganizationJpaEntity(
                UUID.randomUUID(), request.name(), slug, request.description()));
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
  public List<OrganizationResponse> list() {
    return organizations.findAll().stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public OrganizationResponse get(UUID id) {
    return organizations
        .findById(id)
        .map(this::toResponse)
        .orElseThrow(() -> new EntityNotFoundException("Organization not found"));
  }

  @Transactional
  public TeamResponse createTeam(UUID organizationId, CreateTeamRequest request) {
    if (!organizations.existsById(organizationId)) {
      throw new EntityNotFoundException("Organization not found");
    }
    var saved =
        teams.save(
            new TeamJpaEntity(
                UUID.randomUUID(), organizationId, request.name(), request.purpose()));
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
  public List<TeamResponse> listTeams(UUID organizationId) {
    return teams.findByOrganizationId(organizationId).stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public TeamResponse getTeam(UUID organizationId, UUID teamId) {
    return teams
        .findById(teamId)
        .filter(team -> team.getOrganizationId().equals(organizationId))
        .map(this::toResponse)
        .orElseThrow(() -> new EntityNotFoundException("Team not found"));
  }

  private OrganizationResponse toResponse(OrganizationJpaEntity entity) {
    return new OrganizationResponse(
        entity.getId(),
        entity.getName(),
        entity.getSlug(),
        entity.getDescription(),
        entity.getStatus(),
        entity.getCreatedAt());
  }

  private TeamResponse toResponse(TeamJpaEntity entity) {
    return new TeamResponse(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getName(),
        entity.getPurpose(),
        entity.getStatus(),
        entity.getCreatedAt());
  }

  private String slugify(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
  }
}

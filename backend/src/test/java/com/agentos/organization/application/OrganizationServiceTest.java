package com.agentos.organization.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.agentos.organization.infrastructure.OrganizationJpaRepository;
import com.agentos.organization.infrastructure.TeamJpaEntity;
import com.agentos.organization.infrastructure.TeamJpaRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

  @Mock private OrganizationJpaRepository organizations;
  @Mock private TeamJpaRepository teams;
  @InjectMocks private OrganizationService service;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID teamId = UUID.randomUUID();

  @Test
  void getTeamReturnsTeamFromRequestedOrganization() {
    when(teams.findById(teamId))
        .thenReturn(Optional.of(new TeamJpaEntity(teamId, organizationId, "Team", "Research")));

    var response = service.getTeam(organizationId, teamId);

    assertEquals(teamId, response.id());
    assertEquals(organizationId, response.organizationId());
  }

  @Test
  void getTeamRejectsTeamFromAnotherOrganization() {
    when(teams.findById(teamId))
        .thenReturn(Optional.of(new TeamJpaEntity(teamId, UUID.randomUUID(), "Team", "Research")));

    assertThrows(EntityNotFoundException.class, () -> service.getTeam(organizationId, teamId));
  }

  @Test
  void getTeamRejectsMissingTeam() {
    when(teams.findById(teamId)).thenReturn(Optional.empty());

    assertThrows(EntityNotFoundException.class, () -> service.getTeam(organizationId, teamId));
  }
}

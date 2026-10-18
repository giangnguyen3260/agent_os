package com.agentos.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agentos.agent.api.dto.CreateAgentRequest;
import com.agentos.agent.infrastructure.AgentJpaEntity;
import com.agentos.agent.infrastructure.AgentJpaRepository;
import com.agentos.organization.application.OrganizationService;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

  @Mock private AgentJpaRepository agents;
  @Mock private OrganizationService organizations;
  @InjectMocks private AgentService service;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID teamId = UUID.randomUUID();

  @Test
  void createWithValidTeamSavesAgent() {
    when(agents.save(any(AgentJpaEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.create(organizationId, request(teamId));

    assertEquals(organizationId, response.organizationId());
    assertEquals(teamId, response.teamId());
    verify(organizations).get(organizationId);
    verify(organizations).getTeam(organizationId, teamId);
    verify(agents).save(any(AgentJpaEntity.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong organization", "missing team"})
  void createWithInvalidTeamDoesNotSaveAgent(String reason) {
    when(organizations.getTeam(organizationId, teamId))
        .thenThrow(new EntityNotFoundException(reason));

    assertThrows(
        EntityNotFoundException.class, () -> service.create(organizationId, request(teamId)));

    verifyNoInteractions(agents);
  }

  @Test
  void createWithoutTeamSavesAgent() {
    when(agents.save(any(AgentJpaEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var response = service.create(organizationId, request(null));

    assertNull(response.teamId());
    verify(organizations).get(organizationId);
    verifyNoMoreInteractions(organizations);
    verify(agents).save(any(AgentJpaEntity.class));
  }

  @Test
  void createWithMissingOrganizationDoesNotSaveAgent() {
    when(organizations.get(organizationId))
        .thenThrow(new EntityNotFoundException("Organization not found"));

    assertThrows(
        EntityNotFoundException.class, () -> service.create(organizationId, request(teamId)));

    verifyNoInteractions(agents);
  }

  private CreateAgentRequest request(UUID requestedTeamId) {
    return new CreateAgentRequest(requestedTeamId, "Agent", "Research", null, null);
  }
}

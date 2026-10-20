package com.agentos.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.agent.application.AgentDirectoryService;
import com.agentos.agent.domain.AgentStatus;
import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:agent_directory_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentDirectoryIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentDirectoryService directory;
  @Autowired MockMvc mvc;
  UUID org;
  UUID account;
  UUID team;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    org = UUID.randomUUID();
    account = UUID.randomUUID();
    team = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Directory',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Directory"
            + " tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, org);
    human = new HumanIdentity(account, account + "@example.test", "Directory tester");
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Directory Team','ACTIVE',current_timestamp,current_timestamp)",
        team,
        org);
  }

  UUID insert(String name, String role, String state, UUID assignedTeam) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,?,?,'MID',?,'SUGGEST',TIMESTAMP WITH TIME ZONE '2026-01-01"
            + " 00:00:00+00',current_timestamp)",
        id,
        org,
        assignedTeam,
        name,
        role,
        state);
    return id;
  }

  @Test
  void stablePaginationAndScopedProjectionForMember() {
    for (int i = 0; i < 25; i++) insert("Agent " + i, "Reviewer", "DRAFT", team);
    var first = directory.list(org, human, 0, "", null, null);
    var second = directory.list(org, human, 1, "", null, null);
    assertThat(first.total()).isEqualTo(25);
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(5).doesNotContainAnyElementsOf(first.items());
    var ids =
        java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
            .map(item -> item.id().toString())
            .toList();
    assertThat(ids).isSorted();
    assertThat(first.items())
        .allSatisfy(
            item -> {
              assertThat(item.organizationId()).isEqualTo(org);
              assertThat(item.teamName()).isEqualTo("Directory Team");
              assertThat(item.configurationRevision()).isZero();
              assertThat(item.concurrencyLimit()).isNull();
            });
    assertThat(directory.list(org, human, 2, "", null, null).items()).isEmpty();
  }

  @Test
  void literalSearchAndCombinedStatusTeamFilters() {
    UUID special = insert("100%_ready!\\", "Review Architect", "DRAFT", team);
    insert("100 percent", "Other", "ACTIVE", null);
    for (String value : new String[] {"%", "_", "!", "\\", "  ARCHITECT  "}) {
      assertThat(directory.list(org, human, 0, value, null, null).items())
          .extracting(item -> item.id())
          .containsExactly(special);
    }
    assertThat(directory.list(org, human, 0, "Review", AgentStatus.DRAFT, team).total())
        .isEqualTo(1);
    assertThat(directory.list(org, human, 0, "Review", AgentStatus.ACTIVE, team).total()).isZero();
    assertThat(directory.list(org, human, 0, "' OR 1=1 --", null, null).items()).isEmpty();
  }

  @Test
  void foreignScopeAndFreshAccountMembershipChecks() {
    insert("Private", "Role", "ACTIVE", null);
    UUID foreign = UUID.randomUUID();
    assertThatThrownBy(() -> directory.list(foreign, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> directory.list(org, human, 0, "", null, foreign))
        .isInstanceOf(EntityNotFoundException.class);
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", account);
    assertThatThrownBy(() -> directory.list(org, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set credential_version=0,enabled=false where id=?", account);
    assertThatThrownBy(() -> directory.list(org, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set enabled=true where id=?", account);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    assertThatThrownBy(() -> directory.list(org, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void invalidParametersAndAnonymousRequests() throws Exception {
    var auth =
        authentication(new UsernamePasswordAuthenticationToken(human, null, java.util.List.of()));
    String path = "/api/v1/organizations/" + org + "/agents/directory";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    for (String query :
        new String[] {
          "page=-1",
          "page=100001",
          "page=no",
          "status=READY",
          "teamId=no",
          "query=" + "x".repeat(201)
        }) {
      mvc.perform(get(path + "?" + query).with(auth)).andExpect(status().isBadRequest());
    }
    mvc.perform(get(path).with(auth)).andExpect(status().isOk());
  }
}

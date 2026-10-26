package com.agentos.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.agent.api.dto.AgentPermissionsResponse;
import com.agentos.agent.api.dto.AgentPermissionsResponse.Request;
import com.agentos.agent.application.AgentPermissionsService;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// H2 is source-level integration coverage only; native PostgreSQL acceptance belongs to CI.
// Fixtures obey production membership checks and foreign keys without disabling constraints.
@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:agent_permissions_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentPermissionsIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentPermissionsService permissions;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org;
  UUID agent;
  UUID team;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    org = organization();
    agent =
        UUID.fromString("abcdefab-cdef-4abc-8abc-" + UUID.randomUUID().toString().substring(24));
    team = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Permissions tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
    human = new HumanIdentity(account, account + "@example.test", "Permissions tester");
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at)"
            + " values (?,?,'Assigned team','ARCHIVED',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update(
        "insert into agents(id,organization_id,team_id,name,role,level,status,autonomy_level,"
            + " configuration_revision,created_at,updated_at)"
            + " values (?,?,?,'Actual agent','Role','MID','DRAFT','SUGGEST',7,"
            + " current_timestamp,current_timestamp)",
        agent,
        org,
        team);
    jdbc.update(
        "insert into agent_draft_config(agent_id,responsibilities,expected_input,output_format,"
            + " acceptance_criteria,model,tools,skills) values (?,'PRIVATE"
            + " PROMPT','Input','Output', 'Criteria',?,?,?)",
        agent,
        "  model\n<opaque>  ",
        "read,write\nnot grants",
        "skill:*\n ");
  }

  UUID organization() {
    UUID id =
        UUID.fromString("fedcbafe-dcba-4abc-8abc-" + UUID.randomUUID().toString().substring(24));
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,'Permissions org',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  String path() {
    return "/api/v1/organizations/" + org + "/agents/" + agent + "/permissions";
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  AgentPermissionsResponse read() {
    return permissions.get(org, human, agent);
  }

  JsonNode wire() throws Exception {
    return json.readTree(
            mvc.perform(get(path()).with(auth()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  List<String> keys(JsonNode node) {
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  void unavailable(AgentPermissionsResponse response) {
    assertThat(response.sourceStatus()).isEqualTo("UNAVAILABLE");
    assertThat(response.requests())
        .extracting(Request::kind)
        .containsExactly("MODEL", "TOOLS", "SKILLS");
    for (var request : response.requests()) {
      assertThat(request.effective()).isNull();
      assertThat(request.grantedBy()).isNull();
      assertThat(request.scope()).isNull();
    }
  }

  @Test
  void exactWireOpaqueRequestsExplicitNullsAndObservationTime() throws Exception {
    Instant start = Instant.now();
    var data = wire();
    assertThat(keys(data))
        .containsExactlyInAnyOrder(
            "organizationId",
            "agentId",
            "name",
            "status",
            "configurationRevision",
            "checkedAt",
            "teamState",
            "team",
            "configurationPresent",
            "sourceStatus",
            "requests");
    assertThat(data.get("organizationId").asText()).isEqualTo(org.toString());
    assertThat(data.get("agentId").asText()).isEqualTo(agent.toString());
    assertThat(data.get("name").asText()).isEqualTo("Actual agent");
    assertThat(data.get("status").asText()).isEqualTo("DRAFT");
    assertThat(data.get("configurationRevision").asInt()).isEqualTo(7);
    assertThat(Instant.parse(data.get("checkedAt").asText())).isBetween(start, Instant.now());
    assertThat(data.get("configurationPresent").asBoolean()).isTrue();
    assertThat(data.get("sourceStatus").asText()).isEqualTo("UNAVAILABLE");
    assertThat(data.get("requests").size()).isEqualTo(3);
    for (int i = 0; i < 3; i++) {
      var request = data.get("requests").get(i);
      assertThat(keys(request))
          .containsExactlyInAnyOrder("kind", "configured", "effective", "grantedBy", "scope");
      assertThat(request.get("kind").asText())
          .isEqualTo(List.of("MODEL", "TOOLS", "SKILLS").get(i));
      assertThat(request.get("configured").asText())
          .isEqualTo(List.of("  model\n<opaque>  ", "read,write\nnot grants", "skill:*\n ").get(i));
      for (String key : List.of("effective", "grantedBy", "scope"))
        assertThat(request.get(key).isNull()).isTrue();
    }
    assertThat(data.toString()).doesNotContain("PRIVATE PROMPT");
    assertThat(keys(data.get("team"))).containsExactlyInAnyOrder("id", "name", "status");
    assertThat(data.get("teamState").asText()).isEqualTo("ASSIGNED");
    assertThat(data.get("team").get("id").asText()).isEqualTo(team.toString());
    assertThat(data.get("team").get("name").asText()).isEqualTo("Assigned team");
    assertThat(data.get("team").get("status").asText()).isEqualTo("ARCHIVED");
  }

  @Test
  void missingConfigurationIsNotMaterializedAndRevisionZeroSurvives() throws Exception {
    jdbc.update("delete from agent_draft_config where agent_id=?", agent);
    jdbc.update("update agents set configuration_revision=0 where id=?", agent);
    var before = snapshot();
    var response = read();
    assertThat(response.configurationPresent()).isFalse();
    assertThat(response.configurationRevision()).isZero();
    unavailable(response);
    for (var request : wire().get("requests"))
      assertThat(request.get("configured").isNull()).isTrue();
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void presentNullEmptyAndWhitespaceRemainDistinctAtMaximumRevision() throws Exception {
    jdbc.update(
        "update agent_draft_config set model=null,tools='',skills=? where agent_id=?",
        " \n\t ",
        agent);
    jdbc.update("update agents set configuration_revision=? where id=?", Integer.MAX_VALUE, agent);
    var data = wire();
    assertThat(data.get("configurationPresent").asBoolean()).isTrue();
    assertThat(data.get("configurationRevision").asInt()).isEqualTo(Integer.MAX_VALUE);
    assertThat(data.at("/requests/0/configured").isNull()).isTrue();
    assertThat(data.at("/requests/1/configured").asText()).isEmpty();
    assertThat(data.at("/requests/2/configured").asText()).isEqualTo(" \n\t ");
    unavailable(read());
  }

  @Test
  void everyLifecycleAndCurrentAdminMemberReadsWithoutInferringAuthority() throws Exception {
    for (String role : List.of("ADMIN", "MEMBER", "ADMIN")) {
      jdbc.update(
          "update organization_memberships set role=? where account_id=? and organization_id=?",
          role,
          human.id(),
          org);
      for (String lifecycle : List.of("DRAFT", "ACTIVE", "PAUSED", "ARCHIVED", "LEGACY_UNKNOWN")) {
        jdbc.update("update agents set status=? where id=?", lifecycle, agent);
        assertThat(wire().get("status").asText()).isEqualTo(lifecycle);
        unavailable(read());
      }
    }
  }

  @Test
  void currentMembershipCredentialsAndEnabledStateAreRechecked() throws Exception {
    jdbc.update("update human_accounts set credential_version=1 where id=?", human.id());
    mvc.perform(get(path()).with(auth())).andExpect(status().isUnauthorized());
    assertThatThrownBy(this::read).isInstanceOf(AccessDeniedException.class);
    jdbc.update(
        "update human_accounts set credential_version=0,enabled=false where id=?", human.id());
    mvc.perform(get(path()).with(auth())).andExpect(status().isUnauthorized());
    assertThatThrownBy(this::read).isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set enabled=true where id=?", human.id());
    jdbc.update(
        "delete from organization_memberships where account_id=? and organization_id=?",
        human.id(),
        org);
    mvc.perform(get(path()).with(auth())).andExpect(status().isForbidden());
    assertThatThrownBy(this::read).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void nonmemberScopePrecedesValidationAndAuthorizedForeignOrMissingAgentIs404() throws Exception {
    UUID foreign = organization();
    String foreignPath = path().replace(org.toString(), foreign.toString());
    mvc.perform(
            get(foreignPath.replace(agent.toString(), "invalid"))
                .param("unknown", "1")
                .with(auth()))
        .andExpect(status().isForbidden());
    mvc.perform(get(foreignPath).with(auth())).andExpect(status().isForbidden());
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", human.id(), foreign);
    mvc.perform(get(foreignPath).with(auth())).andExpect(status().isNotFound());
    mvc.perform(get(path().replace(agent.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isNotFound());
  }

  @Test
  void canonicalUuidAndNoQueryParametersOnOwnedPath() throws Exception {
    for (String id :
        List.of(
            "invalid",
            "1-1-1-1-1",
            agent.toString().toUpperCase(),
            "00000000-0000-6000-8000-000000000000",
            "00000000-0000-4000-0000-000000000000")) {
      mvc.perform(get(path().replace(agent.toString(), id)).with(auth()))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get(path().replace(org.toString(), org.toString().toUpperCase())).with(auth()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path()).param("unknown", "1").with(auth())).andExpect(status().isBadRequest());
    mvc.perform(get(path()).param("unknown", "1", "2").with(auth()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void schemaRejectsForeignTeamAssignmentAndUnassignedIsDistinct() throws Exception {
    UUID foreign = organization();
    UUID foreignTeam = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'SECRET FOREIGN TEAM','ACTIVE',current_timestamp,current_timestamp)",
        foreignTeam,
        foreign);
    // V005 prevents foreign/missing assignments in valid PostgreSQL data. Do not bypass it
    // to manufacture legacy corruption; the service also uses a same-organization join.
    assertThatThrownBy(
            () -> jdbc.update("update agents set team_id=? where id=?", foreignTeam, agent))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    var data = wire();
    assertThat(data.get("teamState").asText()).isEqualTo("ASSIGNED");
    assertThat(data.toString()).doesNotContain(foreignTeam.toString(), "SECRET FOREIGN TEAM");
    jdbc.update("update agents set team_id=null where id=?", agent);
    data = wire();
    assertThat(data.get("teamState").asText()).isEqualTo("UNASSIGNED");
    assertThat(data.get("team").isNull()).isTrue();
  }

  @Test
  void humanApprovalGrantIsNotInheritedAndRepeatedRefreshHasNoBusinessWrites() throws Exception {
    jdbc.update(
        "insert into"
            + " approval_grants(id,organization_id,team_id,account_id,action_type,risk_level,expires_at)"
            + " values (?,?,?,?,'PUBLISH_PREVIEW','LOW',?)",
        UUID.randomUUID(),
        org,
        team,
        human.id(),
        java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
    var before = snapshot();
    for (int i = 0; i < 2; i++) {
      wire();
      unavailable(read());
    }
    assertThat(snapshot()).isEqualTo(before);
    jdbc.update("update agents set name='Fresh agent',configuration_revision=8 where id=?", agent);
    jdbc.update("update agent_draft_config set tools='Fresh request' where agent_id=?", agent);
    before = snapshot();
    var data = wire();
    assertThat(data.get("name").asText()).isEqualTo("Fresh agent");
    assertThat(data.get("configurationRevision").asInt()).isEqualTo(8);
    assertThat(data.at("/requests/1/configured").asText()).isEqualTo("Fresh request");
    assertThat(snapshot()).isEqualTo(before);
  }

  Map<String, List<Map<String, Object>>> snapshot() {
    Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
    for (String table :
        List.of(
            "organizations",
            "teams",
            "agents",
            "agent_create_receipts",
            "agent_configuration_versions",
            "agent_configuration_receipts",
            "tasks",
            "audit_events",
            "approval_grants")) {
      String column = table.equals("organizations") ? "id" : "organization_id";
      result.put(
          table, jdbc.queryForList("select * from " + table + " where " + column + "=?", org));
    }
    result.put(
        "agent_draft_config",
        jdbc.queryForList("select * from agent_draft_config where agent_id=?", agent));
    result.put(
        "human_accounts", jdbc.queryForList("select * from human_accounts where id=?", human.id()));
    result.put(
        "organization_memberships",
        jdbc.queryForList("select * from organization_memberships where organization_id=?", org));
    return result;
  }
}

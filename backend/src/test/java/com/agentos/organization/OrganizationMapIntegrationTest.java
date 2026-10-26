package com.agentos.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.organization.application.OrganizationMapService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationMapIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired OrganizationMapService service;
  UUID org, foreign, account, primary, sibling, foreignTeam;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'Map tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, org);
    primary = team(org, "Primary", "ACTIVE");
    sibling = team(org, "Sibling", "DRAFT");
    foreignTeam = team(foreign, "Private team", "ACTIVE");
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,'Organization',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID team(UUID scope, String name, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at)"
            + " values (?,?,?,?,current_timestamp,current_timestamp)",
        id,
        scope,
        name,
        state);
    return id;
  }

  UUID agent(UUID scope, UUID teamId, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,?,'Recorded role','LEGACY_LEVEL','LEGACY_STATUS',"
            + "'SUGGEST',current_timestamp,current_timestamp)",
        id,
        scope,
        teamId,
        name);
    return id;
  }

  void manager(UUID teamId, UUID agentId) {
    jdbc.update("update teams set manager_agent_id=? where id=?", agentId, teamId);
  }

  void reports(UUID agentId, UUID target) {
    jdbc.update("update agents set reports_to_agent_id=? where id=?", target, agentId);
  }

  HumanIdentity human() {
    return new HumanIdentity(account, account + "@example.test", "Tester");
  }

  RequestPostProcessor auth() {
    return SecurityMockMvcRequestPostProcessors.authentication(
        new UsernamePasswordAuthenticationToken(human(), null, List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/organization-map";
  }

  JsonNode read(String suffix) throws Exception {
    return json.readTree(
            mvc.perform(get(url() + suffix).with(auth()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  JsonNode teamRow(UUID id) throws Exception {
    return read("/agents?teamId=" + id).get("team");
  }

  JsonNode row(JsonNode data, UUID id) {
    for (JsonNode item : data.get("items"))
      if (item.get("id").asText().equals(id.toString())) return item;
    throw new AssertionError("Missing expected row");
  }

  void keys(JsonNode node, String... expected) {
    List<String> actual = new ArrayList<>();
    node.fieldNames().forEachRemaining(actual::add);
    assertThat(actual).containsExactlyInAnyOrder(expected);
  }

  @Test
  void scopedCountsExactWireAndIndependentInspector() throws Exception {
    UUID manager = agent(org, primary, "Manager");
    agent(org, primary, "Worker");
    agent(org, sibling, "Sibling worker");
    agent(org, null, "Unassigned");
    UUID secret = agent(foreign, foreignTeam, "Secret");
    manager(primary, manager);
    JsonNode overview = read("");
    keys(overview, "organizationId", "name", "teamTotal", "agentTotal", "unassignedTotal", "teams");
    keys(overview.get("teams"), "items", "page", "size", "total");
    assertThat(overview.get("teamTotal").asInt()).isEqualTo(2);
    assertThat(overview.at("/teams/total").asInt()).isEqualTo(2);
    assertThat(overview.get("agentTotal").asInt()).isEqualTo(4);
    assertThat(overview.get("unassignedTotal").asInt()).isEqualTo(1);
    JsonNode item = teamRow(primary);
    keys(
        item,
        "id",
        "name",
        "purpose",
        "status",
        "managerAgentId",
        "managerName",
        "managerUnavailable",
        "agentCount",
        "intake");
    assertThat(item.get("agentCount").asInt()).isEqualTo(2);
    assertThat(item.get("managerAgentId").asText()).isEqualTo(manager.toString());
    assertThat(item.get("managerName").asText()).isEqualTo("Manager");
    assertThat(item.get("managerUnavailable").asBoolean()).isFalse();
    assertThat(item.get("purpose").isNull()).isTrue();
    assertThat(item.get("intake").isNull()).isTrue();
    assertThat(read("?query=missing").at("/teams/items")).isEmpty();
    assertThat(teamRow(primary)).isEqualTo(item);
    JsonNode noManager = teamRow(sibling);
    assertThat(noManager.get("managerAgentId").isNull()).isTrue();
    assertThat(noManager.get("managerName").isNull()).isTrue();
    assertThat(noManager.get("managerUnavailable").asBoolean()).isFalse();
    String wire = overview.toString() + read("/agents");
    assertThat(wire)
        .doesNotContain(
            secret.toString(),
            foreign.toString(),
            "Secret",
            "password_hash",
            "configuration",
            "prompt");
    // V005 prevents foreign attachments and invalid manager pointers. Keep these
    // constraints intact; the service's defensive joins cannot be exercised with
    // those corrupt rows in the current migrated schema.
    assertThatThrownBy(() -> agent(org, foreignTeam, "Invalid attachment"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> manager(sibling, manager))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> manager(sibling, secret))
        .isInstanceOf(DataIntegrityViolationException.class);
    mvc.perform(get(url() + "/agents").with(auth()).param("teamId", foreignTeam.toString()))
        .andExpect(status().isNotFound());
    mvc.perform(get(url() + "/agents").with(auth()).param("teamId", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void flatReportingCyclesSelfCrossScopeAndUnassigned() throws Exception {
    UUID a = agent(org, primary, "A"), b = agent(org, primary, "B");
    UUID self = agent(org, primary, "Self"), wrongTeam = agent(org, sibling, "Wrong Team");
    UUID secret = agent(foreign, foreignTeam, "Foreign Secret");
    UUID c = agent(org, primary, "C"), d = agent(org, primary, "D");
    UUID u = agent(org, null, "Unassigned A"), v = agent(org, null, "Unassigned B");
    reports(a, b);
    reports(b, a);
    reports(self, self);
    reports(c, wrongTeam);
    reports(d, secret);
    reports(u, v);
    reports(v, a);
    JsonNode data = read("/agents?teamId=" + primary);
    keys(data, "organizationId", "teamId", "team", "items", "page", "size", "total");
    keys(
        row(data, a),
        "id",
        "name",
        "role",
        "level",
        "status",
        "reportsToAgentId",
        "reportsToName",
        "reportingUnavailable");
    assertThat(data.get("total").asInt()).isEqualTo(5);
    assertThat(row(data, a).get("reportsToAgentId").asText()).isEqualTo(b.toString());
    assertThat(row(data, b).get("reportsToName").asText()).isEqualTo("A");
    assertThat(row(data, a).get("reportingUnavailable").asBoolean()).isFalse();
    assertThat(row(data, a).get("level").asText()).isEqualTo("LEGACY_LEVEL");
    assertThat(row(data, a).get("status").asText()).isEqualTo("LEGACY_STATUS");
    for (UUID id : List.of(self, c, d)) {
      JsonNode item = row(data, id);
      assertThat(item.get("reportsToAgentId").isNull()).isTrue();
      assertThat(item.get("reportsToName").isNull()).isTrue();
      assertThat(item.get("reportingUnavailable").asBoolean()).isTrue();
    }
    assertThat(data.toString())
        .doesNotContain(secret.toString(), wrongTeam.toString(), "Foreign Secret");
    JsonNode unassigned = read("/agents");
    assertThat(unassigned.get("teamId").isNull()).isTrue();
    assertThat(unassigned.get("team").isNull()).isTrue();
    assertThat(unassigned.get("total").asInt()).isEqualTo(2);
    assertThat(row(unassigned, u).get("reportsToAgentId").asText()).isEqualTo(v.toString());
    assertThat(row(unassigned, v).get("reportingUnavailable").asBoolean()).isTrue();
    reports(v, null);
    assertThat(row(read("/agents"), v).get("reportingUnavailable").asBoolean()).isFalse();
  }

  @Test
  void stableBoundedPagesLiteralFiltersAndOrganizationTotals() throws Exception {
    for (int i = 0; i < 24; i++) {
      team(org, i % 2 == 0 ? "same" : "SAME", "ACTIVE");
      agent(org, primary, i % 2 == 0 ? "same" : "SAME");
    }
    UUID literal = team(org, "Rate%_!", "ARCHIVED");
    team(org, "RateXYZ", "ARCHIVED");
    jdbc.update("update teams set purpose='MiXeD purpose',intake='MANUAL' where id=?", literal);
    UUID literalAgent = agent(org, primary, "Rate%_!");
    agent(org, primary, "RateXYZ");
    var expectedTeams =
        jdbc.query(
            "select id from teams where organization_id=? order by lower(name),id",
            (rs, n) -> rs.getObject(1, UUID.class).toString(),
            org);
    var expectedAgents =
        jdbc.query(
            "select id from agents where organization_id=? and team_id=? order by lower(name),id",
            (rs, n) -> rs.getObject(1, UUID.class).toString(),
            org,
            primary);
    for (boolean agents : List.of(false, true)) {
      String route = agents ? "/agents?teamId=" + primary + "&" : "?";
      JsonNode first = read(route + "page=0"), second = read(route + "page=1");
      if (!agents) {
        first = first.get("teams");
        second = second.get("teams");
      }
      assertThat(first.get("size").asInt()).isEqualTo(20);
      assertThat(first.get("items").size()).isEqualTo(20);
      assertThat(second.get("page").asInt()).isEqualTo(1);
      List<String> actual = new ArrayList<>();
      first.get("items").forEach(n -> actual.add(n.get("id").asText()));
      second.get("items").forEach(n -> actual.add(n.get("id").asText()));
      assertThat(actual).containsExactlyElementsOf(agents ? expectedAgents : expectedTeams);
      assertThat(second.get("total").asInt()).isEqualTo(actual.size());
      JsonNode empty = read(route + "page=100000");
      assertThat(agents ? empty.get("items") : empty.at("/teams/items")).isEmpty();
    }
    JsonNode filtered =
        json.readTree(
                mvc.perform(
                        get(url()).with(auth()).param("query", " %_! ").param("status", "ARCHIVED"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertThat(filtered.at("/teams/total").asInt()).isEqualTo(1);
    assertThat(filtered.at("/teams/items/0/id").asText()).isEqualTo(literal.toString());
    assertThat(filtered.get("teamTotal").asInt()).isEqualTo(expectedTeams.size());
    assertThat(filtered.get("agentTotal").asInt()).isEqualTo(expectedAgents.size());
    assertThat(read("?query=mixed purpose").at("/teams/total").asInt()).isEqualTo(1);
    assertThat(teamRow(literal).get("intake").asText()).isEqualTo("MANUAL");
    JsonNode agents =
        json.readTree(
                mvc.perform(
                        get(url() + "/agents")
                            .with(auth())
                            .param("teamId", primary.toString())
                            .param("query", " %_! "))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertThat(agents.get("total").asInt()).isEqualTo(1);
    assertThat(agents.at("/items/0/id").asText()).isEqualTo(literalAgent.toString());
    assertThat(read("/agents?teamId=" + primary + "&query=recorded role").get("total").asInt())
        .isEqualTo(expectedAgents.size());
  }

  @Test
  void unassignedPagesEmptyInventoryAndAllLifecycleFilters() throws Exception {
    for (int i = 0; i < 23; i++) agent(org, null, "Same");
    agent(org, primary, "Assigned");
    JsonNode first = read("/agents"), second = read("/agents?page=1");
    assertThat(first.get("total").asInt()).isEqualTo(23);
    assertThat(first.get("items").size()).isEqualTo(20);
    assertThat(second.get("items").size()).isEqualTo(3);
    List<String> actual = new ArrayList<>();
    first.get("items").forEach(n -> actual.add(n.get("id").asText()));
    second.get("items").forEach(n -> actual.add(n.get("id").asText()));
    var expected =
        jdbc.query(
            "select id from agents where organization_id=? and team_id is null order by"
                + " lower(name),id",
            (rs, n) -> rs.getObject(1, UUID.class).toString(),
            org);
    assertThat(actual).containsExactlyElementsOf(expected);
    team(org, "Archived", "ARCHIVED");
    for (String lifecycle : List.of("DRAFT", "ACTIVE", "ARCHIVED")) {
      JsonNode filtered = read("?status=" + lifecycle);
      assertThat(filtered.at("/teams/total").asInt()).isEqualTo(1);
      assertThat(filtered.at("/teams/items/0/status").asText()).isEqualTo(lifecycle);
      assertThat(filtered.get("teamTotal").asInt()).isEqualTo(3);
      assertThat(filtered.get("unassignedTotal").asInt()).isEqualTo(23);
    }
    UUID empty = organization();
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, empty);
    var emptyOverview = service.overview(empty, human(), 0, "", null);
    assertThat(emptyOverview.teamTotal()).isZero();
    assertThat(emptyOverview.agentTotal()).isZero();
    assertThat(emptyOverview.unassignedTotal()).isZero();
    assertThat(emptyOverview.teams().items()).isEmpty();
    assertThat(service.agents(empty, human(), null, 0, "").items()).isEmpty();
  }

  @Test
  void rejectsDuplicateUnknownAndNoncanonicalParameters() throws Exception {
    for (String route : List.of("", "/agents")) {
      for (String page :
          List.of("", "-1", "+1", " 1", "1 ", "1.0", "1e0", "100001", "2147483648", "１"))
        mvc.perform(get(url() + route).with(auth()).param("page", page))
            .andExpect(status().isBadRequest());
      for (String key : List.of("page", "query", route.isEmpty() ? "status" : "teamId"))
        mvc.perform(get(url() + route).with(auth()).param(key, "0", "0"))
            .andExpect(status().isBadRequest());
      mvc.perform(get(url() + route).with(auth()).param("unknown", "x"))
          .andExpect(status().isBadRequest());
      mvc.perform(get(url() + route).with(auth()).param("query", "x".repeat(201)))
          .andExpect(status().isBadRequest());
      mvc.perform(get(url() + route).with(auth()).param("query", "😀".repeat(101)))
          .andExpect(status().isBadRequest());
      mvc.perform(get(url() + route).with(auth()).param("query", " " + "x".repeat(200) + " "))
          .andExpect(status().isOk());
      mvc.perform(get(url() + route).with(auth()).param("page", "000000000001"))
          .andExpect(status().isOk());
    }
    for (String state : List.of("", "active", "UNKNOWN"))
      mvc.perform(get(url()).with(auth()).param("status", state))
          .andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/agents").with(auth()).param("status", "ACTIVE"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url()).with(auth()).param("teamId", primary.toString()))
        .andExpect(status().isBadRequest());
    for (String id :
        List.of(
            "",
            "1-1-1-1-1",
            "ABCDEF00-0000-4000-8000-000000000000",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-6000-8000-000000000000",
            "00000000-0000-4000-0000-000000000000",
            " " + primary))
      mvc.perform(get(url() + "/agents").with(auth()).param("teamId", id))
          .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/organizations/" + "ABCDEF00-0000-4000-8000-000000000000/organization-map")
                .with(auth()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void currentAuthorityAndServiceCredentialChecks() throws Exception {
    for (String role : List.of("MEMBER", "ADMIN")) {
      jdbc.update("update organization_memberships set role=? where account_id=?", role, account);
      read("");
      read("/agents");
    }
    mvc.perform(get(url())).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/organizations/" + foreign + "/organization-map").with(auth()))
        .andExpect(status().isForbidden());
    jdbc.update("update human_accounts set credential_version=1 where id=?", account);
    mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> service.overview(org, human(), 0, "", null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.agents(org, human(), null, 0, ""))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set credential_version=0,enabled=false where id=?", account);
    mvc.perform(get(url() + "/agents").with(auth())).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> service.overview(org, human(), 0, "", null))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set enabled=true where id=?", account);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    for (String route : List.of("", "/agents?teamId=" + foreignTeam))
      mvc.perform(get(url() + route).with(auth())).andExpect(status().isForbidden());
    assertThatThrownBy(() -> service.agents(org, human(), foreignTeam, 0, ""))
        .isInstanceOf(AccessDeniedException.class);
  }

  Map<String, List<Map<String, Object>>> snapshot() {
    Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
    for (String table :
        List.of(
            "organizations",
            "teams",
            "agents",
            "human_accounts",
            "organization_memberships",
            "audit_events",
            "team_create_receipts")) result.put(table, jdbc.queryForList("select * from " + table));
    return result;
  }

  @Test
  void readsDoNotWriteAndDeclareRepeatableRead() throws Exception {
    agent(org, primary, "Worker");
    var before = snapshot();
    read("");
    read("?query=missing&status=ARCHIVED");
    read("/agents");
    read("/agents?teamId=" + primary);
    assertThat(snapshot()).isEqualTo(before);
    for (var method : OrganizationMapService.class.getDeclaredMethods()) {
      if (!List.of("overview", "agents").contains(method.getName())) continue;
      var transaction = method.getAnnotation(Transactional.class);
      assertThat(transaction).isNotNull();
      assertThat(transaction.readOnly()).isTrue();
      assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
  }
}

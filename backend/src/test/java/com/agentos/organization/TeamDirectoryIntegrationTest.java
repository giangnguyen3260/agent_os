package com.agentos.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamDirectoryIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  UUID org, foreign, account, team, mission;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?, 'Dashboard"
            + " tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, org);
    team = team(org, "Primary", "ACTIVE");
    mission = mission(org, team);
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Organization',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID team(UUID organization, String name, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,?,?,current_timestamp,current_timestamp)",
        id,
        organization,
        name,
        status);
    return id;
  }

  UUID mission(UUID organization, UUID teamId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values"
            + " (?,?,?,'Mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        id,
        organization,
        teamId);
    return id;
  }

  UUID task(UUID organization, UUID teamId, UUID missionId, String state) {
    UUID id = UUID.randomUUID();
    boolean pending = state.equals("WAITING") || state.equals("BLOCKED");
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)"
            + " values (?,?,?,?,'Task',?,?,?,?)",
        id,
        organization,
        teamId,
        missionId,
        state,
        pending ? "Needs approval" : null,
        pending ? java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z") : null,
        pending ? "Team manager" : null);
    return id;
  }

  void agent(UUID teamId, String level, String status) {
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,'Agent','Role',?,?,'SUGGEST',current_timestamp,current_timestamp)",
        UUID.randomUUID(),
        org,
        teamId,
        level,
        status);
  }

  RequestPostProcessor auth() {
    return SecurityMockMvcRequestPostProcessors.authentication(
        new UsernamePasswordAuthenticationToken(
            new HumanIdentity(account, account + "@example.test", "Tester"),
            null,
            java.util.List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/teams/directory";
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

  @Test
  void scopedCountsAndManagerDoNotMultiply() throws Exception {
    agent(team, "MANAGER", "ACTIVE");
    agent(team, "MID", "SUSPENDED");
    agent(team, "MID", "DRAFT");
    UUID manager =
        jdbc.queryForObject(
            "select id from agents where team_id=? and level='MANAGER'", UUID.class, team);
    jdbc.update("update teams set manager_agent_id=? where id=?", manager, team);
    for (String state :
        new String[] {
          "RUNNING",
          "RUNNING",
          "WAITING",
          "BLOCKED",
          "IN_REVIEW",
          "DRAFT",
          "READY",
          "COMPLETED",
          "FAILED",
          "CANCELLED"
        }) task(org, team, mission, state);
    UUID other = team(foreign, "Hidden", "ACTIVE");
    task(foreign, other, mission(foreign, other), "RUNNING");
    UUID sibling = team(org, "Sibling", "ACTIVE");
    task(org, sibling, mission(org, sibling), "RUNNING");
    JsonNode data = read("?query=primary");
    assertThat(data.get("total").asInt()).isEqualTo(1);
    JsonNode item = data.at("/items/0");
    assertThat(item.get("organizationId").asText()).isEqualTo(org.toString());
    assertThat(item.get("managerAgentId").asText()).isEqualTo(manager.toString());
    assertThat(item.get("managerName").asText()).isEqualTo("Agent");
    assertThat(item.get("agentCount").asInt()).isEqualTo(3);
    assertThat(item.get("totalTasks").asInt()).isEqualTo(10);
    assertThat(item.get("runningTasks").asInt()).isEqualTo(2);
    assertThat(item.get("pendingTasks").asInt()).isEqualTo(2);
    assertThat(item.get("reviewTasks").asInt()).isEqualTo(1);
  }

  @Test
  void literalSearchPurposeStatusAndLegacyNulls() throws Exception {
    UUID literal = team(org, "Rate%_!", "DRAFT");
    team(org, "RateXYZ", "DRAFT");
    team(org, "Rate%_!", "ARCHIVED");
    JsonNode filtered =
        json.readTree(
                mvc.perform(
                        get(url()).with(auth()).param("query", " %_! ").param("status", "DRAFT"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertThat(filtered.at("/items/0/id").asText()).isEqualTo(literal.toString());
    assertThat(filtered.get("total").asInt()).isEqualTo(1);
    jdbc.update("update teams set purpose='MiXeD Purpose' where id=?", literal);
    assertThat(read("?query=  mixed purpose  ").get("total").asInt()).isEqualTo(1);
    JsonNode legacy = read("?query=PRIMARY").at("/items/0");
    assertThat(legacy.get("purpose").isNull()).isTrue();
    assertThat(legacy.get("managerAgentId").isNull()).isTrue();
    assertThat(legacy.get("managerName").isNull()).isTrue();
    assertThat(legacy.get("totalTasks").asInt()).isZero();
    for (String state : new String[] {"DRAFT", "ACTIVE", "ARCHIVED"})
      assertThat(read("?status=" + state).get("items"))
          .allMatch(n -> n.get("status").asText().equals(state));
  }

  @Test
  void deterministicPagesAndNewestFirst() throws Exception {
    for (int i = 0; i < 24; i++) team(org, "Same", "ACTIVE");
    jdbc.update("update teams set created_at='2026-01-01T00:00:00Z' where organization_id=?", org);
    var expected =
        jdbc.query(
            "select id from teams where organization_id=? order by created_at desc,id",
            (rs, n) -> rs.getObject(1, UUID.class).toString(),
            org);
    JsonNode first = read(""), second = read("?page=1");
    assertThat(first.get("size").asInt()).isEqualTo(20);
    assertThat(second.get("page").asInt()).isEqualTo(1);
    assertThat(second.get("total").asInt()).isEqualTo(25);
    var actual = new java.util.ArrayList<String>();
    first.get("items").forEach(n -> actual.add(n.get("id").asText()));
    second.get("items").forEach(n -> actual.add(n.get("id").asText()));
    assertThat(actual).containsExactlyElementsOf(expected);
    UUID newest = team(org, "Newest", "ACTIVE");
    assertThat(read("").at("/items/0/id").asText()).isEqualTo(newest.toString());
    assertThat(read("?page=100000").get("items")).isEmpty();
  }

  @Test
  void invalidParametersAndLegacyRoute() throws Exception {
    for (String suffix :
        new String[] {
          "?page=-1",
          "?page=100001",
          "?page=2147483648",
          "?page=1.0",
          "?page=bad",
          "?status=UNKNOWN",
          "?status=active",
          "?query=" + "x".repeat(201),
          "?query=" + " ".repeat(400)
        }) mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isBadRequest());
    assertThat(read("?query=" + "x".repeat(200)).get("items")).isEmpty();
    mvc.perform(get("/api/v1/organizations/" + org + "/teams").with(auth()))
        .andExpect(status().isOk());
  }

  @Test
  void freshAuthorizationForAdminMemberAndRevocation() throws Exception {
    read("");
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", account);
    read("");
    mvc.perform(get(url())).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/organizations/" + foreign + "/teams/directory").with(auth()))
        .andExpect(status().isForbidden());
    jdbc.update("update human_accounts set credential_version=1 where id=?", account);
    mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
    jdbc.update("update human_accounts set credential_version=0,enabled=false where id=?", account);
    mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
    jdbc.update("update human_accounts set enabled=true where id=?", account);
    read("");
    jdbc.update("delete from organization_memberships where account_id=?", account);
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
  }
}

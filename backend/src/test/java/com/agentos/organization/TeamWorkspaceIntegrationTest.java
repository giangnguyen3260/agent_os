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
class TeamWorkspaceIntegrationTest {
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
    return "/api/v1/organizations/" + org + "/teams/" + team + "/workspace";
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
  void lanesCountsBoundedPreviewAndTaskPages() throws Exception {
    agent(team, "MANAGER", "ACTIVE");
    agent(team, "MID", "DRAFT");
    for (int i = 0; i < 23; i++) task(org, team, mission, i % 2 == 0 ? "WAITING" : "BLOCKED");
    for (String state :
        new String[] {"DRAFT", "READY", "RUNNING", "IN_REVIEW", "COMPLETED", "FAILED", "CANCELLED"})
      task(org, team, mission, state);
    UUID sibling = team(org, "Sibling", "ACTIVE");
    task(org, sibling, mission(org, sibling), "RUNNING");
    UUID other = team(foreign, "Foreign", "ACTIVE");
    task(foreign, other, mission(foreign, other), "RUNNING");
    jdbc.update("update tasks set created_at='2026-01-01T00:00:00Z' where team_id=?", team);
    JsonNode data = read("");
    java.time.Instant.parse(data.get("generatedAt").asText());
    assertThat(data.at("/summary/agentCount").asInt()).isEqualTo(2);
    assertThat(data.at("/summary/totalTasks").asInt()).isEqualTo(30);
    assertThat(data.at("/summary/runningTasks").asInt()).isEqualTo(1);
    assertThat(data.at("/summary/reviewTasks").asInt()).isEqualTo(1);
    assertThat(data.at("/summary/pendingTasks").asInt()).isEqualTo(23);
    var names = new java.util.ArrayList<String>();
    data.get("lanes").forEach(n -> names.add(n.get("lane").asText()));
    assertThat(names)
        .containsExactly(
            "INTAKE", "READY", "RUNNING", "REVIEW", "PENDING", "DONE", "FAILED", "CANCELLED");
    assertThat(data.at("/lanes/4/total").asInt()).isEqualTo(23);
    assertThat(data.at("/lanes/4/items")).hasSize(5);
    JsonNode first = read("/tasks?lane=PENDING"), second = read("/tasks?lane=PENDING&page=1");
    assertThat(first.get("items")).hasSize(20);
    assertThat(first.get("size").asInt()).isEqualTo(20);
    assertThat(second.get("items")).hasSize(3);
    assertThat(second.get("total").asInt()).isEqualTo(23);
    assertThat(second.get("page").asInt()).isEqualTo(1);
    var ids = new java.util.ArrayList<String>();
    first.get("items").forEach(n -> ids.add(n.get("id").asText()));
    second.get("items").forEach(n -> ids.add(n.get("id").asText()));
    assertThat(ids)
        .containsExactlyElementsOf(
            jdbc.query(
                "select id from tasks where team_id=? and status in ('WAITING','BLOCKED') order by"
                    + " created_at desc,id",
                (rs, n) -> rs.getString(1),
                team));
    assertThat(first.at("/items/0/organizationId").asText()).isEqualTo(org.toString());
    assertThat(first.at("/items/0/pendingReason").asText()).isEqualTo("Needs approval");
    assertThat(read("/tasks?lane=PENDING&page=100000").get("items")).isEmpty();
    UUID newest = task(org, team, mission, "WAITING");
    assertThat(read("/tasks?lane=PENDING").at("/items/0/id").asText()).isEqualTo(newest.toString());
    for (int i = 0; i < 8; i++)
      if (i != 4) assertThat(data.at("/lanes/" + i + "/total").asInt()).isEqualTo(1);
  }

  @Test
  void legacyConfigAndScopedManager() throws Exception {
    JsonNode t = read("").get("team");
    for (String field :
        new String[] {
          "purpose",
          "managerAgentId",
          "managerName",
          "intake",
          "concurrency",
          "monthlyBudget",
          "currency"
        }) assertThat(t.get(field).isNull()).isTrue();
    assertThat(read("").at("/summary/totalTasks").asInt()).isZero();
    UUID other = team(org, "Other", "ACTIVE");
    agent(other, "MANAGER", "ACTIVE");
    UUID manager = jdbc.queryForObject("select id from agents where team_id=?", UUID.class, other);
    assertThat(read("").at("/team/managerName").isNull()).isTrue();
    jdbc.update("update agents set team_id=? where id=?", team, manager);
    jdbc.update("update teams set manager_agent_id=? where id=?", manager, team);
    assertThat(read("").at("/team/managerName").asText()).isEqualTo("Agent");
    assertThat(read("").at("/summary/agentCount").asInt()).isEqualTo(1);
  }

  @Test
  void strictInputs() throws Exception {
    for (String suffix :
        new String[] {
          "/tasks",
          "/tasks?lane=",
          "/tasks?lane=pending",
          "/tasks?lane=WAITING",
          "/tasks?lane=UNKNOWN",
          "/tasks?lane=PENDING&page=-1",
          "/tasks?lane=PENDING&page=100001",
          "/tasks?lane=PENDING&page=1.0",
          "/tasks?lane=PENDING&page=2147483648",
          "/activity?page=-1",
          "/activity?page=100001",
          "/activity?page=no"
        }) mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isBadRequest());
    assertThat(read("/activity?page=100000").get("items")).isEmpty();
  }

  @Test
  void authorizationAndTeamScopingForEveryEndpoint() throws Exception {
    UUID other = team(foreign, "Hidden", "ACTIVE");
    for (String suffix : new String[] {"", "/tasks?lane=READY", "/activity"}) {
      read(suffix);
      mvc.perform(get(url() + suffix)).andExpect(status().isUnauthorized());
      mvc.perform(
              get("/api/v1/organizations/" + foreign + "/teams/" + other + "/workspace" + suffix)
                  .with(auth()))
          .andExpect(status().isForbidden());
      for (UUID missing : java.util.List.of(other, UUID.randomUUID()))
        mvc.perform(
                get("/api/v1/organizations/" + org + "/teams/" + missing + "/workspace" + suffix)
                    .with(auth()))
            .andExpect(status().isNotFound());
      jdbc.update("update human_accounts set credential_version=1 where id=?", account);
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isUnauthorized());
      jdbc.update(
          "update human_accounts set credential_version=0,enabled=false where id=?", account);
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isUnauthorized());
      jdbc.update("update human_accounts set enabled=true where id=?", account);
      jdbc.update("delete from organization_memberships where account_id=?", account);
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isForbidden());
      jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
      read(suffix);
    }
  }

  void event(UUID organization, UUID taskId, UUID missionId) {
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,task_id,mission_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values"
            + " (?,?,?,?,'HUMAN',?,'TASK_UPDATED','secret-token-password',current_timestamp)",
        UUID.randomUUID(),
        organization,
        taskId,
        missionId,
        account);
  }

  @Test
  void activityRequiresEveryReferenceAndExposesOnlySafeProjection() throws Exception {
    UUID localTask = task(org, team, mission, "READY");
    UUID sibling = team(org, "Sibling", "ACTIVE"), siblingMission = mission(org, sibling);
    UUID siblingTask = task(org, sibling, siblingMission, "READY");
    UUID foreignTeam = team(foreign, "Foreign", "ACTIVE"),
        foreignMission = mission(foreign, foreignTeam);
    UUID foreignTask = task(foreign, foreignTeam, foreignMission, "READY");
    for (int i = 0; i < 21; i++) event(org, localTask, null);
    event(org, null, mission);
    event(org, localTask, mission);
    event(org, null, null);
    event(org, UUID.randomUUID(), null);
    event(org, localTask, siblingMission);
    event(org, siblingTask, mission);
    event(org, localTask, foreignMission);
    event(org, foreignTask, mission);
    event(org, null, foreignMission);
    event(foreign, localTask, mission);
    event(org, siblingTask, siblingMission);
    jdbc.update(
        "update audit_events set created_at='2026-01-01T00:00:00Z' where organization_id=?", org);
    JsonNode first = read("/activity"), second = read("/activity?page=1");
    assertThat(first.get("total").asInt()).isEqualTo(23);
    assertThat(first.get("items")).hasSize(20);
    assertThat(second.get("items")).hasSize(3);
    var ids = new java.util.ArrayList<String>();
    for (JsonNode page : java.util.List.of(first, second))
      for (JsonNode item : page.get("items")) {
        var fields = new java.util.ArrayList<String>();
        item.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "eventType", "actorType", "createdAt");
        java.time.Instant.parse(item.get("createdAt").asText());
        ids.add(item.get("id").asText());
      }
    assertThat(ids).doesNotHaveDuplicates();
    assertThat(ids)
        .containsExactlyElementsOf(
            jdbc.query(
                "select id from audit_events where organization_id=? and (task_id=? or (task_id is"
                    + " null and mission_id=?)) and (mission_id is null or mission_id=?) order by"
                    + " created_at desc,id",
                (rs, n) -> rs.getString(1),
                org,
                localTask,
                mission,
                mission));
    assertThat(first.toString())
        .doesNotContain("secret-token-password", account.toString(), "payload", "actorId");
  }
}

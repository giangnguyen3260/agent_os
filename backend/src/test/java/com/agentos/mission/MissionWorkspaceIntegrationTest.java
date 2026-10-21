package com.agentos.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.application.MissionWorkspaceService;
import com.agentos.mission.application.MissionWorkspaceService.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionWorkspaceIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MissionWorkspaceService service;
  UUID org, foreign, team, account, id;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org, "Primary", "ACTIVE");
    id = mission(org, team, "Visible", "RUNNING");
    account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, org);
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID team(UUID organization, String name, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,?,?,current_timestamp,current_timestamp)",
        id,
        organization,
        name,
        state);
    return id;
  }

  UUID mission(UUID organization, UUID assigned, String title, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values (?,?,?,?,'Private goal',?,'NORMAL',current_timestamp,current_timestamp)",
        id,
        organization,
        assigned,
        title,
        state);
    return id;
  }

  UUID manager(UUID organization, UUID assigned) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,'Private"
            + " manager','Role','MANAGER','ACTIVE','SUGGEST',current_timestamp,current_timestamp)",
        id,
        organization,
        assigned);
    return id;
  }

  UUID task(UUID organization, UUID assigned, UUID mission, String state) {
    boolean pending = List.of("WAITING", "BLOCKED").contains(state);
    UUID taskId = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)"
            + " values (?,?,?,?,'Task',?,?,?,?)",
        taskId,
        organization,
        assigned,
        mission,
        state,
        pending ? "Approval" : null,
        pending ? java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z") : null,
        pending ? "Manager" : null);
    return taskId;
  }

  String url() {
    return "/api/v1/organizations/" + org + "/teams/" + team + "/missions/" + id + "/workspace";
  }

  RequestPostProcessor auth() {
    return authentication(
        new UsernamePasswordAuthenticationToken(
            new HumanIdentity(account, account + "@example.test", "Tester"), null, List.of()));
  }

  JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    return json.readTree(
            mvc.perform(request.with(auth()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  List<List<java.util.Map<String, Object>>> snapshot() {
    return List.of(
            "missions", "tasks", "agents", "teams", "audit_events", "mission_create_receipts")
        .stream()
        .map(
            table ->
                jdbc.queryForList(
                    "select * from " + table + " where organization_id=? order by 1", org))
        .toList();
  }

  void keys(JsonNode node, String... expected) {
    var fields = new ArrayList<String>();
    node.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder(expected);
  }

  @Test
  void realCountersExactProjectionAndNoBusinessWrites() throws Exception {
    UUID manager = manager(org, team);
    jdbc.update("update missions set manager_agent_id=? where id=?", manager, id);
    for (String state :
        List.of(
            "DRAFT",
            "READY",
            "RUNNING",
            "WAITING",
            "BLOCKED",
            "IN_REVIEW",
            "COMPLETED",
            "COMPLETED",
            "FAILED",
            "CANCELLED")) task(org, team, id, state);
    task(org, team, mission(org, team, "Other", "READY"), "COMPLETED");
    var before = snapshot();
    JsonNode root = read(get(url()));
    keys(root, "generatedAt", "mission", "summary");
    java.time.Instant.parse(root.get("generatedAt").asText());
    keys(
        root.get("mission"),
        "id",
        "organizationId",
        "teamId",
        "teamName",
        "title",
        "status",
        "priority",
        "managerAgentId",
        "managerName");
    assertThat(root.at("/mission/managerAgentId").asText()).isEqualTo(manager.toString());
    assertThat(root.at("/mission/teamName").asText()).isEqualTo("Primary");
    assertThat(root.at("/mission/title").asText()).isEqualTo("Visible");
    assertThat(root.at("/mission/status").asText()).isEqualTo("RUNNING");
    assertThat(root.get("summary"))
        .isEqualTo(
            json.readTree(
                "{\"totalTasks\":10,\"completedTasks\":2,\"runningTasks\":1,\"pendingTasks\":2,\"reviewTasks\":1}"));
    JsonNode tasks = read(get(url() + "/tasks"));
    keys(tasks, "items", "page", "size", "total");
    for (JsonNode task : tasks.get("items")) {
      keys(
          task,
          "id",
          "organizationId",
          "teamId",
          "missionId",
          "title",
          "status",
          "pendingReason",
          "waitingSince",
          "resolver");
      assertThat(task.get("organizationId").asText()).isEqualTo(org.toString());
      assertThat(task.get("teamId").asText()).isEqualTo(team.toString());
      assertThat(task.get("missionId").asText()).isEqualTo(id.toString());
      boolean pending = List.of("WAITING", "BLOCKED").contains(task.get("status").asText());
      for (String field : List.of("pendingReason", "waitingSince", "resolver"))
        assertThat(task.get(field).isNull()).isEqualTo(!pending);
    }
    read(get(url() + "/activity"));
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void everyStatusPendingAndStableTaskPaging() throws Exception {
    for (Status state : Status.values()) {
      if (state == Status.ALL || state == Status.PENDING) continue;
      task(org, team, id, state.name());
      JsonNode filtered = read(get(url() + "/tasks").param("status", state.name()));
      assertThat(filtered.get("total").asInt()).isEqualTo(1);
      assertThat(filtered.at("/items/0/status").asText()).isEqualTo(state.name());
    }
    assertThat(read(get(url() + "/tasks").param("status", "PENDING")).get("total").asInt())
        .isEqualTo(2);
    for (int i = 0; i < 16; i++) task(org, team, id, "READY");
    jdbc.update("update tasks set created_at='2026-01-01T00:00:00Z' where mission_id=?", id);
    var expected =
        jdbc.query(
            "select id from tasks where mission_id=? order by created_at desc,id",
            (rs, n) -> rs.getObject(1, UUID.class).toString(),
            id);
    var actual = new ArrayList<String>();
    for (int page = 0; page < 2; page++) {
      JsonNode data = read(get(url() + "/tasks").param("page", "" + page).param("size", "100"));
      assertThat(data.get("size").asInt()).isEqualTo(20);
      assertThat(data.get("page").asInt()).isEqualTo(page);
      assertThat(data.get("total").asInt()).isEqualTo(25);
      assertThat(data.get("items")).hasSize(page == 0 ? 20 : 5);
      data.get("items").forEach(n -> actual.add(n.get("id").asText()));
    }
    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(read(get(url() + "/tasks").param("page", "100000")).get("items")).isEmpty();
  }

  UUID event(UUID organization, UUID mission, UUID task) {
    UUID event = UUID.randomUUID();
    jdbc.update(
        "insert into audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at) values (?,?,?,?,'HUMAN',?,'SAFE_TYPE','private payload','2026-01-01T00:00:00Z')",
        event,
        organization,
        mission,
        task,
        account);
    return event;
  }

  @Test
  void activityRejectsEveryMixedReferenceAndPagesOnlySafeMetadata() throws Exception {
    UUID valid = task(org, team, id, "READY");
    UUID otherMission = mission(org, team, "Same team other mission", "READY");
    UUID otherTask = task(org, team, otherMission, "READY");
    UUID sibling = team(org, "Sibling", "ACTIVE");
    UUID siblingMission = mission(org, sibling, "Sibling", "READY");
    UUID siblingTask = task(org, sibling, siblingMission, "READY");
    UUID foreignTeam = team(foreign, "Foreign", "ACTIVE");
    UUID foreignMission = mission(foreign, foreignTeam, "Foreign", "READY");
    UUID foreignTask = task(foreign, foreignTeam, foreignMission, "READY");
    event(org, id, otherTask);
    event(org, id, siblingTask);
    event(org, id, foreignTask);
    event(org, id, UUID.randomUUID());
    event(org, otherMission, valid);
    event(org, siblingMission, valid);
    event(org, foreignMission, valid);
    event(org, null, null);
    event(foreign, id, valid);
    var safe = new ArrayList<String>();
    for (int i = 0; i < 25; i++)
      safe.add(event(org, i % 3 == 0 ? null : id, i % 3 == 1 ? null : valid).toString());
    var expected =
        jdbc
            .query(
                "select id from audit_events where organization_id=? order by created_at desc,id",
                (rs, n) -> rs.getObject(1, UUID.class).toString(),
                org)
            .stream()
            .filter(safe::contains)
            .toList();
    var actual = new ArrayList<String>();
    var before = snapshot();
    for (int page = 0; page < 2; page++) {
      JsonNode data = read(get(url() + "/activity").param("page", "" + page));
      assertThat(data.get("total").asInt()).isEqualTo(25);
      assertThat(data.get("items")).hasSize(page == 0 ? 20 : 5);
      for (JsonNode item : data.get("items")) {
        keys(item, "id", "eventType", "actorType", "createdAt");
        assertThat(item.get("eventType").asText()).isEqualTo("SAFE_TYPE");
        assertThat(item.get("actorType").asText()).isEqualTo("HUMAN");
        java.time.Instant.parse(item.get("createdAt").asText());
        actual.add(item.get("id").asText());
      }
    }
    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(read(get(url() + "/activity").param("page", "100000")).get("items")).isEmpty();
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void emptyLegacyAndMovedManagersStayScoped() throws Exception {
    UUID sibling = team(org, "Sibling", "ACTIVE");
    UUID foreignTeam = team(foreign, "Foreign", "ACTIVE");
    for (UUID manager :
        List.of(manager(org, sibling), manager(foreign, foreignTeam), manager(org, null))) {
      jdbc.update("update missions set manager_agent_id=? where id=?", manager, id);
      JsonNode root = read(get(url()));
      assertThat(root.at("/mission/managerAgentId").isNull()).isTrue();
      assertThat(root.at("/mission/managerName").isNull()).isTrue();
      root.get("summary").forEach(n -> assertThat(n.asLong()).isZero());
    }
    UUID moved = manager(org, team);
    jdbc.update("update missions set manager_agent_id=? where id=?", moved, id);
    read(get(url()));
    jdbc.update("update agents set team_id=? where id=?", sibling, moved);
    assertThat(read(get(url())).at("/mission/managerAgentId").isNull()).isTrue();
    assertThat(read(get(url() + "/tasks")).get("items")).isEmpty();
    assertThat(read(get(url() + "/activity")).get("total").asInt()).isZero();
    UUID legacy = mission(org, null, "Legacy", "CREATED");
    for (String suffix : List.of("", "/tasks", "/activity")) {
      for (UUID invalid :
          List.of(
              legacy,
              UUID.randomUUID(),
              mission(org, sibling, "Other", "READY"),
              mission(foreign, foreignTeam, "Foreign", "READY")))
        mvc.perform(get(url().replace(id.toString(), invalid.toString()) + suffix).with(auth()))
            .andExpect(status().isNotFound());
      for (UUID invalid : List.of(sibling, foreignTeam, UUID.randomUUID()))
        mvc.perform(get(url().replace(team.toString(), invalid.toString()) + suffix).with(auth()))
            .andExpect(status().isNotFound());
      mvc.perform(get(url().replace(org.toString(), foreign.toString()) + suffix).with(auth()))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void invalidBoundsEnumsAndMalformedParametersReturn400() throws Exception {
    for (String suffix : List.of("/tasks", "/activity"))
      for (String page : List.of("-1", "100001", "2147483648", "1.0", "bad"))
        mvc.perform(get(url() + suffix).param("page", page).with(auth()))
            .andExpect(status().isBadRequest());
    for (String state : List.of("UNKNOWN", "running", "ALL OR 1=1"))
      mvc.perform(get(url() + "/tasks").param("status", state).with(auth()))
          .andExpect(status().isBadRequest());
    mvc.perform(get(url().replace(id.toString(), "bad")).with(auth()))
        .andExpect(status().isBadRequest());
  }

  void allServices(HumanIdentity human) {
    service.get(org, team, id, human);
    service.tasks(org, team, id, human, 0, Status.ALL);
    service.activity(org, team, id, human, 0);
  }

  void deniedServices(HumanIdentity human) {
    assertThatThrownBy(() -> service.get(org, team, id, human))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.tasks(org, team, id, human, 0, Status.ALL))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.activity(org, team, id, human, 0))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void everyEndpointAndDirectServiceReauthorizeFreshIdentityAndMembership() throws Exception {
    HumanIdentity human = new HumanIdentity(account, account + "@example.test", "Tester");
    allServices(human);
    deniedServices(null);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", account);
    allServices(human);
    for (String suffix : List.of("", "/tasks", "/activity")) {
      read(get(url() + suffix));
      mvc.perform(get(url() + suffix)).andExpect(status().isUnauthorized());
    }
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", account);
      deniedServices(human);
      for (String suffix : List.of("", "/tasks", "/activity"))
        mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isUnauthorized());
    }
    jdbc.update("update human_accounts set enabled=true,credential_version=0 where id=?", account);
    allServices(human);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    deniedServices(human);
    for (String suffix : List.of("", "/tasks", "/activity"))
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isForbidden());
  }
}

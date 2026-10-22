package com.agentos.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.application.TaskDetailService;
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
class TaskDetailIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TaskDetailService service;
  UUID org, foreign, team, account, id, taskId;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org, "Primary", "ACTIVE");
    id = mission(org, team, "Visible", "RUNNING");
    taskId = task(org, team, id, "WAITING");
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
    return "/api/v1/organizations/"
        + org
        + "/teams/"
        + team
        + "/missions/"
        + id
        + "/tasks/"
        + taskId;
  }

  RequestPostProcessor auth() {
    return authentication(
        new UsernamePasswordAuthenticationToken(
            new HumanIdentity(account, account + "@example.test", "Tester"), null, List.of()));
  }

  JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    JsonNode envelope =
        json.readTree(
            mvc.perform(request.with(auth()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    keys(envelope, "data", "meta");
    UUID.fromString(envelope.at("/meta/requestId").asText());
    return envelope.get("data");
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

  UUID event(UUID organization, UUID mission, UUID task) {
    UUID event = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,?,?,'HUMAN',?,'SAFE_TYPE','private payload','2026-01-01T00:00:00Z')",
        event,
        organization,
        mission,
        task,
        account);
    return event;
  }

  @Test
  void exactProjectionAndNullableFieldsWithoutBusinessWrites() throws Exception {
    var before = snapshot();
    JsonNode root = read(get(url()));
    keys(root, "generatedAt", "task", "mission", "team");
    java.time.Instant.parse(root.get("generatedAt").asText());
    JsonNode detail = root.get("task");
    keys(
        detail,
        "id",
        "organizationId",
        "teamId",
        "missionId",
        "title",
        "status",
        "pendingReason",
        "waitingSince",
        "resolver",
        "createdAt",
        "updatedAt");
    assertThat(detail.get("id").asText()).isEqualTo(taskId.toString());
    assertThat(detail.get("organizationId").asText()).isEqualTo(org.toString());
    assertThat(detail.get("teamId").asText()).isEqualTo(team.toString());
    assertThat(detail.get("missionId").asText()).isEqualTo(id.toString());
    assertThat(detail.get("title").asText()).isEqualTo("Task");
    assertThat(detail.get("status").asText()).isEqualTo("WAITING");
    assertThat(detail.get("pendingReason").asText()).isEqualTo("Approval");
    assertThat(detail.get("resolver").asText()).isEqualTo("Manager");
    assertThat(java.time.Instant.parse(detail.get("waitingSince").asText()))
        .isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    for (String field : List.of("createdAt", "updatedAt"))
      java.time.Instant.parse(detail.get(field).asText());
    keys(root.get("mission"), "id", "title");
    keys(root.get("team"), "id", "name");
    assertThat(root.at("/mission/id").asText()).isEqualTo(id.toString());
    assertThat(root.at("/mission/title").asText()).isEqualTo("Visible");
    assertThat(root.at("/team/id").asText()).isEqualTo(team.toString());
    assertThat(root.at("/team/name").asText()).isEqualTo("Primary");
    assertThat(read(get(url() + "/activity")).get("items")).isEmpty();
    assertThat(snapshot()).isEqualTo(before);
    UUID ready = task(org, team, id, "READY");
    JsonNode readyTask = read(get(url().replace(taskId.toString(), ready.toString()))).get("task");
    for (String field : List.of("pendingReason", "waitingSince", "resolver"))
      assertThat(readyTask.get(field).isNull()).isTrue();
  }

  @Test
  void validatesEveryParentIncludingSameTeamOtherMission() throws Exception {
    UUID sibling = team(org, "Sibling", "ACTIVE");
    UUID siblingMission = mission(org, sibling, "Sibling", "READY");
    UUID otherMission = mission(org, team, "Same team other mission", "READY");
    UUID foreignTeam = team(foreign, "Foreign", "ACTIVE");
    UUID foreignMission = mission(foreign, foreignTeam, "Foreign", "READY");
    UUID foreignTask = task(foreign, foreignTeam, foreignMission, "READY");
    for (String suffix : List.of("", "/activity")) {
      for (UUID invalid : List.of(UUID.randomUUID(), sibling, foreignTeam))
        mvc.perform(get(url().replace(team.toString(), invalid.toString()) + suffix).with(auth()))
            .andExpect(status().isNotFound());
      for (UUID invalid :
          List.of(
              UUID.randomUUID(),
              otherMission,
              siblingMission,
              foreignMission,
              mission(org, null, "Legacy", "CREATED")))
        mvc.perform(get(url().replace(id.toString(), invalid.toString()) + suffix).with(auth()))
            .andExpect(status().isNotFound());
      for (UUID invalid :
          List.of(
              UUID.randomUUID(),
              foreignTask,
              task(org, team, otherMission, "READY"),
              task(org, sibling, siblingMission, "READY")))
        mvc.perform(get(url().replace(taskId.toString(), invalid.toString()) + suffix).with(auth()))
            .andExpect(status().isNotFound());
      mvc.perform(get(url().replace(org.toString(), foreign.toString()) + suffix).with(auth()))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void activityRejectsForeignReferencesAndPagesOnlyExactTaskMetadata() throws Exception {
    UUID otherMission = mission(org, team, "Other", "READY");
    UUID otherTask = task(org, team, otherMission, "READY");
    UUID foreignTeam = team(foreign, "Foreign", "ACTIVE");
    UUID foreignMission = mission(foreign, foreignTeam, "Foreign", "READY");
    UUID foreignTask = task(foreign, foreignTeam, foreignMission, "READY");
    event(org, id, otherTask);
    event(org, id, foreignTask);
    event(org, id, UUID.randomUUID());
    event(org, otherMission, taskId);
    event(org, foreignMission, taskId);
    event(foreign, id, taskId);
    event(org, id, null);
    event(org, null, null);
    var safe = new ArrayList<String>();
    for (int i = 0; i < 25; i++) safe.add(event(org, i % 2 == 0 ? null : id, taskId).toString());
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
      JsonNode data = read(get(url() + "/activity").param("page", "" + page).param("size", "100"));
      keys(data, "items", "page", "size", "total");
      assertThat(data.get("total").asInt()).isEqualTo(25);
      assertThat(data.get("page").asInt()).isEqualTo(page);
      assertThat(data.get("size").asInt()).isEqualTo(20);
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
  void malformedParametersRespectSecurityBoundaryAndPageBounds() throws Exception {
    for (String page : List.of("-1", "100001", "2147483648", "1.0", "bad"))
      mvc.perform(get(url() + "/activity").param("page", page).with(auth()))
          .andExpect(status().isBadRequest());
    for (UUID parameter : List.of(team, id, taskId))
      for (String suffix : List.of("", "/activity"))
        mvc.perform(get(url().replace(parameter.toString(), "bad") + suffix).with(auth()))
            .andExpect(status().isBadRequest());
    for (String suffix : List.of("", "/activity"))
      mvc.perform(get(url().replace(org.toString(), "bad") + suffix).with(auth()))
          .andExpect(status().isForbidden());
  }

  void allowed(HumanIdentity human) {
    service.get(org, team, id, taskId, human);
    service.activity(org, team, id, taskId, human, 0);
  }

  void denied(HumanIdentity human) {
    assertThatThrownBy(() -> service.get(org, team, id, taskId, human))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.activity(org, team, id, taskId, human, 0))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void everyReadRechecksFreshHumanAccountAndMembership() throws Exception {
    HumanIdentity human = new HumanIdentity(account, account + "@example.test", "Tester");
    allowed(human);
    denied(null);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", account);
    allowed(human);
    for (String suffix : List.of("", "/activity")) {
      read(get(url() + suffix));
      mvc.perform(get(url() + suffix)).andExpect(status().isUnauthorized());
    }
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", account);
      denied(human);
      for (String suffix : List.of("", "/activity"))
        mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isUnauthorized());
    }
    jdbc.update("update human_accounts set enabled=true,credential_version=0 where id=?", account);
    allowed(human);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    denied(human);
    for (String suffix : List.of("", "/activity"))
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isForbidden());
  }
}

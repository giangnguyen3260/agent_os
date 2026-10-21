package com.agentos.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.application.MissionDirectoryService;
import com.agentos.mission.domain.MissionStatus;
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
class MissionDirectoryIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MissionDirectoryService service;
  UUID org, foreign, team, account;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org, "Primary", "ACTIVE");
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

  void task(UUID organization, UUID assigned, UUID mission, String state) {
    boolean pending = List.of("WAITING", "BLOCKED").contains(state);
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)"
            + " values (?,?,?,?,'Task',?,?,?,?)",
        UUID.randomUUID(),
        organization,
        assigned,
        mission,
        state,
        pending ? "Approval" : null,
        pending ? java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z") : null,
        pending ? "Manager" : null);
  }

  String url() {
    return "/api/v1/organizations/" + org + "/missions/directory";
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

  @Test
  void scopedCountsProjectionAndReadDoesNotWrite() throws Exception {
    UUID id = mission(org, team, "Visible", "RUNNING");
    UUID manager = manager(org, team);
    jdbc.update("update missions set manager_agent_id=? where id=?", manager, id);
    manager(org, team);
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
    task(org, team, mission(org, team, "Other mission", "READY"), "COMPLETED");
    UUID sibling = team(org, "Sibling", "ACTIVE");
    task(org, sibling, mission(org, sibling, "Sibling", "READY"), "COMPLETED");
    UUID other = team(foreign, "Foreign", "ACTIVE");
    task(foreign, other, mission(foreign, other, "Hidden", "READY"), "COMPLETED");
    var before = snapshot();
    JsonNode data = read(get(url()).param("query", "visible"));
    assertThat(data.get("total").asLong()).isEqualTo(1);
    assertThat(data.get("team").isNull()).isTrue();
    JsonNode item = data.at("/items/0");
    assertThat(item.get("id").asText()).isEqualTo(id.toString());
    assertThat(item.get("organizationId").asText()).isEqualTo(org.toString());
    assertThat(item.get("teamName").asText()).isEqualTo("Primary");
    assertThat(item.get("managerAgentId").asText()).isEqualTo(manager.toString());
    assertThat(item.get("managerName").asText()).isEqualTo("Private manager");
    assertThat(item.get("totalTasks").asLong()).isEqualTo(10);
    assertThat(item.get("completedTasks").asLong()).isEqualTo(2);
    assertThat(item.get("pendingTasks").asLong()).isEqualTo(2);
    var fields = new ArrayList<String>();
    item.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "id",
            "organizationId",
            "title",
            "status",
            "priority",
            "teamId",
            "teamName",
            "managerAgentId",
            "managerName",
            "totalTasks",
            "completedTasks",
            "pendingTasks",
            "createdAt");
    assertThat(snapshot()).isEqualTo(before);
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

  @Test
  void unresolvedManagersAndLegacyNullTeamAreSafe() throws Exception {
    UUID id = mission(org, team, "Visible", "CREATED");
    UUID sibling = team(org, "Sibling", "ACTIVE");
    UUID other = team(foreign, "Foreign", "ACTIVE");
    for (UUID manager :
        List.of(manager(org, sibling), manager(foreign, other), manager(org, null))) {
      jdbc.update("update missions set manager_agent_id=? where id=?", manager, id);
      JsonNode item = read(get(url()).param("query", "Visible")).at("/items/0");
      assertThat(item.get("managerAgentId").isNull()).isTrue();
      assertThat(item.get("managerName").isNull()).isTrue();
    }
    UUID moved = manager(org, team);
    jdbc.update("update missions set manager_agent_id=? where id=?", moved, id);
    jdbc.update("update agents set team_id=? where id=?", sibling, moved);
    assertThat(read(get(url()).param("query", "Visible")).at("/items/0/managerAgentId").isNull())
        .isTrue();
    mission(org, null, "Legacy", "CREATED");
    JsonNode legacy = read(get(url()).param("query", "Legacy")).at("/items/0");
    for (String field : List.of("teamId", "teamName", "managerAgentId", "managerName"))
      assertThat(legacy.get(field).isNull()).isTrue();
    for (String field : List.of("totalTasks", "completedTasks", "pendingTasks"))
      assertThat(legacy.get(field).asLong()).isZero();
  }

  @Test
  void literalCaseInsensitiveSearchAllStatusesAndScopedTeam() throws Exception {
    UUID archived = team(org, "Archived", "ARCHIVED");
    for (MissionStatus state : MissionStatus.values()) {
      UUID id = mission(org, archived, "Rate%_! MiXeD", state.name());
      mission(org, team, "RateXYZ mixed", state.name());
      JsonNode data =
          read(
              get(url())
                  .param("query", "  %_! mixed  ")
                  .param("status", state.name())
                  .param("teamId", archived.toString()));
      assertThat(data.get("total").asLong()).isEqualTo(1);
      assertThat(data.at("/items/0/id").asText()).isEqualTo(id.toString());
      assertThat(data.at("/team/id").asText()).isEqualTo(archived.toString());
      assertThat(data.at("/team/name").asText()).isEqualTo("Archived");
      assertThat(data.at("/team/status").asText()).isEqualTo("ARCHIVED");
    }
    assertThat(read(get(url()).param("query", "' OR 1=1 --")).get("total").asLong()).isZero();
    JsonNode empty = read(get(url()).param("teamId", team.toString()).param("query", "missing"));
    assertThat(empty.get("items")).isEmpty();
    assertThat(empty.at("/team/id").asText()).isEqualTo(team.toString());
  }

  @Test
  void fixedPagingStableOrderAndEmptyOutOfRange() throws Exception {
    for (int i = 0; i < 25; i++) mission(org, team, "Same", "READY");
    jdbc.update(
        "update missions set created_at='2026-01-01T00:00:00Z' where organization_id=?", org);
    var expected =
        jdbc.query(
            "select id from missions where organization_id=? order by created_at desc,id",
            (rs, n) -> rs.getObject(1, UUID.class).toString(),
            org);
    JsonNode first = read(get(url()).param("size", "100"));
    JsonNode second = read(get(url()).param("page", "1"));
    assertThat(first.get("size").asInt()).isEqualTo(20);
    assertThat(first.get("page").asInt()).isZero();
    assertThat(first.get("items")).hasSize(20);
    assertThat(second.get("items")).hasSize(5);
    assertThat(second.get("total").asInt()).isEqualTo(25);
    var actual = new ArrayList<String>();
    first.get("items").forEach(n -> actual.add(n.get("id").asText()));
    second.get("items").forEach(n -> actual.add(n.get("id").asText()));
    assertThat(actual).containsExactlyElementsOf(expected);
    UUID newest = mission(org, team, "Newest", "CREATED");
    assertThat(read(get(url())).at("/items/0/id").asText()).isEqualTo(newest.toString());
    JsonNode beyond = read(get(url()).param("page", "100000"));
    assertThat(beyond.get("items")).isEmpty();
    assertThat(beyond.get("total").asInt()).isEqualTo(26);
  }

  @Test
  void invalidParametersAndForeignTeam() throws Exception {
    for (String page : List.of("-1", "100001", "2147483648", "1.0", "bad"))
      mvc.perform(get(url()).with(auth()).param("page", page)).andExpect(status().isBadRequest());
    for (String state : List.of("UNKNOWN", "running"))
      mvc.perform(get(url()).with(auth()).param("status", state))
          .andExpect(status().isBadRequest());
    mvc.perform(get(url()).with(auth()).param("query", "x".repeat(201)))
        .andExpect(status().isBadRequest());
    assertThat(read(get(url()).param("query", "x".repeat(200))).get("items")).isEmpty();
    mvc.perform(get(url()).with(auth()).param("teamId", "bad")).andExpect(status().isBadRequest());
    for (UUID missing : List.of(UUID.randomUUID(), team(foreign, "Private", "ACTIVE")))
      mvc.perform(get(url()).with(auth()).param("teamId", missing.toString()))
          .andExpect(status().isNotFound());
  }

  @Test
  void serviceAlsoReauthorizesWithoutHttpFilter() {
    HumanIdentity human = new HumanIdentity(account, account + "@example.test", "Tester");
    assertThat(service.directory(org, human, 0, "", null, null).items()).isEmpty();
    assertThatThrownBy(() -> service.directory(org, null, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set credential_version=1 where id=?", account);
    assertThatThrownBy(() -> service.directory(org, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set credential_version=0,enabled=false where id=?", account);
    assertThatThrownBy(() -> service.directory(org, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set enabled=true where id=?", account);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    assertThatThrownBy(() -> service.directory(org, human, 0, "", null, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void freshAuthorizationAndRevocation() throws Exception {
    mission(org, team, "Visible", "READY");
    read(get(url()));
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", account);
    read(get(url()));
    mvc.perform(get(url())).andExpect(status().isUnauthorized());
    mvc.perform(get(url().replace(org.toString(), foreign.toString())).with(auth()))
        .andExpect(status().isForbidden());
    mvc.perform(get(url().replace(org.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isForbidden());
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", account);
      mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
    }
    jdbc.update("update human_accounts set enabled=true,credential_version=0 where id=?", account);
    read(get(url()));
    jdbc.update("delete from organization_memberships where account_id=?", account);
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
  }
}

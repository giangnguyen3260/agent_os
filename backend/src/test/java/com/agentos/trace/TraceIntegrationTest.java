package com.agentos.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraceIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TraceExplorerService service;
  UUID org, foreign, team, mission, task, account;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org);
    mission = mission(org, team);
    task = task(org, team, mission);
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

  UUID team(UUID organization) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        id,
        organization);
    return id;
  }

  UUID mission(UUID organization, UUID assigned) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values (?,?,?,'Mission','Private"
            + " goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        id,
        organization,
        assigned);
    return id;
  }

  UUID task(UUID organization, UUID assigned, UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
            + " (?,?,?,?,'Task','READY')",
        id,
        organization,
        assigned,
        parent);
    return id;
  }

  UUID event(UUID organization, UUID parent, UUID child, String type) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,?,?,'OPAQUE_ACTOR',?,?,?,'2026-01-01T07:00:00+07:00')",
        id,
        organization,
        parent,
        child,
        account,
        type,
        "PRIVATE_PAYLOAD_SENTINEL <script> secret");
    return id;
  }

  HumanIdentity human() {
    return new HumanIdentity(account, account + "@example.test", "Tester");
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human(), null, List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/trace-events";
  }

  JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mvc.perform(request.with(auth()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain(
            "PRIVATE_PAYLOAD_SENTINEL",
            "Private goal",
            "actorId",
            "payload",
            "correlationId",
            "runId");
    JsonNode envelope = json.readTree(body);
    keys(envelope, "data", "meta");
    return envelope.get("data");
  }

  void keys(JsonNode node, String... expected) {
    var fields = new ArrayList<String>();
    node.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder(expected);
  }

  List<List<Map<String, Object>>> snapshot() {
    return List.of(
            "organizations",
            "organization_memberships",
            "human_accounts",
            "teams",
            "missions",
            "tasks",
            "audit_events",
            "mission_create_receipts",
            "task_create_receipts",
            "authentication_audit")
        .stream()
        .map(table -> jdbc.queryForList("select * from " + table + " order by 1"))
        .toList();
  }

  @Test
  void exactProjectionDetailExplicitNullsUtcAndNoWrites() throws Exception {
    UUID id = event(org, mission, task, "<opaque> SUCCEEDED?");
    UUID legacy = mission(org, null);
    UUID legacyEvent = event(org, legacy, null, "Legacy");
    var before = snapshot();
    Instant start = Instant.now();
    JsonNode page = read(get(url()));
    keys(page, "organizationId", "generatedAt", "runtimeStatus", "items", "page", "size", "total");
    assertThat(page.get("organizationId").asText()).isEqualTo(org.toString());
    assertThat(page.get("runtimeStatus").asText()).isEqualTo("UNAVAILABLE");
    assertThat(Instant.parse(page.get("generatedAt").asText())).isBetween(start, Instant.now());
    assertThat(page.get("total").asInt()).isEqualTo(2);
    JsonNode detail = read(get(url() + "/" + id));
    keys(detail, "organizationId", "generatedAt", "runtimeStatus", "event");
    JsonNode item = detail.get("event");
    keys(item, "id", "eventType", "actorType", "createdAt", "team", "mission", "task");
    keys(item.get("team"), "id", "name");
    keys(item.get("mission"), "id", "title");
    keys(item.get("task"), "id", "title");
    assertThat(item.at("/team/id").asText()).isEqualTo(team.toString());
    assertThat(item.at("/team/name").asText()).isEqualTo("Team");
    assertThat(item.at("/mission/id").asText()).isEqualTo(mission.toString());
    assertThat(item.at("/task/id").asText()).isEqualTo(task.toString());
    assertThat(item.get("eventType").asText()).isEqualTo("<opaque> SUCCEEDED?");
    assertThat(item.get("actorType").asText()).isEqualTo("OPAQUE_ACTOR");
    assertThat(item.get("createdAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
    JsonNode nullable = read(get(url() + "/" + legacyEvent)).get("event");
    assertThat(nullable.get("team").isNull()).isTrue();
    assertThat(nullable.get("task").isNull()).isTrue();
    assertThat(nullable.at("/mission/id").asText()).isEqualTo(legacy.toString());
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void sourceVisibilityRejectsWholeEventAndDetailWithoutDroppingConstraints() throws Exception {
    UUID foreignTeam = team(foreign);
    UUID foreignMission = mission(foreign, foreignTeam);
    UUID foreignTask = task(foreign, foreignTeam, foreignMission);
    UUID otherMission = mission(org, team);
    var excluded =
        List.of(
            event(org, null, null, "Organization only"),
            event(foreign, foreignMission, foreignTask, "Foreign"),
            event(org, foreignMission, null, "Foreign mission"),
            event(org, null, foreignTask, "Foreign task"),
            event(org, mission, foreignTask, "Foreign child"),
            event(org, foreignMission, task, "Foreign parent"),
            event(org, otherMission, task, "Disagree"),
            event(org, mission, UUID.randomUUID(), "Dangling task"));
    UUID taskOnly = event(org, null, task, "Task only");
    JsonNode page = read(get(url()));
    assertThat(page.get("total").asInt()).isEqualTo(1);
    assertThat(page.at("/items/0/id").asText()).isEqualTo(taskOnly.toString());
    assertThat(page.at("/items/0/mission/id").asText()).isEqualTo(mission.toString());
    for (UUID id : excluded) {
      mvc.perform(get(url() + "/" + id).with(auth())).andExpect(status().isNotFound());
    }
    mvc.perform(get(url() + "/" + UUID.randomUUID()).with(auth())).andExpect(status().isNotFound());
  }

  @Test
  void stablePaginationCountsBeyondLastPageAndRefresh() throws Exception {
    var ids = new ArrayList<String>();
    for (int i = 0; i < 25; i++)
      ids.add(event(org, i % 2 == 0 ? null : mission, task, "MATCH").toString());
    ids.sort(String::compareTo);
    var actual = new ArrayList<String>();
    for (int page = 0; page < 2; page++) {
      JsonNode data = read(get(url()).param("page", "" + page));
      assertThat(data.get("page").asInt()).isEqualTo(page);
      assertThat(data.get("size").asInt()).isEqualTo(20);
      assertThat(data.get("total").asInt()).isEqualTo(25);
      assertThat(data.get("items")).hasSize(page == 0 ? 20 : 5);
      data.get("items").forEach(item -> actual.add(item.get("id").asText()));
    }
    assertThat(actual).containsExactlyElementsOf(ids);
    JsonNode beyond = read(get(url()).param("page", "100000"));
    assertThat(beyond.get("items")).isEmpty();
    assertThat(beyond.get("total").asInt()).isEqualTo(25);
    UUID newest = event(org, mission, null, "NEW");
    jdbc.update("update audit_events set created_at='2027-01-01T00:00:00Z' where id=?", newest);
    assertThat(read(get(url())).at("/items/0/id").asText()).isEqualTo(newest.toString());
  }

  @Test
  void filtersIntersectProjectedReferencesAndNeverRevealForeignMetadata() throws Exception {
    event(org, null, task, "MATCH");
    event(org, mission, null, "MATCH");
    UUID other = mission(org, team);
    event(org, other, null, "MATCH");
    assertThat(read(get(url()).param("missionId", mission.toString())).get("total").asInt())
        .isEqualTo(2);
    assertThat(
            read(get(url()).param("missionId", mission.toString()).param("taskId", task.toString()))
                .get("total")
                .asInt())
        .isEqualTo(1);
    assertThat(
            read(get(url()).param("missionId", other.toString()).param("taskId", task.toString()))
                .get("total")
                .asInt())
        .isZero();
    UUID foreignTeam = team(foreign);
    UUID foreignMission = mission(foreign, foreignTeam);
    UUID foreignTask = task(foreign, foreignTeam, foreignMission);
    for (String key : List.of("missionId", "taskId")) {
      for (UUID filter : List.of(foreignMission, foreignTask, UUID.randomUUID())) {
        JsonNode result = read(get(url()).param(key, filter.toString()));
        assertThat(result.get("total").asInt()).isZero();
        assertThat(result.get("items")).isEmpty();
      }
    }
  }

  @Test
  void literalCaseInsensitiveSearchEscapesPercentUnderscoreEscapeAndSqlText() throws Exception {
    String type = "Mixed%_!\\' OR 1=1 --";
    UUID id = event(org, mission, null, type);
    event(org, mission, null, "MixedABC");
    for (String query : List.of("%", "_", "!", "\\", "' OR 1=1 --", "  mIxEd%  ")) {
      JsonNode data = read(get(url()).param("q", query));
      assertThat(data.get("total").asInt()).isEqualTo(1);
      assertThat(data.at("/items/0/id").asText()).isEqualTo(id.toString());
    }
    assertThat(read(get(url()).param("q", "\u2003 \u2003")).get("total").asInt()).isEqualTo(2);
    read(get(url()).param("q", "😀".repeat(100)));
    mvc.perform(get(url()).param("q", "😀".repeat(101)).with(auth()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void strictQueryAllowlistDuplicatesPagesAndCanonicalUuids() throws Exception {
    for (String key : List.of("size", "sort", "unexpected")) {
      mvc.perform(get(url()).param(key, "20").with(auth())).andExpect(status().isBadRequest());
    }
    for (String key : List.of("q", "page", "missionId", "taskId")) {
      mvc.perform(get(url()).param(key, "0", "0").with(auth())).andExpect(status().isBadRequest());
    }
    for (String page :
        List.of("", "00", "01", "+1", "-1", " 1", "1.0", "100001", "2147483648", "bad")) {
      mvc.perform(get(url()).param("page", page).with(auth())).andExpect(status().isBadRequest());
    }
    for (String invalid :
        List.of(
            "",
            "bad",
            mission.toString().toUpperCase(java.util.Locale.ROOT),
            "1-1-1-1-1",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-6000-8000-000000000000",
            "00000000-0000-4000-0000-000000000000")) {
      for (String key : List.of("missionId", "taskId")) {
        mvc.perform(get(url()).param(key, invalid).with(auth())).andExpect(status().isBadRequest());
      }
      if (!invalid.isEmpty())
        mvc.perform(get(url() + "/" + invalid).with(auth())).andExpect(status().isBadRequest());
    }
    UUID id = event(org, mission, null, "Event");
    mvc.perform(get(url() + "/" + id).param("q", "").with(auth()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url().replace(org.toString(), "bad")).with(auth()))
        .andExpect(status().isForbidden());
    mvc.perform(get(url()).param("size", "20")).andExpect(status().isUnauthorized());
    mvc.perform(
            get(url().replace(org.toString(), foreign.toString())).param("size", "20").with(auth()))
        .andExpect(status().isForbidden());
  }

  @Test
  void legacyPayloadEndpointsReturn404ForAdminAndMemberWithoutLeakingOrWriting() throws Exception {
    event(org, mission, task, "Visible with secret payload");
    var before = snapshot();
    String base = "/api/v1/organizations/" + org;
    for (String role : List.of("ADMIN", "MEMBER")) {
      jdbc.update("update organization_memberships set role=? where account_id=?", role, account);
      for (String path :
          List.of(base + "/audit-events", base + "/missions/" + mission + "/trace")) {
        String body =
            mvc.perform(get(path).with(auth()))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
            .doesNotContain(
                "PRIVATE_PAYLOAD_SENTINEL",
                "Visible with secret payload",
                "OPAQUE_ACTOR",
                "payload",
                account.toString());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path.replace(org.toString(), foreign.toString())).with(auth()))
            .andExpect(status().isForbidden());
      }
    }
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", account);
    assertThat(snapshot()).isEqualTo(before);
  }

  void deniedService(UUID id, HumanIdentity identity) {
    assertThatThrownBy(
            () -> service.list(org, identity, TraceQuery.parse(new LinkedMultiValueMap<>())))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.detail(org, identity, id))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void missingOrganizationMatchesExistingAuthorizationDenialBeforeAnyEventRead() throws Exception {
    UUID missing = UUID.randomUUID();
    UUID eventId = event(org, mission, null, "Safe");
    assertThatThrownBy(
            () -> service.list(missing, human(), TraceQuery.parse(new LinkedMultiValueMap<>())))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.detail(missing, human(), eventId))
        .isInstanceOf(AccessDeniedException.class);
    for (String suffix : List.of("", "/" + eventId)) {
      mvc.perform(get(url().replace(org.toString(), missing.toString()) + suffix).with(auth()))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void currentAuthorityRecheckedForBothReadsAtServiceAndHttpBoundaries() throws Exception {
    UUID id = event(org, mission, null, "Event");
    deniedService(id, null);
    for (String role : List.of("MEMBER", "ADMIN")) {
      jdbc.update("update organization_memberships set role=? where account_id=?", role, account);
      read(get(url()));
      read(get(url() + "/" + id));
    }
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", account);
      deniedService(id, human());
      for (String suffix : List.of("", "/" + id))
        mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isUnauthorized());
    }
    jdbc.update("update human_accounts set enabled=true,credential_version=0 where id=?", account);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    deniedService(id, human());
    for (String suffix : List.of("", "/" + id))
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isForbidden());
  }
}

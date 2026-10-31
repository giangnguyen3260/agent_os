package com.agentos.taskdependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP-only command adapter; no dependency service or production canonicalizer is used. */
abstract class DependencyHttpFixture {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org, team, mission, task, actor;
  MockHttpSession session;
  final List<UUID> actors = new ArrayList<>();

  public static OffsetDateTime clock() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC);
  }

  @BeforeEach
  void seedDependencyScope() throws Exception {
    try (Connection c = jdbc.getDataSource().getConnection()) {
      if (c.getMetaData().getDatabaseProductName().equals("H2")) {
        jdbc.execute(
            "create alias if not exists clock_timestamp for"
                + " 'com.agentos.taskdependencies.TaskDependenciesIntegrationTest.clock'");
      }
    }
    org = UUID.randomUUID();
    team = UUID.randomUUID();
    mission = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Dependency"
            + " QC',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        "dependency-qc-" + org);
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','DRAFT',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values"
            + " (?,?,?,'Mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        mission,
        org,
        team);
    actor = admin();
    session = session(actor);
    task = node("Source", "DRAFT");
  }

  UUID admin() {
    UUID id = UUID.randomUUID();
    actors.add(id);
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'QC','unused')",
        id,
        id + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        id,
        org);
    return id;
  }

  MockHttpSession session(UUID id) {
    var result = new MockHttpSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new HumanIdentity(id, id + "@example.test", "QC"), null, List.of()));
    result.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    return result;
  }

  RequestPostProcessor auth() {
    var context =
        (org.springframework.security.core.context.SecurityContext)
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    return authentication(context.getAuthentication());
  }

  UUID node(String title, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)"
            + " values (?,?,?,?,?,?,?,?,?)",
        id,
        org,
        team,
        mission,
        title,
        state,
        List.of("WAITING", "BLOCKED").contains(state) ? "Awaiting QC input" : null,
        List.of("WAITING", "BLOCKED").contains(state) ? clock() : null,
        List.of("WAITING", "BLOCKED").contains(state) ? "QC" : null);
    return id;
  }

  void edge(UUID source, UUID target) {
    jdbc.update(
        "insert into"
            + " task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)"
            + " values (?,?,?,?,?)",
        org,
        team,
        mission,
        source,
        target);
  }

  String root(UUID source) {
    return "/api/v1/organizations/"
        + org
        + "/teams/"
        + team
        + "/missions/"
        + mission
        + "/tasks/"
        + source
        + "/draft-dependencies";
  }

  JsonNode read(String path) throws Exception {
    return data(mvc.perform(get(path).session(session).with(auth())).andReturn(), 200);
  }

  JsonNode context() throws Exception {
    return read(root(task));
  }

  JsonNode candidate(UUID source, UUID target) throws Exception {
    return read(root(source) + "/eligible-dependencies/" + target);
  }

  ObjectNode command(UUID source, UUID... targets) throws Exception {
    JsonNode snapshot = read(root(source));
    ObjectNode body = json.createObjectNode();
    body.set("expectedRevision", snapshot.get("revision"));
    body.set("expectedFingerprint", snapshot.get("fingerprint"));
    var desired = body.putArray("dependencies");
    for (UUID target : targets) {
      var item = desired.addObject();
      item.put("taskId", target.toString());
      item.set("fingerprint", candidate(source, target).get("fingerprint"));
    }
    return body;
  }

  MockHttpServletRequestBuilder postRequest(UUID source, UUID key, String body) {
    return postAt(root(source), key, body);
  }

  MockHttpServletRequestBuilder postAt(String path, UUID key, String body) {
    return post(path)
        .session(session)
        .with(auth())
        .with(csrf().asHeader())
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .content(body);
  }

  MvcResult send(UUID source, UUID key, JsonNode body) throws Exception {
    return mvc.perform(postRequest(source, key, json.writeValueAsString(body))).andReturn();
  }

  JsonNode data(MvcResult result, int expected) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(expected);
    JsonNode envelope = json.readTree(result.getResponse().getContentAsByteArray());
    assertThat(envelope.has("data")).isTrue();
    assertThat(envelope.has("meta")).isTrue();
    return envelope.get("data");
  }

  void error(MvcResult result, int expected, String code) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(expected);
    JsonNode e = json.readTree(result.getResponse().getContentAsByteArray()).get("error");
    assertThat(e.path("code").asText()).isEqualTo(code);
    assertThat(e.path("details").isObject()).isTrue();
    assertThat(e.path("details").size()).isZero();
  }

  void fields(JsonNode value, String... expected) {
    List<String> actual = new ArrayList<>();
    value.fieldNames().forEachRemaining(actual::add);
    assertThat(actual).containsExactlyInAnyOrder(expected);
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  Map<String, Object> sourceRow() {
    return jdbc.queryForMap("select * from tasks where id=?", task);
  }

  List<Object> state() {
    return List.of(
        jdbc.queryForList("select * from tasks where organization_id=? order by id", org),
        jdbc.queryForList(
            "select * from task_dependencies where organization_id=? order by"
                + " task_id,dependency_task_id",
            org),
        jdbc.queryForList(
            "select * from task_dependency_events where organization_id=? order by id", org),
        jdbc.queryForList(
            "select * from task_dependency_receipts where organization_id=? order by event_id",
            org),
        jdbc.queryForList("select * from audit_events where organization_id=? order by id", org));
  }

  @AfterEach
  void deleteDependencyScope() {
    if (org == null) return;
    for (String table :
        List.of(
            "task_dependency_receipts",
            "task_dependency_events",
            "task_owner_receipts",
            "task_owner_events",
            "task_edit_receipts",
            "task_edit_events",
            "task_create_receipts",
            "audit_events",
            "task_dependencies",
            "tasks",
            "organization_memberships",
            "agents",
            "missions",
            "teams")) {
      jdbc.update("delete from " + table + " where organization_id=?", org);
    }
    jdbc.update("delete from organizations where id=?", org);
    for (UUID id : actors) jdbc.update("delete from human_accounts where id=?", id);
    actors.clear();
  }
}

package com.agentos.taskediting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.taskediting.TaskDraftEditDtos.Command;
import com.agentos.taskediting.TaskDraftEditDtos.Context;
import com.agentos.taskediting.TaskDraftEditDtos.Receipt;
import com.agentos.taskediting.TaskDraftEditDtos.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:task_edit_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskDraftEditIntegrationTest {
  @Autowired TaskDraftEditService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org, team, mission, task, actor;
  HumanIdentity human;

  public static OffsetDateTime clock() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC);
  }

  @BeforeEach
  void setup() throws Exception {
    try (Connection c = jdbc.getDataSource().getConnection()) {
      if (c.getMetaData().getDatabaseProductName().equals("H2"))
        jdbc.execute(
            "create alias if not exists clock_timestamp for"
                + " 'com.agentos.taskediting.TaskDraftEditIntegrationTest.clock'");
    }
    org = UUID.randomUUID();
    team = UUID.randomUUID();
    mission = UUID.randomUUID();
    task = UUID.randomUUID();
    actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Actor','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
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
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
            + " (?,?,?,?,'Legacy','DRAFT')",
        task,
        org,
        team,
        mission);
    human = new HumanIdentity(actor, actor + "@example.test", "Session");
  }

  Context context() {
    return service.context(org, team, mission, task, human);
  }

  Command command(Context s, String title) {
    return new Command(
        s.revision(), s.fingerprint(), title, "Description\ninput", "Criteria", "HIGH");
  }

  Result save(UUID key, Command c) {
    return service.update(org, team, mission, task, human, key, c);
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  String url() {
    return "/api/v1/organizations/"
        + org
        + "/teams/"
        + team
        + "/missions/"
        + mission
        + "/tasks/"
        + task
        + "/draft-settings";
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  Map<String, Object> row(String table, UUID id) {
    return jdbc.queryForMap("select * from " + table + " where id=?", id);
  }

  TaskDraftEditDtos.History history(int page) {
    return service.history(org, team, mission, task, human, page);
  }

  List<Object> businessState() {
    return List.of(
        row("tasks", task), row("missions", mission), row("teams", team),
        jdbc.queryForList("select * from task_edit_events where organization_id=? order by revision", org),
        jdbc.queryForList("select * from task_edit_receipts where organization_id=? order by event_id", org),
        jdbc.queryForList("select * from audit_events where organization_id=? order by id", org));
  }

  @Test
  void historyReadsRealEventsWithCurrentNamesNullableSnapshotsAndFixedPagination() throws Exception {
    assertThat(history(0).total()).isZero();
    assertThat(history(0).items()).isEmpty();
    for (int i = 1; i <= 23; i++) save(UUID.randomUUID(), command(context(), "Revision " + i));
    jdbc.update("update human_accounts set display_name='Current human name' where id=?", actor);
    jdbc.update("update tasks set status='COMPLETED' where id=?", task);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update("update missions set status='COMPLETED',task_intake_paused=true where id=?", mission);
    var state = businessState();
    var first = history(0);
    var second = history(1);
    assertThat(first.pageSize()).isEqualTo(20);
    assertThat(first.total()).isEqualTo(23);
    assertThat(first.items()).extracting(TaskDraftEditDtos.HistoryItem::revision)
        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20).map(i -> 23 - i).boxed().toList());
    assertThat(second.items()).extracting(TaskDraftEditDtos.HistoryItem::revision).containsExactly(3, 2, 1);
    assertThat(second.total()).isEqualTo(23);
    assertThat(history(100000).items()).isEmpty();
    assertThat(history(100000).total()).isEqualTo(23);
    var oldest = second.items().get(2);
    assertThat(oldest.before()).isEqualTo(new TaskDraftEditDtos.Values("Legacy", null, null, null));
    assertThat(oldest.after().title()).isEqualTo("Revision 1");
    assertThat(first.items()).allSatisfy(item -> {
      assertThat(item.actorName()).isEqualTo("Current human name");
      assertThat(java.time.Instant.parse(item.committedAt())).isNotNull();
    });
    var response = mvc.perform(get(url() + "/history").with(auth()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var data = json.readTree(response).get("data");
    assertThat(data.size()).isEqualTo(8);
    assertThat(data.get("items").get(0).size()).isEqualTo(6);
    assertThat(data.get("items").get(0).get("before").size()).isEqualTo(4);
    assertThat(response).doesNotContain("@example.test", "actorId", "idempotencyKey", "body_sha256", "fingerprint");
    mvc.perform(get(url() + "/history").with(auth()).queryParam("page", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[2].before.description").value(nullValue()));
    assertThat(businessState()).isEqualTo(state);
  }

  @Test
  void historyCannotCrossExistingParentsOrIncludeSiblingEvents() throws Exception {
    save(UUID.randomUUID(), command(context(), "Scoped"));
    UUID sibling = UUID.randomUUID();
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values (?,?,?,?,'Sibling','DRAFT')",
        sibling, org, team, mission);
    var siblingContext = service.context(org, team, mission, sibling, human);
    service.update(org, team, mission, sibling, human, UUID.randomUUID(), command(siblingContext, "Sibling edit"));
    assertThat(history(0).total()).isEqualTo(1);
    assertThat(history(0).items()).singleElement().satisfies(item -> assertThat(item.after().title()).isEqualTo("Scoped"));
    UUID otherTeam = UUID.randomUUID();
    UUID otherMission = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values (?,?,'Other','DRAFT',current_timestamp,current_timestamp)",
        otherTeam, org);
    jdbc.update(
        "insert into missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) values (?,?,?,'Other','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        otherMission, org, otherTeam);
    var state = businessState();
    mvc.perform(get((url() + "/history").replace(team.toString(), otherTeam.toString())).with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(get((url() + "/history").replace(team.toString(), otherTeam.toString()).replace(mission.toString(), otherMission.toString())).with(auth()))
        .andExpect(status().isNotFound());
    UUID foreignOrg = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Foreign',?,'ACTIVE',current_timestamp,current_timestamp)",
        foreignOrg, foreignOrg.toString());
    mvc.perform(get((url() + "/history").replace(org.toString(), foreignOrg.toString())).with(auth()))
        .andExpect(status().isForbidden());
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", actor, foreignOrg);
    mvc.perform(get((url() + "/history").replace(org.toString(), foreignOrg.toString())).with(auth()))
        .andExpect(status().isNotFound());
    assertThat(businessState()).isEqualTo(state);
  }

  @Test
  void historyDeclaresReadOnlyRepeatableReadTransaction() throws Exception {
    var transaction = TaskDraftEditService.class
        .getMethod("history", UUID.class, UUID.class, UUID.class, UUID.class, HumanIdentity.class, int.class)
        .getAnnotation(Transactional.class);
    assertThat(transaction.readOnly()).isTrue();
    assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
  }

  @Test
  void historyRequiresFreshAuthorityBeforeExactScopeWithoutBusinessWrites() throws Exception {
    save(UUID.randomUUID(), command(context(), "Private"));
    var state = businessState();
    for (UUID wrong : List.of(team, mission, task)) {
      mvc.perform(get((url() + "/history").replace(wrong.toString(), UUID.randomUUID().toString())).with(auth()))
          .andExpect(status().isNotFound());
    }
    mvc.perform(get((url() + "/history").replace(org.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isForbidden());
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=? and organization_id=?", actor, org);
    mvc.perform(get(url() + "/history").with(auth())).andExpect(status().isForbidden());
    mvc.perform(get((url() + "/history").replace(task.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isForbidden());
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=? and organization_id=?", actor, org);
    jdbc.update("update human_accounts set enabled=false where id=?", actor);
    mvc.perform(get(url() + "/history").with(auth())).andExpect(status().isForbidden());
    jdbc.update("update human_accounts set enabled=true,credential_version=credential_version+1 where id=?", actor);
    mvc.perform(get(url() + "/history").with(auth())).andExpect(status().isForbidden());
    assertThat(businessState()).isEqualTo(state);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "00", "01", "-1", "+1", " 1", "1 ", "1.0", "1e0", "100001", "2147483648", "١"})
  void historyRejectsNoncanonicalPagesWithoutWrites(String page) throws Exception {
    var state = businessState();
    mvc.perform(get(url() + "/history").with(auth()).queryParam("page", page)).andExpect(status().isBadRequest());
    assertThat(businessState()).isEqualTo(state);
  }

  @Test
  void historyRejectsUnknownDuplicateQueriesBodiesAndNoncanonicalIds() throws Exception {
    var state = businessState();
    mvc.perform(get(url() + "/history").with(auth()).queryParam("page", "0", "1")).andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/history").with(auth()).queryParam("pageSize", "20")).andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/history").with(auth()).content("{}")).andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/history").with(auth()).header("Transfer-Encoding", "chunked")).andExpect(status().isBadRequest());
    for (UUID id : List.of(org, team, mission, task)) {
      mvc.perform(get((url() + "/history").replace(id.toString(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")).with(auth()))
          .andExpect(status().isBadRequest());
    }
    assertThat(businessState()).isEqualTo(state);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "{}", "[]",
      "{\"title\":null,\"description\":null,\"acceptanceCriteria\":null,\"priority\":null}",
      "{\"title\":\"Legacy\",\"description\":null,\"acceptanceCriteria\":null,\"priority\":null,\"unknown\":1}", "{\"title\":1,\"description\":null,\"acceptanceCriteria\":null,\"priority\":null}",
      "{\"title\":\"A\",\"title\":\"B\",\"description\":null,\"acceptanceCriteria\":null,\"priority\":null}"})
  void historyFailsClosedOnMalformedStoredSnapshots(String malformed) {
    save(UUID.randomUUID(), command(context(), "Private"));
    for (String column : List.of("before_json", "after_json")) {
      var original = jdbc.queryForObject("select " + column + " from task_edit_events where task_id=?", String.class, task);
      jdbc.update("update task_edit_events set " + column + "=? where task_id=?", malformed, task);
      var state = businessState();
      assertThatThrownBy(() -> history(0)).isInstanceOf(IllegalStateException.class);
      assertThat(businessState()).isEqualTo(state);
      jdbc.update("update task_edit_events set " + column + "=? where task_id=?", original, task);
    }
  }

  @Test
  void historyRejectsNoopEventsAndInvalidCurrentActorWithoutWrites() {
    save(UUID.randomUUID(), command(context(), "Private"));
    String original = jdbc.queryForObject(
        "select before_json from task_edit_events where task_id=?", String.class, task);
    jdbc.update("update task_edit_events set before_json=after_json where task_id=?", task);
    var noopState = businessState();
    assertThatThrownBy(() -> history(0)).isInstanceOf(IllegalStateException.class);
    assertThat(businessState()).isEqualTo(noopState);
    jdbc.update("update task_edit_events set before_json=? where task_id=?", original, task);
    for (String name : List.of("", " ", "\u00a0", "Unsafe\nname")) {
      jdbc.update("update human_accounts set display_name=? where id=?", name, actor);
      var state = businessState();
      assertThatThrownBy(() -> history(0)).isInstanceOf(IllegalStateException.class);
      assertThat(businessState()).isEqualTo(state);
    }
  }

  @Test
  void historyAcceptsRawLegacyBeforeButRejectsIncompleteOrNoncanonicalAfter() throws Exception {
    save(UUID.randomUUID(), command(context(), "Private"));
    var legacy = new TaskDraftEditDtos.Values("  ", "", null, "  Historical label  ");
    jdbc.update("update task_edit_events set before_json=? where task_id=?", json.writeValueAsString(legacy), task);
    assertThat(history(0).items().get(0).before()).isEqualTo(legacy);
    for (var invalid : List.of(
        new TaskDraftEditDtos.Values("Private", null, "Criteria", "HIGH"),
        new TaskDraftEditDtos.Values("Private", " ", "Criteria", "HIGH"),
        new TaskDraftEditDtos.Values(" Private ", "Description", "Criteria", "HIGH"),
        new TaskDraftEditDtos.Values("Private", "Description", "Criteria", "MEDIUM"))) {
      jdbc.update("update task_edit_events set after_json=? where task_id=?", json.writeValueAsString(invalid), task);
      var state = businessState();
      assertThatThrownBy(() -> history(0)).isInstanceOf(IllegalStateException.class);
      assertThat(businessState()).isEqualTo(state);
    }
  }

  @Test
  void savesOnlyFourFieldsRevisionTimestampAndSafeScopedHistory() throws Exception {
    var before = row("tasks", task);
    var missionBefore = row("missions", mission);
    Context source = context();
    assertThat(source.values().description()).isNull();
    assertThat(source.values().priority()).isNull();
    Receipt r = save(UUID.randomUUID(), command(source, "Saved")).receipt();
    assertThat(r.before()).isEqualTo(source.values());
    assertThat(r.after()).isEqualTo(context().values());
    assertThat(r.revision()).isEqualTo(1);
    var after = row("tasks", task);
    for (String field :
        List.of(
            "title",
            "description",
            "acceptance_criteria",
            "priority",
            "updated_at",
            "task_edit_revision")) {
      before.remove(field);
      after.remove(field);
    }
    assertThat(after).isEqualTo(before);
    assertThat(row("missions", mission)).isEqualTo(missionBefore);
    assertThat(count("task_edit_events")).isEqualTo(1);
    assertThat(count("task_edit_receipts")).isEqualTo(1);
    var audit = jdbc.queryForMap("select * from audit_events where organization_id=?", org);
    assertThat(audit.get("mission_id")).isEqualTo(mission);
    assertThat(audit.get("task_id")).isEqualTo(task);
    assertThat(json.readTree((String) audit.get("payload")).size()).isEqualTo(2);
    assertThat((String) audit.get("payload")).doesNotContain("Saved", "Description", "Criteria");
    assertThat(json.valueToTree(source).size()).isEqualTo(13);
    assertThat(json.valueToTree(r).size()).isEqualTo(9);
  }

  @Test
  void historicalReplayAndLookupRemainExactAfterLifecycleChanges() {
    UUID key = UUID.randomUUID();
    Command c = command(context(), "First");
    var first = save(key, c);
    save(UUID.randomUUID(), command(context(), "Second"));
    jdbc.update("update tasks set status='READY' where id=?", task);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    var snapshot = row("tasks", task);
    assertThat(save(key, c).receipt()).isEqualTo(first.receipt());
    assertThat(save(key, c).replay()).isTrue();
    assertThat(service.receipt(org, team, mission, task, human, key)).isEqualTo(first.receipt());
    assertThat(row("tasks", task)).isEqualTo(snapshot);
    assertThat(count("task_edit_events")).isEqualTo(2);
    assertThatThrownBy(() -> save(key, command(context(), "Other")))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    assertThatThrownBy(() -> save(key, c)).hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> service.receipt(org, team, mission, task, human, key))
        .hasMessage("FORBIDDEN");
  }

  @Test
  void noopStaleAndMaxRevisionProduceNoFurtherWrites() {
    save(UUID.randomUUID(), command(context(), "Saved"));
    Context source = context();
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(source, "Saved")))
        .hasMessage("TASK_EDIT_NO_CHANGE");
    jdbc.update(
        "update missions set task_intake_version=task_intake_version+1 where id=?", mission);
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(source, "Stale")))
        .hasMessage("TASK_EDIT_CONFLICT");
    jdbc.update("update tasks set task_edit_revision=2147483647 where id=?", task);
    assertThat(context().blockers()).containsExactly("REVISION_EXHAUSTED");
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(context(), "Overflow")))
        .hasMessage("TASK_EDIT_BLOCKED");
    assertThat(count("task_edit_events")).isEqualTo(1);
    assertThat(count("task_edit_receipts")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"TASK", "TEAM", "MISSION", "PAUSE"})
  void currentStateBlocksWritesWithoutEffects(String kind) {
    switch (kind) {
      case "TASK" -> jdbc.update("update tasks set status='READY' where id=?", task);
      case "TEAM" -> jdbc.update("update teams set status='ARCHIVED' where id=?", team);
      case "MISSION" -> jdbc.update("update missions set status='PLANNING' where id=?", mission);
      case "PAUSE" ->
          jdbc.update("update missions set task_intake_paused=true where id=?", mission);
      default -> throw new AssertionError();
    }
    assertThat(context().canEdit()).isFalse();
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(context(), "Blocked")))
        .hasMessage("TASK_EDIT_BLOCKED");
    assertThat(count("task_edit_events")).isZero();
  }

  @Test
  void fullFingerprintCoversOwnerDependenciesAndTimestampWithoutLeakingThem() throws Exception {
    Context initial = context();
    UUID owner = UUID.randomUUID(), dependency = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values"
            + " (?,?,?,'Private','Role','MID','DRAFT','SUPERVISED',current_timestamp,current_timestamp)",
        owner,
        org,
        team);
    jdbc.update("update tasks set owner_agent_id=? where id=?", owner, task);
    Context owned = context();
    assertThat(owned.fingerprint()).isNotEqualTo(initial.fingerprint());
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
            + " (?,?,?,?,'Private dependency','DRAFT')",
        dependency,
        org,
        team,
        mission);
    jdbc.update(
        "insert into task_dependencies values (?,?,?,?,?)", org, team, mission, task, dependency);
    Context linked = context();
    assertThat(linked.fingerprint()).isNotEqualTo(owned.fingerprint());
    assertThat(json.writeValueAsString(linked))
        .doesNotContain(owner.toString(), dependency.toString(), "Private");
    var edges = jdbc.queryForList("select * from task_dependencies where task_id=?", task);
    save(UUID.randomUUID(), command(linked, "Changed"));
    assertThat(jdbc.queryForList("select * from task_dependencies where task_id=?", task))
        .isEqualTo(edges);
    assertThat(row("tasks", task).get("owner_agent_id")).isEqualTo(owner);
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(initial, "Stale")))
        .hasMessage("TASK_EDIT_CONFLICT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"audit_events", "task_edit_events", "task_edit_receipts"})
  void anyProvenanceFailureRollsBackEverything(String table) {
    Context source = context();
    String constraint = "task_edit_test_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table "
            + table
            + " add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> save(UUID.randomUUID(), command(source, "Rollback")))
          .isInstanceOf(DataAccessException.class);
      assertThat(context().fingerprint()).isEqualTo(source.fingerprint());
      assertThat(count("task_edit_events")).isZero();
      assertThat(count("task_edit_receipts")).isZero();
      assertThat(count("audit_events")).isZero();
    } finally {
      jdbc.execute("alter table " + table + " drop constraint " + constraint);
    }
  }

  @Test
  void freshAuthorityAndScopeRequiredAndForeignActorReceiptIsNull() {
    UUID key = UUID.randomUUID();
    save(key, command(context(), "Saved"));
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Other','unused')",
        other,
        other + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", other, org);
    assertThat(
            service.receipt(
                org,
                team,
                mission,
                task,
                new HumanIdentity(other, "other@example.test", "Other"),
                key))
        .isNull();
    assertThatThrownBy(() -> service.context(org, UUID.randomUUID(), mission, task, human))
        .hasMessage("NOT_FOUND");
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", actor);
    assertThatThrownBy(this::context).hasMessage("FORBIDDEN");
    human = new HumanIdentity(actor, human.email(), "Actor", 1);
    jdbc.update("update human_accounts set enabled=false where id=?", actor);
    assertThatThrownBy(this::context).hasMessage("FORBIDDEN");
  }

  @Test
  void httpRequiresCsrfExactSchemasAndReturnsWrappedNullableReceipt() throws Exception {
    String body = json.writeValueAsString(command(context(), "Saved"));
    UUID key = UUID.randomUUID();
    mvc.perform(
            post(url())
                .with(auth())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(get(url()).with(auth()).queryParam("x", "1")).andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/receipts/" + key).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.receipt").value(nullValue()));
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.revision").value(1));
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body.replace("Saved", "  Saved  ")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value(1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"application/json; charset=ISO-8859-1", "text/plain", "application/problem+json"})
  void invalidMediaTypesDoNotWrite(String type) throws Exception {
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(type)
                .content(json.writeValueAsString(command(context(), "Saved"))))
        .andExpect(status().isBadRequest());
    assertThat(count("task_edit_events")).isZero();
  }

  @Test
  void invalidWireHeadersAndForeignOrMemberScopeNeverWrite() throws Exception {
    String body = json.writeValueAsString(command(context(), "Saved"));
    for (String malformed :
        List.of(
            body.replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""),
            body.replace("\"title\":", "\"unexpected\":true,\"title\":"),
            body.replace("\"title\":", "\"title\":\"Duplicate\",\"title\":"))) {
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf().asHeader())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(malformed))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID(), UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url().replace(task.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isNotFound());
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
    mvc.perform(get(url() + "/receipts/" + UUID.randomUUID()).with(auth()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    assertThat(count("task_edit_events")).isZero();
    assertThat(count("task_edit_receipts")).isZero();
    assertThat(count("audit_events")).isZero();
  }
}

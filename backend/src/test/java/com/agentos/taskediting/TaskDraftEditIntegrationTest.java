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

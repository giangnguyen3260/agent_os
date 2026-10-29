package com.agentos.taskowner;

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
import com.agentos.taskowner.TaskOwnerDtos.Command;
import com.agentos.taskowner.TaskOwnerDtos.Context;
import com.agentos.taskowner.TaskOwnerDtos.Result;
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
        "spring.datasource.url=jdbc:h2:mem:task_owner_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskOwnerIntegrationTest {
  @Autowired TaskOwnerService service;
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
                + " 'com.agentos.taskowner.TaskOwnerIntegrationTest.clock'");
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

  UUID agent(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) values (?,?,?,?,'Role','MID','DRAFT','SUPERVISED',current_timestamp,current_timestamp)",
        id,
        org,
        team,
        name);
    return id;
  }

  Command command(Context source, UUID target) {
    return new Command(
        source.revision(),
        source.fingerprint(),
        target,
        target == null
            ? null
            : service.candidate(org, team, mission, task, human, target).fingerprint());
  }

  Result save(UUID key, Command command) {
    return service.update(org, team, mission, task, human, key, command);
  }

  @Test
  void assignChangeClearProtectsEveryOtherColumnAndTable() throws Exception {
    UUID one = agent("One"), two = agent("Two");
    var before = row("tasks", task);
    var parent = row("missions", mission);
    var teamBefore = row("teams", team);
    var agentBefore = row("agents", one);
    var secondAgentBefore = row("agents", two);
    jdbc.update(
        "insert into task_create_receipts values (?,?,?,?,?)",
        org,
        actor,
        UUID.randomUUID(),
        "a".repeat(64),
        "retained creation receipt");
    var oldReceipts =
        jdbc.queryForList("select * from task_create_receipts where organization_id=?", org);
    UUID dependency = UUID.randomUUID();
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values (?,?,?,?,'Dependency','DRAFT')",
        dependency,
        org,
        team,
        mission);
    jdbc.update(
        "insert into task_dependencies values (?,?,?,?,?)", org, team, mission, task, dependency);
    var edges = jdbc.queryForList("select * from task_dependencies where task_id=?", task);
    var first = save(UUID.randomUUID(), command(context(), one)).receipt();
    assertThat(first.before().state()).isEqualTo("UNASSIGNED");
    assertThat(first.after().agent().id()).isEqualTo(one);
    save(UUID.randomUUID(), command(context(), two));
    var clear = save(UUID.randomUUID(), command(context(), null)).receipt();
    assertThat(clear.before().agent().id()).isEqualTo(two);
    assertThat(clear.after().state()).isEqualTo("UNASSIGNED");
    assertThat(clear.after().agent()).isNull();
    assertThat(clear.revision()).isEqualTo(3);
    var after = row("tasks", task);
    for (String column : List.of("owner_agent_id", "task_owner_revision", "updated_at")) {
      before.remove(column);
      after.remove(column);
    }
    assertThat(after).isEqualTo(before);
    assertThat(row("missions", mission)).isEqualTo(parent);
    assertThat(row("teams", team)).isEqualTo(teamBefore);
    assertThat(row("agents", one)).isEqualTo(agentBefore);
    assertThat(row("agents", two)).isEqualTo(secondAgentBefore);
    assertThat(jdbc.queryForList("select * from task_create_receipts where organization_id=?", org))
        .isEqualTo(oldReceipts);
    assertThat(jdbc.queryForList("select * from task_dependencies where task_id=?", task))
        .isEqualTo(edges);
    assertThat(count("task_edit_events")).isZero();
    assertThat(count("task_edit_receipts")).isZero();
    assertThat(count("task_owner_events")).isEqualTo(3);
    assertThat(count("task_owner_receipts")).isEqualTo(3);
    for (var audit : jdbc.queryForList("select * from audit_events where organization_id=?", org)) {
      assertThat(audit.get("mission_id")).isEqualTo(mission);
      assertThat(audit.get("task_id")).isEqualTo(task);
      var fields = new java.util.HashSet<String>();
      json.readTree((String) audit.get("payload")).fieldNames().forEachRemaining(fields::add);
      assertThat(fields).containsExactlyInAnyOrder("eventId", "revision");
    }
    assertThat(json.valueToTree(context()).size()).isEqualTo(14);
    assertThat(json.valueToTree(clear).size()).isEqualTo(9);
  }

  @Test
  void unavailableClearUsesRawPointerAndRetainsInternalProvenance() throws Exception {
    UUID old = agent("Private moved name");
    jdbc.update("update tasks set owner_agent_id=? where id=?", old, task);
    jdbc.update("update agents set team_id=null where id=?", old);
    var source = context();
    assertThat(source.owner().state()).isEqualTo("UNAVAILABLE");
    assertThat(source.owner().agent()).isNull();
    assertThat(json.writeValueAsString(source))
        .doesNotContain(old.toString(), "Private moved name");
    var key = UUID.randomUUID();
    var clear = command(source, null);
    var receipt = save(key, clear).receipt();
    assertThat(service.receipt(org, team, mission, task, human, key)).isEqualTo(receipt);
    assertThat(save(key, clear).receipt()).isEqualTo(receipt);
    assertThat(receipt.before().state()).isEqualTo("UNAVAILABLE");
    assertThat(receipt.after().state()).isEqualTo("UNASSIGNED");
    var event = jdbc.queryForMap("select * from task_owner_events where organization_id=?", org);
    assertThat(event.get("before_owner_agent_id")).isEqualTo(old);
    assertThat(event.get("after_owner_agent_id")).isNull();
    assertThat(json.writeValueAsString(receipt))
        .doesNotContain(old.toString(), "Private moved name");
  }

  @Test
  void currentIneligibleOwnerRemainsVisibleButCanBeReplaced() {
    UUID old = agent("Existing"), replacement = agent("Replacement");
    jdbc.update("update tasks set owner_agent_id=? where id=?", old, task);
    jdbc.update("update agents set status='ARCHIVED' where id=?", old);
    assertThat(context().owner().agent().id()).isEqualTo(old);
    assertThat(context().owner().agent().status()).isEqualTo("ARCHIVED");
    assertThatThrownBy(() -> service.candidate(org, team, mission, task, human, old))
        .hasMessage("TASK_OWNER_NOT_ELIGIBLE");
    save(UUID.randomUUID(), command(context(), replacement));
    assertThat(row("agents", old).get("status")).isEqualTo("ARCHIVED");
  }

  @Test
  void paginationLiteralSearchAndCandidatePrivacy() {
    for (int i = 0; i < 23; i++) agent("Same");
    UUID literal = agent("100%_literal");
    var first = service.owners(org, team, mission, task, human, 0, "");
    var second = service.owners(org, team, mission, task, human, 1, "");
    assertThat(first.total()).isEqualTo(24);
    assertThat(first.pageSize()).isEqualTo(20);
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(4);
    assertThat(
            java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                .map(TaskOwnerDtos.Candidate::id)
                .distinct()
                .count())
        .isEqualTo(24);
    assertThat(service.owners(org, team, mission, task, human, 0, "  %_  ").items())
        .extracting(TaskOwnerDtos.Candidate::id)
        .containsExactly(literal);
    assertThat(service.owners(org, team, mission, task, human, 0, "SAME").total()).isEqualTo(23);
  }

  @Test
  void historicalReplayExactIdentitySurvivesAgentMoveAndLifecycleButRequiresAuthority() {
    UUID target = agent("Original"), key = UUID.randomUUID();
    var command = command(context(), target);
    var first = save(key, command);
    save(UUID.randomUUID(), command(context(), null));
    jdbc.update("update agents set team_id=null,name='Changed' where id=?", target);
    jdbc.update("update tasks set status='READY' where id=?", task);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    assertThat(save(key, command).replay()).isTrue();
    assertThat(save(key, command).receipt()).isEqualTo(first.receipt());
    assertThat(service.receipt(org, team, mission, task, human, key)).isEqualTo(first.receipt());
    assertThatThrownBy(
            () ->
                save(
                    key,
                    new Command(
                        command.expectedRevision(), command.expectedFingerprint(), null, null)))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and account_id=?",
        org,
        actor);
    assertThatThrownBy(() -> save(key, command)).hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> service.receipt(org, team, mission, task, human, key))
        .hasMessage("FORBIDDEN");
  }

  @Test
  void noOpSourceCandidateConflictsAndIndependentRevisionExhaustion() {
    UUID target = agent("Target");
    var initial = context();
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(initial, null)))
        .hasMessage("TASK_OWNER_NO_CHANGE");
    var selected = command(initial, target);
    jdbc.update("update agents set name='Renamed' where id=?", target);
    assertThatThrownBy(() -> save(UUID.randomUUID(), selected))
        .hasMessage("TASK_OWNER_CANDIDATE_CONFLICT");
    var stale = command(context(), target);
    jdbc.update(
        "update missions set task_intake_version=task_intake_version+1 where id=?", mission);
    assertThatThrownBy(() -> save(UUID.randomUUID(), stale)).hasMessage("TASK_OWNER_CONFLICT");
    jdbc.update("update tasks set task_edit_revision=2147483647 where id=?", task);
    save(UUID.randomUUID(), command(context(), target));
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(context(), target)))
        .hasMessage("TASK_OWNER_NO_CHANGE");
    jdbc.update("update tasks set task_owner_revision=2147483647 where id=?", task);
    assertThat(context().blockers()).contains("REVISION_EXHAUSTED");
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(context(), null)))
        .hasMessage("TASK_OWNER_BLOCKED");
    assertThat(count("task_owner_events")).isEqualTo(1);
  }

  @Autowired com.agentos.taskediting.TaskDraftEditService draftEdit;

  @Test
  void reciprocalAbaRevisionsInvalidateTokensEvenWithTimestampRestored() {
    UUID target = agent("Target");
    var timestamp = row("tasks", task).get("updated_at");
    var edit = draftEdit.context(org, team, mission, task, human);
    save(UUID.randomUUID(), command(context(), target));
    save(UUID.randomUUID(), command(context(), null));
    jdbc.update("update tasks set updated_at=? where id=?", timestamp, task);
    assertThat(draftEdit.context(org, team, mission, task, human).fingerprint())
        .isNotEqualTo(edit.fingerprint());
    assertThatThrownBy(
            () ->
                draftEdit.update(
                    org,
                    team,
                    mission,
                    task,
                    human,
                    UUID.randomUUID(),
                    new com.agentos.taskediting.TaskDraftEditDtos.Command(
                        edit.revision(),
                        edit.fingerprint(),
                        "Changed",
                        "Description",
                        "Criteria",
                        "HIGH")))
        .hasMessage("TASK_EDIT_CONFLICT");
    var owner = context();
    var original = row("tasks", task);
    for (String title : List.of("Changed", "Legacy")) {
      var current = draftEdit.context(org, team, mission, task, human);
      draftEdit.update(
          org,
          team,
          mission,
          task,
          human,
          UUID.randomUUID(),
          new com.agentos.taskediting.TaskDraftEditDtos.Command(
              current.revision(), current.fingerprint(), title, "Description", "Criteria", "HIGH"));
    }
    jdbc.update(
        "update tasks set title=?,description=?,acceptance_criteria=?,priority=?,updated_at=? where id=?",
        original.get("title"),
        original.get("description"),
        original.get("acceptance_criteria"),
        original.get("priority"),
        original.get("updated_at"),
        task);
    assertThat(context().fingerprint()).isNotEqualTo(owner.fingerprint());
    assertThatThrownBy(() -> save(UUID.randomUUID(), command(owner, target)))
        .hasMessage("TASK_OWNER_CONFLICT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"audit_events", "task_owner_events", "task_owner_receipts"})
  void provenanceFailureRollsBackEveryEffect(String table) {
    UUID target = agent("Target");
    var source = context();
    var before = row("tasks", task);
    String constraint = "owner_test_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table "
            + table
            + " add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> save(UUID.randomUUID(), command(source, target)))
          .isInstanceOf(DataAccessException.class);
      assertThat(row("tasks", task)).isEqualTo(before);
      assertThat(context().fingerprint()).isEqualTo(source.fingerprint());
      assertThat(count("task_owner_events")).isZero();
      assertThat(count("task_owner_receipts")).isZero();
      assertThat(count("audit_events")).isZero();
    } finally {
      jdbc.execute("alter table " + table + " drop constraint " + constraint);
    }
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
        + "/draft-owner";
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  Map<String, Object> row(String table, UUID id) {
    return jdbc.queryForMap("select * from " + table + " where id=?", id);
  }

  List<Object> businessState() {
    return List.of(
        row("tasks", task),
        row("missions", mission),
        row("teams", team),
        jdbc.queryForList(
            "select * from task_edit_events where organization_id=? order by revision", org),
        jdbc.queryForList(
            "select * from task_edit_receipts where organization_id=? order by event_id", org),
        jdbc.queryForList("select * from audit_events where organization_id=? order by id", org));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "00", "01", "-1", "+1", " 1", "1 ", "1.0", "1e0", "100001", "2147483648", "١"})
  void noncanonicalPagesRejected(String page) throws Exception {
    mvc.perform(get(url() + "/eligible-owners").with(auth()).queryParam("page", page))
        .andExpect(status().isBadRequest());
    assertThat(count("task_owner_events")).isZero();
  }

  @Test
  void httpExactShapesCsrfAndHistoricalResponseBytes() throws Exception {
    UUID target = agent("Target"), key = UUID.randomUUID();
    String body = json.writeValueAsString(command(context(), target));
    mvc.perform(get(url()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.owner.agent").value(nullValue()));
    mvc.perform(get(url() + "/receipts/" + key).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.receipt").value(nullValue()));
    mvc.perform(
            post(url())
                .with(auth())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    var first =
        mvc.perform(
                post(url())
                    .with(auth())
                    .with(csrf().asHeader())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    var expected = json.readTree(first.getResponse().getContentAsByteArray()).get("data");
    assertThat(expected.size()).isEqualTo(9);
    assertThat(expected.get("after").size()).isEqualTo(2);
    assertThat(expected.get("after").get("agent").size()).isEqualTo(4);
    jdbc.update("update agents set name='Changed',team_id=null where id=?", target);
    var replay =
        mvc.perform(
                post(url())
                    .with(auth())
                    .with(csrf().asHeader())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(replay.getResponse().getContentAsByteArray()).get("data"))
        .isEqualTo(expected);
    var lookup =
        mvc.perform(get(url() + "/receipts/" + key).with(auth()))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            json.readTree(lookup.getResponse().getContentAsByteArray()).get("data").get("receipt"))
        .isEqualTo(expected);
  }

  @Test
  void strictWireRejectsTypesDuplicatesTrailingMalformedUtf8HeadersAndQueries() throws Exception {
    String body = json.writeValueAsString(command(context(), agent("Target")));
    for (String invalid :
        List.of(
            body + " {}",
            body.replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""),
            body.replace("\"expectedRevision\":0", "\"expectedRevision\":0.0"),
            body.replace("\"expectedRevision\":0", "\"expectedRevision\":2147483648"),
            body.replace("\"expectedRevision\":0", "\"expectedRevision\":0,\"expectedRevision\":0"),
            body.replace("{", "{\"extra\":true,"),
            " ".repeat(4097),
            "{}")) {
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf().asHeader())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(new byte[] {(byte) 0xc3, (byte) 0x28}))
        .andExpect(status().isBadRequest());
    for (String type :
        List.of("text/plain", "application/problem+json", "application/json; charset=ISO-8859-1"))
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf().asHeader())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType(type)
                  .content(body))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID(), UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
    for (String suffix :
        List.of("", "/receipts/" + UUID.randomUUID(), "/eligible-owners/" + agent("Other"))) {
      mvc.perform(get(url() + suffix).with(auth()).queryParam("x", "1"))
          .andExpect(status().isBadRequest());
      mvc.perform(get(url() + suffix).with(auth()).content("{}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get(url() + "/eligible-owners").with(auth()).queryParam("page", "0", "1"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/eligible-owners").with(auth()).queryParam("query", "a", "b"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/eligible-owners").with(auth()).queryParam("query", "a\nb"))
        .andExpect(status().isBadRequest());
    assertThat(count("task_owner_events")).isZero();
    assertThat(count("audit_events")).isZero();
  }

  @Test
  void currentAuthorityPrecedesParsingAndScopeErrorsNeverWrite() throws Exception {
    mvc.perform(get(url().replace(task.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(url().replace(org.toString(), UUID.randomUUID().toString()))
                .with(auth())
                .with(csrf().asHeader())
                .contentType("application/json")
                .content("malformed"))
        .andExpect(status().isForbidden());
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and account_id=?",
        org,
        actor);
    for (String suffix :
        List.of("", "/eligible-owners", "/eligible-owners/not-a-uuid", "/receipts/not-a-uuid"))
      mvc.perform(get(url() + suffix).with(auth()).queryParam("unknown", "x"))
          .andExpect(status().isForbidden());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .contentType("application/json")
                .content("malformed"))
        .andExpect(status().isForbidden());
    jdbc.update(
        "update organization_memberships set role='ADMIN' where organization_id=? and account_id=?",
        org,
        actor);
    jdbc.update("update human_accounts set enabled=false where id=?", actor);
    mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
    jdbc.update(
        "update human_accounts set enabled=true,credential_version=credential_version+1 where id=?",
        actor);
    mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
    assertThat(count("task_owner_events")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"TASK", "TEAM", "MISSION", "PAUSE"})
  void mutableGatesBlockCandidatesAndFreshWrites(String gate) {
    UUID target = agent("Target");
    var command = command(context(), target);
    switch (gate) {
      case "TASK" -> jdbc.update("update tasks set status='READY' where id=?", task);
      case "TEAM" -> jdbc.update("update teams set status='ARCHIVED' where id=?", team);
      case "MISSION" -> jdbc.update("update missions set status='PLANNING' where id=?", mission);
      default -> jdbc.update("update missions set task_intake_paused=true where id=?", mission);
    }
    assertThat(context().canEdit()).isFalse();
    assertThatThrownBy(() -> save(UUID.randomUUID(), command)).hasMessage("TASK_OWNER_BLOCKED");
    assertThatThrownBy(() -> service.owners(org, team, mission, task, human, 0, ""))
        .hasMessage("TASK_OWNER_BLOCKED");
    assertThatThrownBy(() -> service.candidate(org, team, mission, task, human, target))
        .hasMessage("TASK_OWNER_BLOCKED");
    assertThat(count("task_owner_events")).isZero();
  }

  @Test
  void foreignAndWrongTeamTargetsNeverResolveAndReceiptsAreOwnActorOnly() {
    UUID target = agent("Target"), key = UUID.randomUUID();
    save(key, command(context(), target));
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Other','unused')",
        other,
        other + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", other, org);
    assertThat(
            service.receipt(
                org,
                team,
                mission,
                task,
                new HumanIdentity(other, other + "@example.test", "Other"),
                key))
        .isNull();
    UUID otherTeam = UUID.randomUUID(), foreignOrg = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values (?,?,'Other team','DRAFT',current_timestamp,current_timestamp)",
        otherTeam,
        org);
    UUID moved = agent("Private wrong team");
    jdbc.update("update agents set team_id=? where id=?", otherTeam, moved);
    assertThatThrownBy(() -> service.candidate(org, team, mission, task, human, moved))
        .hasMessage("NOT_FOUND");
    assertThatThrownBy(
            () ->
                save(
                    UUID.randomUUID(),
                    new Command(
                        context().revision(), context().fingerprint(), moved, "a".repeat(64))))
        .hasMessage("TASK_OWNER_NOT_ELIGIBLE");
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Foreign',?,'ACTIVE',current_timestamp,current_timestamp)",
        foreignOrg,
        foreignOrg.toString());
    UUID foreign = agent("Private foreign name");
    jdbc.update("update agents set organization_id=?,team_id=null where id=?", foreignOrg, foreign);
    assertThatThrownBy(() -> service.candidate(org, team, mission, task, human, foreign))
        .hasMessage("NOT_FOUND");
    assertThatThrownBy(
            () ->
                save(
                    UUID.randomUUID(),
                    new Command(
                        context().revision(), context().fingerprint(), foreign, "a".repeat(64))))
        .hasMessage("TASK_OWNER_NOT_ELIGIBLE");
    for (UUID id : List.of(team, mission, task)) {
      UUID wrong = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  service.context(
                      org,
                      id.equals(team) ? wrong : team,
                      id.equals(mission) ? wrong : mission,
                      id.equals(task) ? wrong : task,
                      human))
          .hasMessage("NOT_FOUND");
    }
    assertThat(count("task_owner_events")).isEqualTo(1);
  }

  @Test
  void unavailableOwnerCanBeReplacedAndParentCountersDoNotExhaustOwnerRevision() {
    UUID old = agent("Old"), replacement = agent("Replacement");
    jdbc.update(
        "update tasks set owner_agent_id=?,task_edit_revision=2147483647 where id=?", old, task);
    jdbc.update("update agents set team_id=null where id=?", old);
    jdbc.update("update missions set task_intake_version=9223372036854775807 where id=?", mission);
    var before = row("missions", mission);
    var receipt = save(UUID.randomUUID(), command(context(), replacement)).receipt();
    assertThat(receipt.before().state()).isEqualTo("UNAVAILABLE");
    assertThat(receipt.after().agent().id()).isEqualTo(replacement);
    assertThat(row("missions", mission)).isEqualTo(before);
    assertThat(context().revision()).isEqualTo(1);
  }

  @Test
  void malformedPersistedReceiptFailsClosedWithoutMutation() {
    UUID key = UUID.randomUUID();
    var command = command(context(), agent("Target"));
    save(key, command);
    jdbc.update(
        "update task_owner_receipts set original_response=? where organization_id=? and idempotency_key=?",
        "{private-invalid",
        org,
        key);
    var state = row("tasks", task);
    assertThatThrownBy(() -> save(key, command)).hasMessage("TASK_OWNER_UNAVAILABLE");
    assertThatThrownBy(() -> service.receipt(org, team, mission, task, human, key))
        .hasMessage("TASK_OWNER_UNAVAILABLE");
    assertThat(row("tasks", task)).isEqualTo(state);
    assertThat(count("task_owner_events")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unassignedNoop",
        "assignedNoop",
        "revision",
        "time",
        "offsetTime",
        "uppercaseId",
        "shortId",
        "uuidVersion",
        "agentId",
        "name",
        "missingOwner",
        "nullRevision",
        "eventBefore",
        "eventAfter",
        "rawBefore",
        "rawAfter",
        "eventTime",
        "receiptTime"
      })
  void semanticReceiptCorruptionFailsLookupAndReplayWithoutEffects(String corruption)
      throws Exception {
    UUID target = agent("Historical"), key = UUID.randomUUID();
    var command = command(context(), target);
    var receipt = save(key, command).receipt();
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(receipt);
    switch (corruption) {
      case "unassignedNoop" -> tree.set("after", tree.get("before").deepCopy());
      case "assignedNoop" -> tree.set("before", tree.get("after").deepCopy());
      case "revision" -> tree.put("revision", receipt.revision() + 1);
      case "time" -> tree.put("committedAt", "2000-01-01T00:00:00Z");
      case "offsetTime" -> tree.put("committedAt", receipt.committedAt().replace("Z", "+00:00"));
      case "uppercaseId" ->
          tree.put("eventId", receipt.eventId().toString().toUpperCase(java.util.Locale.ROOT));
      case "shortId" -> tree.put("eventId", "1-1-4000-8000-1");
      case "uuidVersion" -> tree.put("eventId", "00000000-0000-0000-8000-000000000001");
      case "agentId" ->
          ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("after").path("agent"))
              .put("id", UUID.randomUUID().toString());
      case "name" ->
          ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("after").path("agent"))
              .put("name", "Altered");
      case "missingOwner" -> tree.remove("before");
      case "nullRevision" -> tree.putNull("revision");
      case "eventBefore" ->
          jdbc.update(
              "update task_owner_events set before_json=after_json where id=?", receipt.eventId());
      case "eventAfter" ->
          jdbc.update(
              "update task_owner_events set after_json=before_json where id=?", receipt.eventId());
      case "rawBefore" ->
          jdbc.update(
              "update task_owner_events set before_owner_agent_id=after_owner_agent_id where id=?",
              receipt.eventId());
      case "rawAfter" ->
          jdbc.update(
              "update task_owner_events set after_owner_agent_id=null where id=?",
              receipt.eventId());
      case "eventTime" ->
          jdbc.update(
              "update task_owner_events set created_at=? where id=?",
              OffsetDateTime.parse("2000-01-01T00:00:00Z"),
              receipt.eventId());
      case "receiptTime" ->
          jdbc.update(
              "update task_owner_receipts set created_at=? where event_id=?",
              OffsetDateTime.parse("2000-01-01T00:00:00Z"),
              receipt.eventId());
      default -> throw new AssertionError(corruption);
    }
    jdbc.update(
        "update task_owner_receipts set original_response=? where event_id=?",
        json.writeValueAsString(tree),
        receipt.eventId());
    var before = row("tasks", task);
    assertThatThrownBy(() -> service.receipt(org, team, mission, task, human, key))
        .hasMessage("TASK_OWNER_UNAVAILABLE");
    assertThatThrownBy(() -> save(key, command)).hasMessage("TASK_OWNER_UNAVAILABLE");
    assertThat(row("tasks", task)).isEqualTo(before);
    assertThat(count("task_owner_events")).isEqualTo(1);
    assertThat(count("task_owner_receipts")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @Test
  void movedTargetPostIsGenericConflictWhileDetailIsNotFound() throws Exception {
    UUID target = agent("Private moved target");
    var selected = command(context(), target);
    jdbc.update("update agents set team_id=null where id=?", target);
    mvc.perform(get(url() + "/eligible-owners/" + target).with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(selected)))
        .andExpect(status().isConflict());
    assertThatThrownBy(() -> save(UUID.randomUUID(), selected))
        .hasMessage("TASK_OWNER_NOT_ELIGIBLE");
    assertThat(count("task_owner_events")).isZero();
  }

  @Test
  void candidateAndContextWireHaveExactSchemasAndCanonicalIdentifiers() throws Exception {
    UUID target = agent("Actual Agent");
    var contextReply = mvc.perform(get(url()).with(auth())).andExpect(status().isOk()).andReturn();
    var data = json.readTree(contextReply.getResponse().getContentAsByteArray()).get("data");
    var fields = new java.util.HashSet<String>();
    data.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "organizationId",
            "teamId",
            "missionId",
            "taskId",
            "checkedAt",
            "revision",
            "fingerprint",
            "teamName",
            "missionTitle",
            "taskTitle",
            "taskStatus",
            "owner",
            "canEdit",
            "blockers");
    java.time.Instant.parse(data.get("checkedAt").asText());
    assertThat(data.get("fingerprint").asText()).matches("[0-9a-f]{64}");
    var candidateReply =
        mvc.perform(get(url() + "/eligible-owners/" + target).with(auth()))
            .andExpect(status().isOk())
            .andReturn();
    var candidate = json.readTree(candidateReply.getResponse().getContentAsByteArray()).get("data");
    var candidateFields = new java.util.HashSet<String>();
    candidate.fieldNames().forEachRemaining(candidateFields::add);
    assertThat(candidateFields)
        .containsExactlyInAnyOrder("id", "name", "level", "status", "fingerprint");
    assertThat(candidate.get("name").asText()).isEqualTo("Actual Agent");
    assertThat(candidate.get("fingerprint").asText()).matches("[0-9a-f]{64}");
    for (UUID id : List.of(team, mission, task))
      mvc.perform(
              get(url().replace(id.toString(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))
                  .with(auth()))
          .andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/eligible-owners/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").with(auth()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/receipts/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").with(auth()))
        .andExpect(status().isBadRequest());
  }
}

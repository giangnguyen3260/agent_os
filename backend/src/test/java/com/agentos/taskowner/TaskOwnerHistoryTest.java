package com.agentos.taskowner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Isolation;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:task_owner_history;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskOwnerHistoryTest {
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
                + " 'com.agentos.taskowner.TaskOwnerHistoryTest.clock'");
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

  String url() {
    return "/api/v1/organizations/"
        + org
        + "/teams/"
        + team
        + "/missions/"
        + mission
        + "/tasks/"
        + task
        + "/draft-owner/history";
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  TaskOwnerDtos.History history(int page) {
    return service.history(org, team, mission, task, human, page);
  }

  TaskOwnerDtos.Receipt commit(UUID target) throws Exception {
    var command = command(context(), target);
    var reply =
        mvc.perform(
                post(url().replace("/history", ""))
                    .with(auth())
                    .with(csrf())
                    .contentType("application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .content(json.writeValueAsString(command)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.treeToValue(json.readTree(reply).get("data"), TaskOwnerDtos.Receipt.class);
  }

  List<List<Map<String, Object>>> business() {
    var rows = new ArrayList<List<Map<String, Object>>>();
    for (String table :
        List.of(
            "organizations",
            "human_accounts",
            "organization_memberships",
            "teams",
            "missions",
            "tasks",
            "agents",
            "task_dependencies",
            "task_owner_events",
            "task_owner_receipts",
            "audit_events",
            "task_edit_events",
            "task_edit_receipts",
            "task_create_receipts")) {
      rows.add(jdbc.queryForList("select * from " + table + " order by 1,2"));
    }
    return rows;
  }

  @Test
  void realCommandsProducePagedImmutableHistoryWithActualTotalsAndExactPublicKeys()
      throws Exception {
    UUID target = agent("Recorded name");
    var receipts = new ArrayList<TaskOwnerDtos.Receipt>();
    for (int i = 0; i < 23; i++) receipts.add(commit(i % 2 == 0 ? target : null));
    jdbc.update(
        "update agents set name='Changed',status='SUSPENDED',team_id=null where id=?", target);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update(
        "update missions set status='COMPLETED',task_intake_paused=true where id=?", mission);
    jdbc.update(
        "update tasks set status='COMPLETED',task_owner_revision=2147483647,title='' where id=?",
        task);
    var before = business();
    var first = history(0);
    var second = history(1);
    assertThat(first.total()).isEqualTo(23);
    assertThat(first.pageSize()).isEqualTo(20);
    assertThat(first.taskTitle()).isEmpty();
    assertThat(second.items()).hasSize(3);
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < 23; i++) {
      var item = i < 20 ? first.items().get(i) : second.items().get(i - 20);
      var receipt = receipts.get(22 - i);
      assertThat(ids.add(item.eventId())).isTrue();
      assertThat(item.eventId()).isEqualTo(receipt.eventId());
      assertThat(item.revision()).isEqualTo(receipt.revision());
      assertThat(java.time.Instant.parse(item.committedAt()))
          .isEqualTo(java.time.Instant.parse(receipt.committedAt()));
      assertThat(item.before()).isEqualTo(receipt.before());
      assertThat(item.after()).isEqualTo(receipt.after());
      assertThat(item.actorName()).isEqualTo("Actor");
    }
    assertThat(history(100000).total()).isEqualTo(23);
    assertThat(history(100000).items()).isEmpty();
    var body =
        json.readTree(
                mvc.perform(get(url()).with(auth()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertThat(keys(body))
        .containsExactlyInAnyOrder(
            "organizationId",
            "teamId",
            "missionId",
            "taskId",
            "teamName",
            "missionTitle",
            "taskTitle",
            "page",
            "pageSize",
            "total",
            "items");
    assertThat(keys(body.get("items").get(0)))
        .containsExactlyInAnyOrder(
            "eventId", "revision", "committedAt", "actorName", "before", "after");
    assertThat(keys(body.get("items").get(0).get("after")))
        .containsExactlyInAnyOrder("state", "agent");
    assertThat(keys(body.get("items").get(0).get("after").get("agent")))
        .containsExactlyInAnyOrder("id", "name", "level", "status");
    assertThat(business()).isEqualTo(before);
  }

  Set<String> keys(com.fasterxml.jackson.databind.JsonNode node) {
    Set<String> result = new HashSet<>();
    node.fieldNames().forEachRemaining(result::add);
    return result;
  }

  @Test
  void emptyHasNoSyntheticBaselineAndReadsAreReadOnlyRepeatableRead() throws Exception {
    var before = business();
    assertThat(history(0).total()).isZero();
    assertThat(history(0).items()).isEmpty();
    var annotation =
        TaskOwnerService.class
            .getMethod(
                "history",
                UUID.class,
                UUID.class,
                UUID.class,
                UUID.class,
                HumanIdentity.class,
                int.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    assertThat(annotation.readOnly()).isTrue();
    assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    assertThat(business()).isEqualTo(before);
  }

  @Test
  void actorIsCurrentSameOrganizationMemberWithExplicitNullAfterRemoval() throws Exception {
    commit(agent("Historical"));
    UUID reader = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Reader','unused')",
        reader,
        reader + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values (?,?,'ADMIN')",
        reader,
        org);
    human = new HumanIdentity(reader, reader + "@example.test", "Reader");
    jdbc.update("update human_accounts set display_name='Renamed',enabled=false where id=?", actor);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and account_id=?",
        org,
        actor);
    assertThat(history(0).items().get(0).actorName()).isEqualTo("Renamed");
    jdbc.update("update human_accounts set display_name=? where id=?", "Unsafe\nname", actor);
    assertThatThrownBy(() -> history(0)).hasMessage("TASK_OWNER_HISTORY_UNAVAILABLE");
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        actor);
    UUID elsewhere = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Elsewhere',?,'ACTIVE',current_timestamp,current_timestamp)",
        elsewhere,
        elsewhere.toString());
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values (?,?,'ADMIN')",
        actor,
        elsewhere);
    assertThat(history(0).items().get(0).actorName()).isNull();
    var before = business();
    var body =
        mvc.perform(get(url()).with(auth()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var item = json.readTree(body).path("data").path("items").get(0);
    assertThat(item.has("actorName")).isTrue();
    assertThat(item.get("actorName").isNull()).isTrue();
    assertThat(body).doesNotContain("Unsafe", actor.toString());
    assertThat(business()).isEqualTo(before);
  }

  @Test
  void unavailableBeforeRetainsNoRawPointerAndLegacyStatusSnapshotSurvives() throws Exception {
    UUID old = agent("Private moved");
    jdbc.update("update tasks set owner_agent_id=? where id=?", old, task);
    jdbc.update("update agents set team_id=null where id=?", old);
    var cleared = commit(null);
    assertThat(history(0).items().get(0).before()).isEqualTo(cleared.before());
    assertThat(json.writeValueAsString(history(0))).doesNotContain(old.toString(), "Private moved");
    UUID target = agent("Legacy");
    commit(target);
    jdbc.update("update agents set status='SUSPENDED' where id=?", target);
    var legacy = commit(null);
    assertThat(history(0).items().get(0).before()).isEqualTo(legacy.before());
    assertThat(history(0).items().get(0).before().agent().status()).isEqualTo("SUSPENDED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "00", "01", "-1", "+1", " 1", "1 ", "1.0", "1e1", "100001", "999999999999"})
  void strictPagesRejectWithoutWrites(String page) throws Exception {
    var before = business();
    mvc.perform(get(url()).with(auth()).queryParam("page", page))
        .andExpect(status().isBadRequest());
    assertThat(business()).isEqualTo(before);
  }

  @Test
  void strictBodyQueryScopeAndAuthorityBeforeParsing() throws Exception {
    var before = business();
    mvc.perform(get(url()).with(auth()).queryParam("page", "0", "1"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url()).with(auth()).queryParam("query", "x"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(url()).with(auth()).content("{}")).andExpect(status().isBadRequest());
    mvc.perform(get(url()).with(auth()).header("Transfer-Encoding", "chunked"))
        .andExpect(status().isBadRequest());
    for (UUID id : List.of(team, mission, task)) {
      mvc.perform(get(url().replace(id.toString(), UUID.randomUUID().toString())).with(auth()))
          .andExpect(status().isNotFound());
    }
    mvc.perform(get(url().replace(task.toString(), task.toString().toUpperCase())).with(auth()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(url().replace(org.toString(), UUID.randomUUID().toString()))
                .with(auth())
                .queryParam("page", "bad")
                .content("{}"))
        .andExpect(status().isForbidden());
    assertThat(business()).isEqualTo(before);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and account_id=?",
        org,
        actor);
    before = business();
    mvc.perform(
            get(url().replace(task.toString(), "bad"))
                .with(auth())
                .queryParam("page", "bad")
                .content("{}"))
        .andExpect(status().isForbidden());
    assertThatThrownBy(() -> history(0)).hasMessage("FORBIDDEN");
    assertThat(business()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"state\":\"UNASSIGNED\",\"agent\":null}",
        "{\"state\":\"ASSIGNED\",\"agent\":null}",
        "{\"state\":\"UNAVAILABLE\",\"agent\":null}",
        "{\"state\":\"ASSIGNED\",\"agent\":null,\"extra\":1}",
        "{\"state\":\"UNASSIGNED\",\"state\":\"ASSIGNED\",\"agent\":null}",
        "{\"state\":1,\"agent\":null}",
        "{}",
        "[]",
        "null",
        "{} {}"
      })
  void selectedCorruptionFailsSafelyButOffPageDoesNotHideValidRows(String corrupt)
      throws Exception {
    UUID target = agent("Recorded");
    for (int i = 0; i < 21; i++) commit(i % 2 == 0 ? target : null);
    jdbc.update(
        "update task_owner_events set after_json=? where task_id=? and revision=1", corrupt, task);
    var before = business();
    assertThat(history(0).items()).hasSize(20);
    assertThat(history(0).total()).isEqualTo(21);
    mvc.perform(get(url()).with(auth()).queryParam("page", "1"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("TASK_OWNER_HISTORY_UNAVAILABLE"));
    assertThat(history(2).total()).isEqualTo(21);
    assertThat(history(2).items()).isEmpty();
    assertThat(business()).isEqualTo(before);
  }

  @Test
  void rawProvenanceAndCurrentNamesFailClosedWithoutReadingReceipts() throws Exception {
    UUID target = agent("Recorded");
    commit(target);
    jdbc.update(
        "update task_owner_receipts set original_response='broken',body_sha256=? where task_id=?",
        "b".repeat(64),
        task);
    assertThat(history(0).items()).hasSize(1);
    jdbc.update(
        "update task_owner_events set before_owner_agent_id=after_owner_agent_id where task_id=?",
        task);
    var before = business();
    assertThatThrownBy(() -> history(0)).hasMessage("TASK_OWNER_HISTORY_UNAVAILABLE");
    assertThat(business()).isEqualTo(before);
    jdbc.update("update task_owner_events set before_owner_agent_id=null where task_id=?", task);
    jdbc.update("update teams set name=? where id=?", "Unsafe\nname", team);
    before = business();
    assertThatThrownBy(() -> history(0)).hasMessage("TASK_OWNER_HISTORY_UNAVAILABLE");
    assertThat(business()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ID",
        "RAW",
        "NAME",
        "LEVEL",
        "STATUS",
        "TYPE",
        "MISSING",
        "EXTRA",
        "DUPLICATE",
        "TRAILING",
        "SURROGATE",
        "C1",
        "SEPARATOR"
      })
  void strictSelectedAgentJsonNeverCoercesOrExposesCorruption(String kind) throws Exception {
    UUID target = agent("Recorded");
    commit(target);
    String source =
        jdbc.queryForObject(
            "select after_json from task_owner_events where task_id=?", String.class, task);
    String corrupt =
        switch (kind) {
          case "ID" -> source.replace(target.toString(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
          case "RAW" -> source.replace(target.toString(), UUID.randomUUID().toString());
          case "NAME" -> source.replace("Recorded", " ");
          case "LEVEL" -> source.replace("MID", "x".repeat(51));
          case "STATUS" -> source.replace("DRAFT", "SUSPENDED");
          case "TYPE" -> source.replace("\"Recorded\"", "12");
          case "MISSING" -> source.replace("\"name\":\"Recorded\",", "");
          case "EXTRA" -> source.replace("\"name\":", "\"private\":true,\"name\":");
          case "DUPLICATE" -> source.replace("\"name\":", "\"name\":\"other\",\"name\":");
          case "TRAILING" -> source + " {}";
          case "SURROGATE" -> source.replace("Recorded", "\\uD800");
          case "C1" -> source.replace("Recorded", "\\u0085");
          default -> source.replace("Recorded", "\\u2028");
        };
    jdbc.update("update task_owner_events set after_json=? where task_id=?", corrupt, task);
    var before = business();
    String reply =
        mvc.perform(get(url()).with(auth()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("TASK_OWNER_HISTORY_UNAVAILABLE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(reply)
        .doesNotContain("Recorded", target.toString(), "after_json", "SELECT", "private");
    assertThat(business()).isEqualTo(before);
  }

  @Test
  void currentAuthorityLossIsDeniedAndCannotBeBypassedWithMalformedWire() throws Exception {
    var before = business();
    mvc.perform(get(url()).queryParam("page", "bad").content("{}"))
        .andExpect(status().isUnauthorized());
    assertThat(business()).isEqualTo(before);
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", actor);
    before = business();
    mvc.perform(get(url()).with(auth()).queryParam("page", "bad"))
        .andExpect(status().isUnauthorized());
    assertThat(business()).isEqualTo(before);
    jdbc.update("update human_accounts set credential_version=0,enabled=false where id=?", actor);
    before = business();
    mvc.perform(get(url()).with(auth()).queryParam("page", "bad"))
        .andExpect(status().isUnauthorized());
    assertThat(business()).isEqualTo(before);
  }

  @Test
  void lifecycleAndIntakeNeverGateHistory() {
    for (String state :
        List.of(
            "DRAFT",
            "READY",
            "RUNNING",
            "WAITING",
            "BLOCKED",
            "IN_REVIEW",
            "COMPLETED",
            "FAILED",
            "CANCELLED")) {
      jdbc.update(
          "update tasks set status=?,task_owner_revision=2147483647 where id=?", state, task);
      jdbc.update("update missions set task_intake_paused=true where id=?", mission);
      var before = business();
      assertThat(history(0).total()).isZero();
      assertThat(business()).isEqualTo(before);
    }
  }

  @Test
  void exactTaskScopeNeverIncludesAnotherTasksEvents() throws Exception {
    UUID target = agent("Scoped");
    var own = commit(target);
    UUID original = task;
    task = UUID.randomUUID();
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values (?,?,?,?,'Other task','DRAFT')",
        task,
        org,
        team,
        mission);
    var other = commit(target);
    assertThat(history(0).items())
        .extracting(TaskOwnerDtos.HistoryItem::eventId)
        .containsExactly(other.eventId());
    task = original;
    var before = business();
    assertThat(history(0).total()).isEqualTo(1);
    assertThat(history(0).items())
        .extracting(TaskOwnerDtos.HistoryItem::eventId)
        .containsExactly(own.eventId());
    assertThat(business()).isEqualTo(before);
  }
}

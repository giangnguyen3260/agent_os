package com.agentos.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.agent.api.dto.AgentConfigurationRequest;
import com.agentos.agent.api.dto.AgentConfigurationResponse;
import com.agentos.agent.api.dto.AgentDraftRequest;
import com.agentos.agent.application.AgentConfigurationService;
import com.agentos.agent.application.AgentDraftService;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:agent_configuration_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentConfigurationIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentDraftService drafts;
  @Autowired AgentConfigurationService configurations;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org;
  UUID agent;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    org = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
    human = new HumanIdentity(account, account + "@example.test", "Tester");
    agent =
        drafts
            .create(
                org,
                human,
                UUID.randomUUID(),
                new AgentDraftRequest(
                    "Agent",
                    "Role",
                    null,
                    " description ",
                    "Responsibilities",
                    "Input",
                    "Output",
                    "Criteria",
                    null,
                    null,
                    null,
                    null))
            .response()
            .id();
  }

  AgentConfigurationRequest request(int revision, String text) {
    return new AgentConfigurationRequest(
        revision,
        text,
        "Input",
        "Output",
        "Criteria",
        null,
        "",
        null,
        null,
        1000,
        2000000,
        new BigDecimal("1000000.00"));
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/agents/" + agent + "/configuration";
  }

  AgentConfigurationResponse save(UUID key, AgentConfigurationRequest r) {
    return configurations.put(org, human, agent, key, r);
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  @Test
  void persistsIdentityAndImmutableSnapshotsWithReplayBeforeStatusAndRevision() {
    var original = configurations.get(org, human, agent);
    assertThat(original.revision()).isEqualTo(1);
    UUID key = UUID.randomUUID();
    var r = request(1, " Updated ");
    var first = save(key, r);
    assertThat(first.revision()).isEqualTo(2);
    assertThat(first.agent().responsibilities()).isEqualTo("Updated");
    assertThat(first.agent().id()).isEqualTo(original.agent().id());
    assertThat(first.agent().description()).isEqualTo(" description ");
    assertThat(first.agent().name()).isEqualTo(original.agent().name());
    assertThat(first.agent().teamId()).isEqualTo(original.agent().teamId());
    assertThat(first.agent().status()).isEqualTo("DRAFT");
    assertThat(configurations.get(org, human, agent)).isEqualTo(first);
    String snapshot =
        jdbc.queryForObject(
            "select original_response from agent_configuration_versions where organization_id=? and"
                + " revision=2",
            String.class,
            org);
    save(UUID.randomUUID(), request(2, "Later"));
    jdbc.update("update agents set status='ACTIVE' where id=?", agent);
    assertThat(save(key, request(1, "Updated"))).isEqualTo(first);
    assertThatThrownBy(() -> save(key, request(1, "Different"))).hasMessage("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> save(UUID.randomUUID(), r)).hasMessage("AGENT_NOT_EDITABLE");
    assertThat(
            jdbc.queryForObject(
                "select original_response from agent_configuration_versions where organization_id=?"
                    + " and revision=2",
                String.class,
                org))
        .isEqualTo(snapshot);
    assertThat(count("agent_configuration_versions")).isEqualTo(3);
    assertThat(count("agent_configuration_receipts")).isEqualTo(2);
  }

  @Test
  void rejectsNoopAndStaleWithoutPartialWritesAndCompletesLegacy() {
    var unchanged =
        new AgentConfigurationRequest(
            1,
            "Responsibilities",
            "Input",
            "Output",
            "Criteria",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> save(UUID.randomUUID(), unchanged))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> save(UUID.randomUUID(), request(0, "stale")))
        .hasMessage("CONFIGURATION_CONFLICT");
    assertThat(count("agent_configuration_versions")).isEqualTo(1);
    agent = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values"
            + " (?,?,'Legacy','Role','MID','DRAFT','SUGGEST',current_timestamp,current_timestamp)",
        agent,
        org);
    assertThat(configurations.get(org, human, agent).agent().responsibilities()).isNull();
    var completed = save(UUID.randomUUID(), request(0, "Completed"));
    assertThat(completed.revision()).isEqualTo(1);
    assertThat(completed.agent().responsibilities()).isEqualTo("Completed");
  }

  @Test
  void receiptFailureRollsBackConfigurationRevisionAndHistoryAndCanRetry() {
    var before = configurations.get(org, human, agent);
    UUID key = UUID.randomUUID();
    jdbc.execute(
        "alter table agent_configuration_receipts add constraint reject_configuration_receipt check"
            + " (organization_id <> '"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> save(key, request(1, "Updated")))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(configurations.get(org, human, agent)).isEqualTo(before);
      assertThat(count("agent_configuration_versions")).isEqualTo(1);
      assertThat(count("agent_configuration_receipts")).isZero();
    } finally {
      jdbc.execute(
          "alter table agent_configuration_receipts drop constraint reject_configuration_receipt");
    }
    assertThat(save(key, request(1, "Updated")).revision()).isEqualTo(2);
  }

  @Test
  void concurrentSameKeyReplaysAndDifferentKeysCannotLoseUpdates() throws Exception {
    UUID key = UUID.randomUUID();
    var r = request(1, "Same");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<AgentConfigurationResponse> action =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            return save(key, r);
          };
      var one = pool.submit(action);
      var two = pool.submit(action);
      start.countDown();
      assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(two.get(20, TimeUnit.SECONDS));
    }
    var race = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<String> first = () -> compete(race, "First");
      Callable<String> second = () -> compete(race, "Second");
      var one = pool.submit(first);
      var two = pool.submit(second);
      race.countDown();
      assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("saved", "CONFIGURATION_CONFLICT");
    }
    var current = configurations.get(org, human, agent);
    assertThat(current.revision()).isEqualTo(3);
    assertThat(current.agent().responsibilities()).isIn("First", "Second");
    assertThat(count("agent_configuration_versions")).isEqualTo(3);
    assertThat(count("agent_configuration_receipts")).isEqualTo(2);
  }

  String compete(CountDownLatch start, String text) throws Exception {
    start.await(10, TimeUnit.SECONDS);
    try {
      save(UUID.randomUUID(), request(2, text));
      return "saved";
    } catch (AgentDraftService.Conflict ex) {
      return ex.code();
    }
  }

  @Test
  void securityRecheckedEvenForReplayAndMemberCanRead() throws Exception {
    UUID key = UUID.randomUUID();
    var r = request(1, "Updated");
    save(key, r);
    String body = json.writeValueAsString(r);
    mvc.perform(
            put(url())
                .with(auth())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    mvc.perform(get(url()).with(auth())).andExpect(status().isOk());
    mvc.perform(
            put(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    assertThatThrownBy(() -> save(key, r)).isInstanceOf(AccessDeniedException.class);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", human.id());
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", human.id());
      mvc.perform(get(url()).with(auth())).andExpect(status().isUnauthorized());
      mvc.perform(
              put(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", key)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isUnauthorized());
      assertThatThrownBy(() -> save(key, r)).isInstanceOf(AccessDeniedException.class);
    }
    jdbc.update("update human_accounts set enabled=true where id=?", human.id());
    jdbc.update("delete from organization_memberships where account_id=?", human.id());
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
    assertThatThrownBy(() -> save(key, r)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void migrationBackfillsOnlyExistingConfigAndPreservesNullableData() {
    var database =
        new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build();
    try {
      var db = new JdbcTemplate(database);
      db.execute("create domain timestamptz as timestamp with time zone");
      db.execute("create table organizations(id uuid primary key)");
      db.execute("create table human_accounts(id uuid primary key)");
      db.execute("create table agents(id uuid primary key,organization_id uuid not null)");
      db.execute(
          "create table agent_draft_config(agent_id uuid primary key,model varchar(2000),limits"
              + " varchar(2000))");
      UUID configured = UUID.randomUUID();
      UUID legacy = UUID.randomUUID();
      db.update("insert into agents values (?,?),(?,?)", configured, org, legacy, org);
      db.update("insert into agent_draft_config values (?,null,' exact ')", configured);
      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V007__agent_configuration.sql"))
          .execute(database);
      assertThat(
              db.queryForObject(
                  "select configuration_revision from agents where id=?",
                  Integer.class,
                  configured))
          .isEqualTo(1);
      assertThat(
              db.queryForObject(
                  "select configuration_revision from agents where id=?", Integer.class, legacy))
          .isZero();
      assertThat(
              db.queryForObject(
                  "select model from agent_draft_config where agent_id=?",
                  String.class,
                  configured))
          .isNull();
      assertThat(
              db.queryForObject(
                  "select limits from agent_draft_config where agent_id=?",
                  String.class,
                  configured))
          .isEqualTo(" exact ");
      assertThat(
              db.queryForObject(
                  "select concurrency from agent_draft_config where agent_id=?",
                  Integer.class,
                  configured))
          .isNull();
      assertThat(db.queryForObject("select count(*) from agent_configuration_versions", Long.class))
          .isZero();
      assertThat(db.queryForObject("select count(*) from agent_configuration_receipts", Long.class))
          .isZero();
    } finally {
      database.shutdown();
    }
  }

  @Test
  void tenantForeignKeysRejectMismatchedSnapshotsAndReceipts() {
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Foreign',?,'ACTIVE',current_timestamp,current_timestamp)",
        foreign,
        foreign.toString());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " agent_configuration_versions(organization_id,agent_id,revision,account_id,original_response)"
                        + " values (?,?,2,?,'{}')",
                    foreign,
                    agent,
                    human.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " agent_configuration_receipts(organization_id,account_id,agent_id,idempotency_key,body_sha256,original_response)"
                        + " values (?,?,?,?,?,'{}')",
                    foreign,
                    human.id(),
                    agent,
                    UUID.randomUUID(),
                    "x".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", human.id(), foreign);
    assertThatThrownBy(() -> configurations.get(foreign, human, agent))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(
            () ->
                configurations.put(foreign, human, agent, UUID.randomUUID(), request(1, "foreign")))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void canonicalNullsBudgetAndTextBounds() throws Exception {
    UUID key = UUID.randomUUID();
    ObjectNode body = json.valueToTree(request(1, "Updated"));
    body.put("monthlyBudget", 0);
    body.remove(List.of("model", "tools", "limits"));
    var first =
        mvc.perform(
                put(url())
                    .with(auth())
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    body.putNull("model");
    body.putNull("tools");
    body.putNull("limits");
    body.put("monthlyBudget", new BigDecimal("0.00"));
    var replay =
        mvc.perform(
                put(url())
                    .with(auth())
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(replay).get("data")).isEqualTo(json.readTree(first).get("data"));
    for (String field :
        List.of(
            "responsibilities",
            "expectedInput",
            "outputFormat",
            "acceptanceCriteria",
            "model",
            "skills",
            "tools",
            "limits")) {
      boolean required =
          List.of("responsibilities", "expectedInput", "outputFormat", "acceptanceCriteria")
              .contains(field);
      for (String invalid :
          required ? List.of("\u2003", "x".repeat(4001)) : List.of("x".repeat(2001))) {
        ObjectNode invalidBody = json.valueToTree(request(2, "Updated again"));
        invalidBody.put(field, invalid);
        mvc.perform(
                put(url())
                    .with(auth())
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(invalidBody.toString()))
            .andExpect(status().isBadRequest());
      }
    }
    for (String field : List.of("expectedRevision", "concurrency", "maxTokensPerRun")) {
      ObjectNode invalidBody = json.valueToTree(request(2, "Updated again"));
      invalidBody.put(
          field,
          field.equals("concurrency")
              ? 1001
              : field.equals("maxTokensPerRun") ? 2000001 : Integer.MAX_VALUE);
      mvc.perform(
              put(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(invalidBody.toString()))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void httpStrictNumbersBoundsScoped404AndImmutableFields() throws Exception {
    for (String field :
        List.of("expectedRevision", "concurrency", "maxTokensPerRun", "monthlyBudget")) {
      for (String invalid :
          List.of(
              "\"1\"",
              "true",
              "[]",
              "{}",
              field.equals("monthlyBudget") ? "0.001" : "1.5",
              "2147483648",
              "-1")) {
        ObjectNode body = json.valueToTree(request(1, "Updated"));
        body.set(field, json.readTree(invalid));
        mvc.perform(
                put(url())
                    .with(auth())
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(body.toString()))
            .andExpect(status().isBadRequest());
      }
    }
    for (String field :
        List.of(
            "responsibilities",
            "expectedInput",
            "outputFormat",
            "acceptanceCriteria",
            "model",
            "skills",
            "tools",
            "limits")) {
      ObjectNode body = json.valueToTree(request(1, "Updated"));
      body.put(field, 42);
      mvc.perform(
              put(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(body.toString()))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get(url().replace(agent.toString(), UUID.randomUUID().toString())).with(auth()))
        .andExpect(status().isNotFound());
    String body = json.writeValueAsString(request(1, "Updated"));
    mvc.perform(put(url()).with(auth()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
    ObjectNode writable = json.readValue(body, ObjectNode.class);
    writable.put("status", "ACTIVE");
    writable.put("name", "Injected");
    mvc.perform(
            put(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(writable.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value(2))
        .andExpect(jsonPath("$.data.agent.name").value("Agent"))
        .andExpect(jsonPath("$.data.agent.status").value("DRAFT"));
  }
}

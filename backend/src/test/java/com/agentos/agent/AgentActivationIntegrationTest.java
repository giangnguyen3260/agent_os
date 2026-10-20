package com.agentos.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.agent.api.dto.AgentActivationRequest;
import com.agentos.agent.api.dto.AgentActivationReview;
import com.agentos.agent.api.dto.AgentActivationReview.Status;
import com.agentos.agent.api.dto.AgentConfigurationRequest;
import com.agentos.agent.api.dto.AgentDraftRequest;
import com.agentos.agent.application.AgentActivationService;
import com.agentos.agent.application.AgentConfigurationService;
import com.agentos.agent.application.AgentDraftService;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:agent_activation_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentActivationIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentDraftService drafts;
  @Autowired AgentConfigurationService configurations;
  @Autowired AgentActivationService activations;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org;
  UUID agent;
  UUID team;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    org = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Activation test',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Activation tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
    human = new HumanIdentity(account, account + "@example.test", "Activation tester");
    agent =
        drafts
            .create(
                org,
                human,
                UUID.randomUUID(),
                new AgentDraftRequest(
                    "Activation agent",
                    "Role",
                    null,
                    null,
                    "Responsibilities",
                    "Input",
                    "Output",
                    "Criteria",
                    "requested-model",
                    null,
                    "requested-tool",
                    "free text is not a limit"))
            .response()
            .id();
    team = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " teams(id,organization_id,name,status,concurrency,monthly_budget,currency,created_at,updated_at)"
            + " values (?,?,'Activation"
            + " team','ACTIVE',1000,1000000,'USD',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update("update agents set team_id=? where id=?", team, agent);
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  String base() {
    return "/api/v1/organizations/" + org + "/agents/" + agent;
  }

  AgentActivationReview review() {
    return activations.get(org, human, agent);
  }

  void configured() {
    configurations.put(
        org,
        human,
        agent,
        UUID.randomUUID(),
        new AgentConfigurationRequest(
            1,
            "Responsibilities",
            "Input",
            "Output",
            "Criteria",
            "requested-model",
            null,
            "requested-tool",
            "text",
            1000,
            2000000,
            new BigDecimal("1000000.00")));
  }

  void check(AgentActivationReview review, int index, Status expected) {
    assertThat(review.checks().get(index).status()).isEqualTo(expected);
    assertThat(review.checks().get(index).label()).isNotBlank();
    assertThat(review.checks().get(index).message()).isNotBlank();
  }

  // Compare persisted rows, including timestamps and receipt/history payloads, not just counts.
  Map<String, List<Map<String, Object>>> snapshot() {
    Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
    for (String table :
        List.of(
            "organizations",
            "teams",
            "agents",
            "agent_create_receipts",
            "agent_configuration_versions",
            "agent_configuration_receipts",
            "tasks",
            "audit_events")) {
      String column = table.equals("organizations") ? "id" : "organization_id";
      result.put(
          table, jdbc.queryForList("select * from " + table + " where " + column + "=?", org));
    }
    result.put(
        "agent_draft_config",
        jdbc.queryForList("select * from agent_draft_config where agent_id=?", agent));
    return result;
  }

  @Test
  void actualConfigurationOrderedChecksAndUnavailableIntegrations() throws Exception {
    var before = snapshot();
    var incomplete = review();
    check(incomplete, 3, Status.BLOCKED);
    check(incomplete, 5, Status.BLOCKED);
    assertThat(snapshot()).isEqualTo(before);
    configured();
    Instant start = Instant.now();
    var r = review();
    assertThat(r.checkedAt()).isBetween(start, Instant.now());
    assertThat(r.organizationId()).isEqualTo(org);
    assertThat(r.agentId()).isEqualTo(agent);
    assertThat(r.revision()).isEqualTo(2);
    assertThat(r.status()).isEqualTo("DRAFT");
    assertThat(r.activationAllowed()).isFalse();
    assertThat(r.checks())
        .extracting(AgentActivationReview.Check::code)
        .containsExactly(
            "DRAFT_STATUS",
            "CONTRACT",
            "MODEL_SELECTION",
            "STRUCTURED_LIMITS",
            "TEAM_ACTIVE",
            "TEAM_ENVELOPE",
            "MODEL_AUTHORIZATION",
            "TOOL_AUTHORIZATION",
            "EXECUTION_RUNTIME");
    for (int i = 0; i < 6; i++) check(r, i, Status.PASS);
    for (int i = 6; i < 9; i++) check(r, i, Status.UNAVAILABLE);
    mvc.perform(get(base() + "/activation-review").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activationAllowed").value(false))
        .andExpect(jsonPath("$.data.name").value("Activation agent"));
  }

  @Test
  void nativeFixtureValuesPassOnlyConfigurationChecksAndNeverImplyGrants() {
    jdbc.update(
        "update teams set concurrency=4,monthly_budget=100,currency='USD' where id=?", team);
    configurations.put(
        org,
        human,
        agent,
        UUID.randomUUID(),
        new AgentConfigurationRequest(
            1,
            "Responsibilities",
            "Input",
            "Output",
            "Criteria",
            "requested-model",
            null,
            "requested-tool",
            null,
            2,
            1000,
            new BigDecimal("50.00")));
    var r = review();
    for (int i = 0; i < 6; i++) check(r, i, Status.PASS);
    for (int i = 6; i < 9; i++) check(r, i, Status.UNAVAILABLE);
    assertThat(r.checks().get(2).message()).contains("requested-model", "does not authorize");
    assertThat(r.checks().get(3).message())
        .contains("Concurrency: 2", "maximum tokens per run: 1000", "monthly budget: 50.00 USD");
    assertThat(r.checks().get(5).message())
        .contains(
            "Agent concurrency 2 <= team concurrency 4",
            "agent monthly budget 50.00 USD <= team monthly budget 100.00 USD",
            "does not allocate or enforce resources");
    assertThat(r.activationAllowed()).isFalse();
    var before = snapshot();
    assertThatThrownBy(() -> activations.activate(org, human, agent, new AgentActivationRequest(2)))
        .isInstanceOfSatisfying(
            AgentActivationService.Blocked.class,
            ex -> {
              assertThat(ex.review())
                  .usingRecursiveComparison()
                  .ignoringFields("checkedAt")
                  .isEqualTo(r);
            });
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void eachMissingStructuredLimitIsActionableAndTextAtBoundsIsConfigurationOnly() {
    configured();
    jdbc.update(
        "update agent_draft_config set responsibilities=?,model=? where agent_id=?",
        "x".repeat(4000),
        "m".repeat(2000),
        agent);
    check(review(), 1, Status.PASS);
    check(review(), 2, Status.PASS);
    jdbc.update("update agent_draft_config set max_tokens_per_run=null where agent_id=?", agent);
    assertThat(review().checks().get(3).message()).contains("maximum tokens");
    jdbc.update(
        "update agent_draft_config set max_tokens_per_run=1,monthly_budget=null where agent_id=?",
        agent);
    assertThat(review().checks().get(3).message()).contains("agent monthly budget");
    jdbc.update("update agent_draft_config set monthly_budget=0 where agent_id=?", agent);
    check(review(), 3, Status.PASS);
    check(review(), 6, Status.UNAVAILABLE);
    check(review(), 7, Status.UNAVAILABLE);
  }

  @Test
  void guardedRepeatedStaleAndConcurrentHttpRequestsHaveNoBusinessWrites() throws Exception {
    configured();
    var before = snapshot();
    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post(base() + "/activation")
                  .with(auth())
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"expectedRevision\":2}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("ACTIVATION_BLOCKED"))
          .andExpect(jsonPath("$.error.details.review.revision").value(2))
          .andExpect(jsonPath("$.error.details.review.checks[8].status").value("UNAVAILABLE"));
    }
    for (int revision : List.of(0, 1, Integer.MAX_VALUE)) {
      mvc.perform(
              post(base() + "/activation")
                  .with(auth())
                  .with(csrf())
                  .contentType("application/json")
                  .content("{\"expectedRevision\":" + revision + "}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));
    }
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<String> action =
          () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return mvc.perform(
                    post(base() + "/activation")
                        .with(auth())
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
          };
      var first = pool.submit(action);
      var second = pool.submit(action);
      start.countDown();
      for (String body :
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))) {
        assertThat(json.readTree(body).at("/error/code").asText()).isEqualTo("ACTIVATION_BLOCKED");
      }
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void strictRevisionAndCsrf() throws Exception {
    var before = snapshot();
    for (String body :
        List.of(
            "{}",
            "null",
            "{\"expectedRevision\":null}",
            "{\"expectedRevision\":\"1\"}",
            "{\"expectedRevision\":true}",
            "{\"expectedRevision\":1.0}",
            "{\"expectedRevision\":1e0}",
            "{\"expectedRevision\":[]}",
            "{\"expectedRevision\":{}}",
            "{\"expectedRevision\":2147483648}",
            "{\"expectedRevision\":-1}")) {
      mvc.perform(
              post(base() + "/activation")
                  .with(auth())
                  .with(csrf())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post(base() + "/activation")
                .with(auth())
                .contentType("application/json")
                .content("{\"expectedRevision\":1}"))
        .andExpect(status().isForbidden());
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void refreshReadsRevisionStatusAndTeamEnvelopeWithLowerAndUpperBounds() {
    configured();
    jdbc.update("update teams set currency=null where id=?", team);
    assertThat(review().checks().get(5).message()).contains("currency");
    jdbc.update("update teams set currency='USD',concurrency=999 where id=?", team);
    assertThat(review().checks().get(5).message())
        .contains("Agent concurrency 1000 exceeds team concurrency 999");
    jdbc.update("update teams set concurrency=1000,monthly_budget=999999.99 where id=?", team);
    assertThat(review().checks().get(5).message())
        .contains("Agent monthly budget 1000000.00 USD exceeds team monthly budget 999999.99 USD");
    jdbc.update("update teams set concurrency=null where id=?", team);
    check(review(), 5, Status.BLOCKED);
    jdbc.update(
        "update teams set concurrency=1000,monthly_budget=null,status='DRAFT' where id=?", team);
    check(review(), 4, Status.BLOCKED);
    assertThat(review().checks().get(5).message()).contains("team monthly budget");
    jdbc.update("update teams set monthly_budget=1000000,status='ACTIVE' where id=?", team);
    configurations.put(
        org,
        human,
        agent,
        UUID.randomUUID(),
        new AgentConfigurationRequest(
            2,
            "Updated",
            "Input",
            "Output",
            "Criteria",
            "requested",
            null,
            null,
            null,
            1,
            1,
            new BigDecimal("0.00")));
    var refreshed = review();
    assertThat(refreshed.revision()).isEqualTo(3);
    check(refreshed, 3, Status.PASS);
    check(refreshed, 5, Status.PASS);
    jdbc.update("update agents set status='ACTIVE' where id=?", agent);
    check(review(), 0, Status.BLOCKED);
    assertThatThrownBy(() -> activations.activate(org, human, agent, new AgentActivationRequest(3)))
        .isInstanceOf(AgentActivationService.Blocked.class);
  }

  @Test
  void legacyMissingConfigAndBlankContractModelAndLimitsAreBlocked() {
    jdbc.update(
        "update agent_draft_config set responsibilities=' ',model=' ' where agent_id=?", agent);
    check(review(), 1, Status.BLOCKED);
    check(review(), 2, Status.BLOCKED);
    jdbc.update("delete from agent_draft_config where agent_id=?", agent);
    jdbc.update("update agents set configuration_revision=0,team_id=null where id=?", agent);
    var before = snapshot();
    var legacy = review();
    for (int i : List.of(1, 2, 3, 4, 5)) check(legacy, i, Status.BLOCKED);
    assertThat(legacy.revision()).isZero();
    assertThatThrownBy(() -> activations.activate(org, human, agent, new AgentActivationRequest(0)))
        .isInstanceOf(AgentActivationService.Blocked.class);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void scopeIsolationAndFreshPermissionsForReadAndPost() throws Exception {
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,'Foreign',?,'ACTIVE',current_timestamp,current_timestamp)",
        foreign,
        foreign.toString());
    for (String suffix : List.of("/activation-review", "/activation")) {
      var request =
          suffix.endsWith("review")
              ? get(base().replace(org.toString(), foreign.toString()) + suffix)
              : post(base().replace(org.toString(), foreign.toString()) + suffix)
                  .contentType("application/json")
                  .content("{\"expectedRevision\":1}");
      mvc.perform(request.with(auth()).with(csrf())).andExpect(status().isForbidden());
    }
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", human.id(), foreign);
    mvc.perform(
            get(base().replace(org.toString(), foreign.toString()) + "/activation-review")
                .with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(base().replace(org.toString(), foreign.toString()) + "/activation")
                .with(auth())
                .with(csrf())
                .contentType("application/json")
                .content("{\"expectedRevision\":1}"))
        .andExpect(status().isNotFound());
    for (String mutation : List.of("role='MEMBER'", "role='ADMIN'")) {
      jdbc.update(
          "update organization_memberships set "
              + mutation
              + " where organization_id=? and account_id=?",
          org,
          human.id());
      if (mutation.contains("MEMBER")) denied(403);
    }
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", human.id());
      denied(401);
      assertThatThrownBy(this::review).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () -> activations.activate(org, human, agent, new AgentActivationRequest(1)))
          .isInstanceOf(AccessDeniedException.class);
    }
    jdbc.update("update human_accounts set enabled=true where id=?", human.id());
    jdbc.update(
        "delete from organization_memberships where account_id=? and organization_id=?",
        human.id(),
        org);
    denied(403);
  }

  void denied(int expected) throws Exception {
    mvc.perform(get(base() + "/activation-review").with(auth())).andExpect(status().is(expected));
    mvc.perform(
            post(base() + "/activation")
                .with(auth())
                .with(csrf())
                .contentType("application/json")
                .content("{\"expectedRevision\":1}"))
        .andExpect(status().is(expected));
  }
}

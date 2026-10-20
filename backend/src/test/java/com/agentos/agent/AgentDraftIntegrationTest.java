package com.agentos.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.agent.api.dto.AgentDraftRequest;
import com.agentos.agent.application.AgentDraftService;
import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:agent_draft_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentDraftIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired AgentDraftService drafts;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  UUID org;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    org = organization();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
    human = new HumanIdentity(account, account + "@example.test", "Tester");
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

  UUID team(UUID tenant, String name, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,?,?,current_timestamp,current_timestamp)",
        id,
        tenant,
        name,
        status);
    return id;
  }

  AgentDraftRequest request(UUID team) {
    return new AgentDraftRequest(
        " Agent ",
        " Role ",
        team,
        " exact ",
        " Responsibilities ",
        " Input ",
        " Output ",
        " Criteria ",
        " model ",
        " skills ",
        " tools ",
        " limits ");
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/agents";
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  @Test
  void persistsConfigurationDefaultsAndAddsWithoutChangingTeam() {
    UUID team = team(org, "Team", "DRAFT");
    var a = drafts.create(org, human, UUID.randomUUID(), request(team)).response();
    var b = drafts.create(org, human, UUID.randomUUID(), request(team)).response();
    assertThat(a.name()).isEqualTo("Agent");
    assertThat(a.role()).isEqualTo("Role");
    assertThat(a.responsibilities()).isEqualTo("Responsibilities");
    assertThat(a.expectedInput()).isEqualTo("Input");
    assertThat(a.outputFormat()).isEqualTo("Output");
    assertThat(a.acceptanceCriteria()).isEqualTo("Criteria");
    assertThat(a.description()).isEqualTo(" exact ");
    assertThat(a.model()).isEqualTo(" model ");
    assertThat(a.skills()).isEqualTo(" skills ");
    assertThat(a.tools()).isEqualTo(" tools ");
    assertThat(a.limits()).isEqualTo(" limits ");
    assertThat(a.level()).isEqualTo("MID");
    assertThat(a.status()).isEqualTo("DRAFT");
    assertThat(a.autonomyLevel()).isEqualTo("SUGGEST");
    assertThat(drafts.get(org, human, a.id())).isEqualTo(a);
    assertThat(b.teamId()).isEqualTo(team);
    assertThat(jdbc.queryForObject("select status from teams where id=?", String.class, team))
        .isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForObject("select manager_agent_id from teams where id=?", UUID.class, team))
        .isNull();
  }

  @Test
  void replayIsCanonicalPermanentAndBeforeTeamEligibility() {
    UUID team = team(org, "Team", "ACTIVE"), key = UUID.randomUUID();
    var original = drafts.create(org, human, key, request(team));
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update("update agents set name='Changed' where id=?", original.response().id());
    var r = request(team);
    var canonical =
        new AgentDraftRequest(
            "Agent",
            "Role",
            team,
            r.description(),
            "Responsibilities",
            "Input",
            "Output",
            "Criteria",
            r.model(),
            r.skills(),
            r.tools(),
            r.limits());
    var replay = drafts.create(org, human, key, canonical);
    assertThat(replay.replay()).isTrue();
    assertThat(replay.response()).isEqualTo(original.response());
    assertThatThrownBy(() -> drafts.create(org, human, key, request(null)))
        .isInstanceOf(AgentDraftService.Conflict.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThat(count("agents")).isEqualTo(1);
    assertThat(count("agent_create_receipts")).isEqualTo(1);
  }

  @Test
  void selectedTeamMustBeEligibleAndSameTenant() {
    for (UUID team :
        List.of(
            UUID.randomUUID(),
            team(organization(), "Foreign", "ACTIVE"),
            team(org, "Archived", "ARCHIVED"))) {
      assertThatThrownBy(() -> drafts.create(org, human, UUID.randomUUID(), request(team)))
          .hasMessage("AGENT_SETUP_CONFLICT");
    }
    assertThat(count("agents")).isZero();
    assertThat(count("agent_create_receipts")).isZero();
  }

  @Test
  void receiptFailureRollsBackAgentAndConfiguration() {
    jdbc.execute(
        "alter table agent_create_receipts add constraint reject_agent_receipt check"
            + " (organization_id <> '"
            + org
            + "')");
    long configs = jdbc.queryForObject("select count(*) from agent_draft_config", Long.class);
    try {
      assertThatThrownBy(() -> drafts.create(org, human, UUID.randomUUID(), request(null)))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("agents")).isZero();
      assertThat(count("agent_create_receipts")).isZero();
      assertThat(jdbc.queryForObject("select count(*) from agent_draft_config", Long.class))
          .isEqualTo(configs);
    } finally {
      jdbc.execute("alter table agent_create_receipts drop constraint reject_agent_receipt");
    }
  }

  @Test
  void allEndpointsRevalidateRevokedIdentityAndMembership() throws Exception {
    UUID key = UUID.randomUUID();
    var a = drafts.create(org, human, key, request(null)).response();
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    mvc.perform(get(url()).with(auth())).andExpect(status().isOk());
    mvc.perform(get(url() + "/" + a.id()).with(auth())).andExpect(status().isOk());
    mvc.perform(get(url() + "/eligible-teams").with(auth())).andExpect(status().isForbidden());
    assertThatThrownBy(() -> drafts.create(org, human, key, request(null)))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", human.id());
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", human.id());
      for (String suffix : List.of("", "/" + a.id(), "/eligible-teams"))
        mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isUnauthorized());
      assertThatThrownBy(() -> drafts.create(org, human, key, request(null)))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> drafts.get(org, human, a.id()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> drafts.list(org, human, 0, ""))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> drafts.eligible(org, human, 0, ""))
          .isInstanceOf(AccessDeniedException.class);
    }
    jdbc.update("update human_accounts set enabled=true where id=?", human.id());
    jdbc.update("delete from organization_memberships where account_id=?", human.id());
    for (String suffix : List.of("", "/" + a.id(), "/eligible-teams"))
      mvc.perform(get(url() + suffix).with(auth())).andExpect(status().isForbidden());
    assertThatThrownBy(() -> drafts.create(org, human, key, request(null)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void httpContractCsrfTenantScopeTypedValidationAndCanonicalNulls() throws Exception {
    String body = json.writeValueAsString(request(null));
    UUID key = UUID.randomUUID();
    mvc.perform(post(url()).with(auth()).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(url()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(url()).with(auth()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
    for (String invalid : List.of("not-uuid", ""))
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", invalid)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("DRAFT"));
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body.replace(" Agent ", "Other")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    for (String field :
        List.of(
            "name",
            "role",
            "responsibilities",
            "expectedInput",
            "outputFormat",
            "acceptanceCriteria")) {
      for (Object invalid :
          List.of(
              "\u2003",
              "x".repeat(field.equals("name") || field.equals("role") ? 256 : 4001),
              42,
              true,
              List.of("x"))) {
        var tree = json.valueToTree(request(null));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
            .set(field, json.valueToTree(invalid));
        mvc.perform(
                post(url())
                    .with(auth())
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(json.writeValueAsString(tree)))
            .andExpect(status().isBadRequest());
      }
    }
    for (String field : List.of("description", "model", "skills", "tools", "limits")) {
      var tree = json.valueToTree(request(null));
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
          .put(field, "x".repeat(field.equals("description") ? 4001 : 2001));
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(json.writeValueAsString(tree)))
          .andExpect(status().isBadRequest());
    }
    UUID foreign = team(organization(), "Foreign", "ACTIVE");
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(request(foreign))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("AGENT_SETUP_CONFLICT"));
    mvc.perform(get(url() + "/" + UUID.randomUUID()).with(auth())).andExpect(status().isNotFound());
    UUID otherOrg = organization();
    var oldOrg = org;
    org = otherOrg;
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
    org = oldOrg;
    var minimal = json.createObjectNode();
    for (String f :
        List.of(
            "name",
            "role",
            "responsibilities",
            "expectedInput",
            "outputFormat",
            "acceptanceCriteria")) minimal.put(f, "x");
    UUID nullKey = UUID.randomUUID();
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", nullKey)
                .contentType("application/json")
                .content(minimal.toString()))
        .andExpect(status().isCreated());
    minimal.putNull("description");
    minimal.putNull("model");
    minimal.putNull("teamId");
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", nullKey)
                .contentType("application/json")
                .content(minimal.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void literalBoundedStablePagesAndLegacyDetail() throws Exception {
    UUID literal = team(org, "Rate%_!", "DRAFT");
    team(org, "RateXY", "ACTIVE");
    team(org, "Rate%_!", "ARCHIVED");
    team(organization(), "Rate%_!", "ACTIVE");
    assertThat(drafts.eligible(org, human, 0, "%_!").items())
        .extracting(AgentDraftService.Candidate::id)
        .containsExactly(literal);
    for (int i = 0; i < 25; i++) {
      team(org, "Same", "ACTIVE");
      drafts.create(org, human, UUID.randomUUID(), request(null));
    }
    var first = drafts.list(org, human, 0, "Agent");
    var second = drafts.list(org, human, 1, "Agent");
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(5).doesNotContainAnyElementsOf(first.items());
    assertThat(second.total()).isEqualTo(25);
    assertThat(drafts.eligible(org, human, 0, "Same").items()).hasSize(20);
    assertThat(drafts.eligible(org, human, 1, "Same").items()).hasSize(5);
    for (String suffix : List.of("", "/eligible-teams"))
      for (String query : List.of("?page=-1", "?page=100001", "?query=" + "x".repeat(101)))
        mvc.perform(get(url() + suffix + query).with(auth())).andExpect(status().isBadRequest());
    UUID legacy = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values"
            + " (?,?,'Legacy','Role','MID','DRAFT','SUGGEST',current_timestamp,current_timestamp)",
        legacy,
        org);
    var response = drafts.get(org, human, legacy);
    assertThat(response.description()).isNull();
    assertThat(response.responsibilities()).isNull();
    assertThat(response.model()).isNull();
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values"
            + " (?,?,'Foreign','Role','MID','DRAFT','SUGGEST',current_timestamp,current_timestamp)",
        foreign,
        organization());
    mvc.perform(get(url() + "/" + foreign).with(auth())).andExpect(status().isNotFound());
  }

  @Test
  void concurrentSameKeyCreatesOneAgent() throws Exception {
    UUID key = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<AgentDraftService.Created> action =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            return drafts.create(org, human, key, request(null));
          };
      var one = pool.submit(action);
      var two = pool.submit(action);
      start.countDown();
      var a = one.get(20, TimeUnit.SECONDS);
      var b = two.get(20, TimeUnit.SECONDS);
      assertThat(a.response()).isEqualTo(b.response());
      assertThat(a.replay()).isNotEqualTo(b.replay());
      assertThat(count("agents")).isEqualTo(1);
      assertThat(count("agent_create_receipts")).isEqualTo(1);
    }
  }
}

package com.agentos.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.dashboard.DashboardService;
import com.agentos.organization.api.dto.NewTeamDraftRequest;
import com.agentos.organization.application.NewTeamDraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:team_draft_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamDraftIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired NewTeamDraftService drafts;
  @Autowired DashboardService dashboard;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  UUID organizationId;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    organizationId = organization();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Tester','unused')",
        account,
        account + "@example.test");
    jdbc.update(
        "insert into organization_memberships values (?,?,'ADMIN')", account, organizationId);
    human = new HumanIdentity(account, account + "@example.test", "Tester");
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Organization',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID agent(String name, String level, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,'Role',?,?,'SUGGEST',current_timestamp,current_timestamp)",
        id,
        organizationId,
        name,
        level,
        state);
    return id;
  }

  NewTeamDraftRequest request(UUID manager, List<UUID> ids) {
    return new NewTeamDraftRequest(
        " Team ",
        " Purpose ",
        "  exact description  ",
        manager,
        ids,
        "MANUAL",
        5,
        new BigDecimal("1.00"));
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + organizationId + "/teams";
  }

  @Test
  void createsDraftAssignsManagerAndKeepsSetupHealth() {
    UUID manager = agent("Manager", "MANAGER", "ACTIVE"), worker = agent("Worker", "MID", "DRAFT");
    var response =
        drafts
            .create(organizationId, human, UUID.randomUUID(), request(manager, List.of(worker)))
            .response();
    assertThat(response.name()).isEqualTo("Team");
    assertThat(response.description()).isEqualTo("  exact description  ");
    assertThat(response.status()).isEqualTo("DRAFT");
    assertThat(response.agentIds()).containsExactly(worker);
    assertThat(response.managerAgentId()).isEqualTo(manager);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from agents where team_id=?", Integer.class, response.id()))
        .isEqualTo(2);
    assertThat(dashboard.summary(organizationId, 0).teams().items().getFirst().health())
        .isEqualTo("SETUP_REQUIRED");
  }

  @Test
  void canonicalReplayReturnsOriginalEvenAfterTeamChangesAndRejectsDifferentBody() {
    UUID key = UUID.randomUUID(), a = agent("A", "MID", "ACTIVE"), b = agent("B", "MID", "ACTIVE");
    var original = drafts.create(organizationId, human, key, request(null, List.of(a, b)));
    jdbc.update("update teams set name='Changed' where id=?", original.response().id());
    var canonical =
        new NewTeamDraftRequest(
            "Team",
            "Purpose",
            "  exact description  ",
            null,
            List.of(b, a),
            "MANUAL",
            5,
            BigDecimal.ONE);
    var replay = drafts.create(organizationId, human, key, canonical);
    assertThat(replay.replay()).isTrue();
    assertThat(replay.response()).isEqualTo(original.response());
    assertThatThrownBy(() -> drafts.create(organizationId, human, key, request(null, List.of())))
        .isInstanceOf(NewTeamDraftService.Conflict.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void freshAccountVersionAndMembershipAreCheckedOnReplay() {
    UUID key = UUID.randomUUID();
    drafts.create(organizationId, human, key, request(null, List.of()));
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    assertThatThrownBy(() -> drafts.create(organizationId, human, key, request(null, List.of())))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", human.id());
    jdbc.update("update human_accounts set credential_version=1 where id=?", human.id());
    assertThatThrownBy(() -> drafts.create(organizationId, human, key, request(null, List.of())))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update(
        "update human_accounts set credential_version=0,enabled=false where id=?", human.id());
    assertThatThrownBy(() -> drafts.create(organizationId, human, key, request(null, List.of())))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void candidatesUseLiteralSearchStablePagingAndExcludeIneligible() {
    UUID literal = agent("Rate%_!", "MID", "ACTIVE");
    agent("RateXY", "MID", "ACTIVE");
    agent("Rate%_!", "MID", "SUSPENDED");
    UUID assigned = agent("Rate%_!", "MID", "DRAFT");
    drafts.create(organizationId, human, UUID.randomUUID(), request(null, List.of(assigned)));
    assertThat(drafts.eligible(organizationId, human, 0, " %_! ").items())
        .extracting(NewTeamDraftService.Candidate::id)
        .containsExactly(literal);
    for (int i = 0; i < 25; i++) agent("Same", "MID", "ACTIVE");
    var first = drafts.eligible(organizationId, human, 0, "same");
    var second = drafts.eligible(organizationId, human, 1, "same");
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(5).doesNotContainAnyElementsOf(first.items());
    assertThat(second.total()).isEqualTo(25);
    assertThatThrownBy(() -> drafts.eligible(organizationId, human, 100001, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void strictIntegerCsrfAndHttpStatusContract() throws Exception {
    String body = json.writeValueAsString(request(null, List.of()));
    mvc.perform(post(url()).with(auth()).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(url()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    UUID key = UUID.randomUUID();
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    for (String value : List.of("1.0", "\"5\"", "1e0", "2147483648")) {
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(body.replace("\"concurrency\":5", "\"concurrency\":" + value)))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post(url()).with(auth()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void scopedMemberDetailAndNullableLegacyConfiguration() throws Exception {
    var team =
        drafts
            .create(organizationId, human, UUID.randomUUID(), request(null, List.of()))
            .response();
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    mvc.perform(get(url() + "/" + team.id()).with(auth())).andExpect(status().isOk());
    mvc.perform(get(url() + "/" + UUID.randomUUID()).with(auth())).andExpect(status().isNotFound());
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Legacy','ACTIVE',current_timestamp,current_timestamp)",
        foreign,
        organization());
    mvc.perform(get(url() + "/" + foreign).with(auth())).andExpect(status().isNotFound());
    UUID legacy = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Legacy','ACTIVE',current_timestamp,current_timestamp)",
        legacy,
        organizationId);
    assertThat(drafts.get(organizationId, human, legacy).concurrency()).isNull();
  }

  @Test
  void rejectsIneligibleAndDatabaseEnforcesTenantAndManagerTeam() {
    UUID worker = agent("Worker", "MID", "ACTIVE");
    assertThatThrownBy(
            () ->
                drafts.create(organizationId, human, UUID.randomUUID(), request(worker, List.of())))
        .hasMessage("TEAM_SETUP_CONFLICT");
    var team =
        drafts
            .create(organizationId, human, UUID.randomUUID(), request(null, List.of(worker)))
            .response();
    assertThatThrownBy(
            () ->
                drafts.create(
                    organizationId, human, UUID.randomUUID(), request(null, List.of(worker))))
        .hasMessage("TEAM_SETUP_CONFLICT");
    UUID other = agent("Other", "MANAGER", "ACTIVE");
    assertThatThrownBy(
            () -> jdbc.update("update teams set manager_agent_id=? where id=?", other, team.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    UUID foreignTeam = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Foreign','ACTIVE',current_timestamp,current_timestamp)",
        foreignTeam,
        organization());
    assertThatThrownBy(
            () -> jdbc.update("update agents set team_id=? where id=?", foreignTeam, other))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void validatesConfigurationBeforeWritingAnything() {
    UUID worker = agent("Worker", "MID", "ACTIVE");
    var invalid =
        List.of(
            new NewTeamDraftRequest(
                "\u2003", "Purpose", null, null, List.of(), "MANUAL", 1, BigDecimal.ZERO),
            new NewTeamDraftRequest(
                "Team", "Purpose", null, null, List.of(), "MANUAL", 0, BigDecimal.ZERO),
            new NewTeamDraftRequest(
                "Team", "Purpose", null, null, List.of(), "MANUAL", 1001, BigDecimal.ZERO),
            new NewTeamDraftRequest(
                "Team", "Purpose", null, null, List.of(), "AUTO", 1, BigDecimal.ZERO),
            new NewTeamDraftRequest(
                "Team", "Purpose", null, null, List.of(), "MANUAL", 1, new BigDecimal("0.001")),
            new NewTeamDraftRequest(
                "Team",
                "Purpose",
                null,
                null,
                List.of(),
                "MANUAL",
                1,
                new BigDecimal("1000000.01")),
            request(null, List.of(worker, worker)),
            request(worker, List.of(worker)));
    var excessScale =
        new NewTeamDraftRequest(
            "Team", "Purpose", null, null, List.of(), "MANUAL", 1, new BigDecimal("1.000"));
    assertThatThrownBy(() -> drafts.create(organizationId, human, UUID.randomUUID(), excessScale))
        .isInstanceOf(IllegalArgumentException.class);
    for (var input : invalid) {
      assertThatThrownBy(() -> drafts.create(organizationId, human, UUID.randomUUID(), input))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from teams where organization_id=?",
                Integer.class,
                organizationId))
        .isZero();
    var boundary =
        new NewTeamDraftRequest(
            "Team", "Purpose", null, null, List.of(), "MANUAL", 1000, new BigDecimal("1000000.00"));
    assertThat(
            drafts
                .create(organizationId, human, UUID.randomUUID(), boundary)
                .response()
                .monthlyBudget())
        .isEqualByComparingTo("1000000");
  }

  @Test
  void rollbackAfterClaimsWhenReceiptInsertFails() {
    UUID worker = agent("Worker", "MID", "ACTIVE");
    jdbc.execute(
        "alter table team_create_receipts add constraint test_reject_receipt check"
            + " (organization_id <> '"
            + organizationId
            + "' or body_sha256='reject')");
    try {
      assertThatThrownBy(
              () ->
                  drafts.create(
                      organizationId, human, UUID.randomUUID(), request(null, List.of(worker))))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from teams where organization_id=?",
                  Integer.class,
                  organizationId))
          .isZero();
      assertThat(jdbc.queryForObject("select team_id from agents where id=?", UUID.class, worker))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from team_create_receipts where organization_id=?",
                  Integer.class,
                  organizationId))
          .isZero();
    } finally {
      jdbc.execute("alter table team_create_receipts drop constraint test_reject_receipt");
    }
  }

  @Test
  void concurrentSameKeyReplaysAndCompetingClaimsHaveOneWinner() throws Exception {
    UUID key = UUID.randomUUID();
    var sameKey = race(() -> drafts.create(organizationId, human, key, request(null, List.of())));
    assertThat(sameKey).allMatch(value -> value instanceof NewTeamDraftService.Created);
    var one = (NewTeamDraftService.Created) sameKey.get(0);
    var two = (NewTeamDraftService.Created) sameKey.get(1);
    assertThat(one.response()).isEqualTo(two.response());
    assertThat(one.replay()).isNotEqualTo(two.replay());
    UUID worker = agent("Worker", "MID", "ACTIVE");
    var claims =
        race(
            () ->
                drafts.create(
                    organizationId, human, UUID.randomUUID(), request(null, List.of(worker))));
    assertThat(
            claims.stream().filter(value -> value instanceof NewTeamDraftService.Created).count())
        .isEqualTo(1);
    assertThat(
            claims.stream().filter(value -> value instanceof NewTeamDraftService.Conflict).count())
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from teams where organization_id=?",
                Integer.class,
                organizationId))
        .isEqualTo(2);
  }

  List<Object> race(Callable<Object> action) throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Object> run =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
              return action.call();
            } catch (NewTeamDraftService.Conflict ex) {
              return ex;
            }
          };
      var a = pool.submit(run);
      var b = pool.submit(run);
      start.countDown();
      return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    }
  }
}

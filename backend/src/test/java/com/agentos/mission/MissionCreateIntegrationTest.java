package com.agentos.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.api.dto.MissionCreateRequest;
import com.agentos.mission.application.MissionCreateService;
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
        "spring.datasource.url=jdbc:h2:mem:mission_create_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionCreateIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MissionCreateService service;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  UUID org, team;
  HumanIdentity human;

  @BeforeEach
  void setup() {
    org = UUID.randomUUID();
    team = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", account, org);
    human = new HumanIdentity(account, account + "@example.test", "Tester");
  }

  MissionCreateRequest request() {
    return new MissionCreateRequest(
        " Title ",
        " Goal ",
        " Output ",
        null,
        "NORMAL",
        "2030-12-31",
        new BigDecimal("12.50"),
        "HUMAN_REQUIRED",
        " exact ");
  }

  String url() {
    return "/api/v1/organizations/" + org + "/teams/" + team + "/missions";
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  UUID manager(UUID assigned, String level, String status, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,?,'Role',?,?, 'SUGGEST',current_timestamp,current_timestamp)",
        id,
        org,
        assigned,
        name,
        level,
        status);
    return id;
  }

  @Test
  void persistsAndReplaysOriginalBeforeEligibility() {
    UUID key = UUID.randomUUID();
    var created = service.create(org, team, human, key, request());
    var r = created.response();
    assertThat(r.title()).isEqualTo("Title");
    assertThat(r.goal()).isEqualTo("Goal");
    assertThat(r.requiredOutput()).isEqualTo("Output");
    assertThat(r.context()).isEqualTo(" exact ");
    assertThat(r.status()).isEqualTo("CREATED");
    assertThat(r.currency()).isEqualTo("USD");
    assertThat(r.managerAgentId()).isNull();
    assertThat(service.get(org, team, human, r.id())).isEqualTo(r);
    assertThat(
            jdbc.queryForObject(
                "select created_by_user_id from missions where id=?", UUID.class, r.id()))
        .isEqualTo(human.id());
    assertThat(
            jdbc.queryForObject(
                "select payload from audit_events where mission_id=?", String.class, r.id()))
        .isEqualTo("{\"status\":\"CREATED\"}");
    jdbc.update("update teams set status='ARCHIVED',name='Changed' where id=?", team);
    assertThat(service.create(org, team, human, key, request()).response()).isEqualTo(r);
    assertThat(service.create(org, team, human, key, request()).replay()).isTrue();
    assertThatThrownBy(() -> service.create(org, UUID.randomUUID(), human, key, request()))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> service.create(org, team, human, UUID.randomUUID(), request()))
        .hasMessage("MISSION_SETUP_CONFLICT");
    assertThat(count("missions")).isEqualTo(1);
  }

  @Test
  void rollbackIncludesMissionReceiptAndAudit() {
    jdbc.execute(
        "alter table mission_create_receipts add constraint reject_mission_receipt check"
            + " (organization_id <> '"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> service.create(org, team, human, UUID.randomUUID(), request()))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("missions")).isZero();
      assertThat(count("audit_events")).isZero();
      assertThat(count("mission_create_receipts")).isZero();
    } finally {
      jdbc.execute("alter table mission_create_receipts drop constraint reject_mission_receipt");
    }
  }

  @Test
  void concurrentSameKeyIsAtomic() throws Exception {
    UUID key = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<MissionCreateService.Created> action =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            return service.create(org, team, human, key, request());
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      start.countDown();
      var one = a.get(20, TimeUnit.SECONDS);
      var two = b.get(20, TimeUnit.SECONDS);
      assertThat(one.response()).isEqualTo(two.response());
      assertThat(one.replay()).isNotEqualTo(two.replay());
      assertThat(count("missions")).isEqualTo(1);
      assertThat(count("audit_events")).isEqualTo(1);
      assertThat(count("mission_create_receipts")).isEqualTo(1);
    }
  }

  @Test
  void managersAreScopedLiteralPagedAndOptionalDraftAllowed() {
    UUID draft = manager(team, "MANAGER", "DRAFT", "Rate%_!");
    manager(team, "MID", "ACTIVE", "Rate%_!");
    manager(null, "MANAGER", "ACTIVE", "Rate%_!");
    manager(team, "MANAGER", "ARCHIVED", "Rate%_!");
    assertThat(service.eligible(org, team, human, 0, "%_!").items())
        .extracting(MissionCreateService.Candidate::id)
        .containsExactly(draft);
    for (int i = 0; i < 25; i++) manager(team, "MANAGER", "ACTIVE", "Same");
    assertThat(service.eligible(org, team, human, 0, "Same").items()).hasSize(20);
    assertThat(service.eligible(org, team, human, 1, "Same").items()).hasSize(5);
    var r = request();
    var selected =
        new MissionCreateRequest(
            r.title(),
            r.goal(),
            r.requiredOutput(),
            draft,
            r.priority(),
            r.deadline(),
            r.budgetLimit(),
            r.approvalMode(),
            r.context());
    assertThat(
            service.create(org, team, human, UUID.randomUUID(), selected).response().managerName())
        .isEqualTo("Rate%_!");
    jdbc.update("update agents set status='ARCHIVED' where id=?", draft);
    assertThatThrownBy(() -> service.create(org, team, human, UUID.randomUUID(), selected))
        .hasMessage("MISSION_SETUP_CONFLICT");
  }

  @Test
  void removedManagerReplayAndLegacyForeignTeamNameRemainSafe() {
    UUID manager = manager(team, "MANAGER", "DRAFT", "Private manager");
    var r = request();
    var selected =
        new MissionCreateRequest(
            r.title(),
            r.goal(),
            r.requiredOutput(),
            manager,
            r.priority(),
            r.deadline(),
            r.budgetLimit(),
            r.approvalMode(),
            null);
    UUID key = UUID.randomUUID();
    var saved = service.create(org, team, human, key, selected).response();
    jdbc.update("update agents set team_id=null,status='ARCHIVED' where id=?", manager);
    assertThat(service.create(org, team, human, key, selected).response()).isEqualTo(saved);
    assertThat(service.get(org, team, human, saved.id()).managerName()).isNull();
    assertThatThrownBy(() -> service.get(org, UUID.randomUUID(), human, saved.id()))
        .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    assertThatThrownBy(
            () -> service.create(org, UUID.randomUUID(), human, UUID.randomUUID(), request()))
        .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    var empty =
        new MissionCreateRequest(
            r.title(),
            r.goal(),
            r.requiredOutput(),
            manager,
            r.priority(),
            r.deadline(),
            r.budgetLimit(),
            r.approvalMode(),
            "");
    assertThat(service.create(org, team, human, key, empty).response()).isEqualTo(saved);
  }

  @Test
  void freshAuthorizationOnReadWriteReplayAndCandidates() throws Exception {
    UUID key = UUID.randomUUID();
    var saved = service.create(org, team, human, key, request()).response();
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    assertThat(service.get(org, team, human, saved.id())).isEqualTo(saved);
    assertThatThrownBy(() -> service.create(org, team, human, key, request()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.eligible(org, team, human, 0, ""))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", human.id());
    for (String mutation : List.of("credential_version=1", "credential_version=0,enabled=false")) {
      jdbc.update("update human_accounts set " + mutation + " where id=?", human.id());
      assertThatThrownBy(() -> service.get(org, team, human, saved.id()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.create(org, team, human, key, request()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.eligible(org, team, human, 0, ""))
          .isInstanceOf(AccessDeniedException.class);
      mvc.perform(get(url() + "/" + saved.id()).with(auth())).andExpect(status().isUnauthorized());
    }
    jdbc.update(
        "update human_accounts set enabled=true,credential_version=0 where id=?", human.id());
    jdbc.update("delete from organization_memberships where account_id=?", human.id());
    assertThatThrownBy(() -> service.get(org, team, human, saved.id()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.create(org, team, human, key, request()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void httpContractValidationScopeCsrfAndLegacyClosed() throws Exception {
    String body = json.writeValueAsString(request());
    UUID key = UUID.randomUUID();
    mvc.perform(post(url()).with(auth()).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(url()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(url()).with(auth()).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
    for (int status : List.of(201, 200))
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", key)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().is(status))
          .andExpect(jsonPath("$.data.status").value("CREATED"));
    for (String field :
        List.of(
            "title",
            "goal",
            "requiredOutput",
            "context",
            "priority",
            "deadline",
            "approvalMode",
            "budgetLimit")) {
      List<Object> invalid =
          switch (field) {
            case "budgetLimit" -> List.of("12", -1, 1.001, 1000001, true);
            case "deadline" -> List.of("2030-02-30", "2030-2-01", "2030-12-31T00:00:00Z", 42);
            case "priority", "approvalMode" -> List.of("AUTO", 42);
            case "context" -> List.of("x".repeat(8001), 42);
            default -> List.of(" ", 42, "x".repeat(field.equals("title") ? 256 : 4001));
          };
      for (Object value : invalid) {
        var tree = json.valueToTree(request());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set(field, json.valueToTree(value));
        mvc.perform(
                post(url())
                    .with(auth())
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(tree.toString()))
            .andExpect(status().isBadRequest());
      }
    }
    mvc.perform(get(url() + "/" + UUID.randomUUID()).with(auth())).andExpect(status().isNotFound());
    mvc.perform(
            get(url().replace(team.toString(), UUID.randomUUID().toString()) + "/eligible-managers")
                .with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get(url().replace(org.toString(), UUID.randomUUID().toString()) + "/eligible-managers")
                .with(auth()))
        .andExpect(status().isForbidden());
    for (String query : List.of("?page=-1", "?page=100001", "?query=" + "x".repeat(101)))
      mvc.perform(get(url() + "/eligible-managers" + query).with(auth()))
          .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/organizations/" + org + "/missions").with(auth()).with(csrf()))
        .andExpect(status().isGone());
  }
}

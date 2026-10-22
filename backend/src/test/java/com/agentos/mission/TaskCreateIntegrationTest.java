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
        "spring.datasource.url=jdbc:h2:mem:task_create_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskCreateIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MissionCreateService missions;
  @Autowired com.agentos.mission.application.TaskCreateService service;
  @Autowired com.agentos.mission.application.TaskDetailService details;
  UUID mission;
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
    mission = missions.create(org, team, human, UUID.randomUUID(), request()).response().id();
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
    return "/api/v1/organizations/" + org + "/teams/" + team + "/missions/" + mission + "/tasks";
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

  com.agentos.mission.api.dto.TaskCreateRequest task(
      long version, UUID owner, List<UUID> dependencies) {
    return new com.agentos.mission.api.dto.TaskCreateRequest(
        " Title ", " Description ", " Criteria ", "HIGH", owner, dependencies, version);
  }

  @Test
  void persistedDetailAndImmutableReplay() {
    UUID owner = manager(team, "MID", "DRAFT", "Owner");
    UUID dependency =
        service
            .create(org, team, mission, human, UUID.randomUUID(), task(0, null, List.of()))
            .response()
            .task()
            .id();
    UUID key = UUID.randomUUID();
    var request = task(1, owner, List.of(dependency));
    var saved = service.create(org, team, mission, human, key, request).response();
    assertThat(saved.task().description()).isEqualTo("Description");
    assertThat(saved.task().acceptanceCriteria()).isEqualTo("Criteria");
    assertThat(saved.task().priority()).isEqualTo("HIGH");
    assertThat(saved.task().status()).isEqualTo("DRAFT");
    assertThat(saved.task().pendingReason()).isNull();
    assertThat(saved.task().dependencyIds()).containsExactly(dependency);
    assertThat(details.get(org, team, mission, saved.task().id(), human).task())
        .isEqualTo(saved.task());
    jdbc.update("update agents set team_id=null where id=?", owner);
    assertThat(details.get(org, team, mission, saved.task().id(), human).task().ownerAgentId())
        .isNull();
    assertThat(details.get(org, team, mission, saved.task().id(), human).task().ownerName())
        .isNull();
    jdbc.update(
        "update missions set task_intake_paused=true,status='COMPLETED' where id=?", mission);
    assertThat(service.create(org, team, mission, human, key, request).response()).isEqualTo(saved);
    assertThat(service.context(org, team, mission, human).canCreate()).isFalse();
    assertThatThrownBy(
            () -> service.create(org, team, mission, human, key, task(2, null, List.of())))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    assertThatThrownBy(() -> service.create(org, team, mission, human, key, request))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.context(org, team, mission, human))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void setupAndVersionFailuresNeverWrite() {
    assertThatThrownBy(
            () ->
                service.create(
                    org, team, mission, human, UUID.randomUUID(), task(1, null, List.of())))
        .hasMessage("TASK_INTAKE_CONFLICT");
    for (var request :
        List.of(task(0, UUID.randomUUID(), List.of()), task(0, null, List.of(UUID.randomUUID()))))
      assertThatThrownBy(
              () -> service.create(org, team, mission, human, UUID.randomUUID(), request))
          .hasMessage("TASK_SETUP_CONFLICT");
    jdbc.update("update missions set task_intake_paused=true where id=?", mission);
    assertThatThrownBy(
            () ->
                service.create(
                    org, team, mission, human, UUID.randomUUID(), task(0, null, List.of())))
        .hasMessage("TASK_SETUP_CONFLICT");
    assertThat(count("tasks")).isZero();
    assertThat(count("task_create_receipts")).isZero();
    assertThat(service.context(org, team, mission, human).intakeVersion()).isZero();
  }

  @Test
  void concurrentSameKeyReplaysAndDifferentKeysConflict() throws Exception {
    UUID key = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<com.agentos.mission.application.TaskCreateService.Created> action =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            return service.create(org, team, mission, human, key, task(0, null, List.of()));
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      start.countDown();
      var one = a.get(20, TimeUnit.SECONDS);
      var two = b.get(20, TimeUnit.SECONDS);
      assertThat(one.response()).isEqualTo(two.response());
      assertThat(one.replay()).isNotEqualTo(two.replay());
    }
    assertThatThrownBy(
            () ->
                service.create(
                    org, team, mission, human, UUID.randomUUID(), task(0, null, List.of())))
        .hasMessage("TASK_INTAKE_CONFLICT");
    assertThat(count("tasks")).isEqualTo(1);
    assertThat(count("task_create_receipts")).isEqualTo(1);
  }

  @Test
  void receiptFailureRollsBackTaskEdgesVersionAndAudit() {
    UUID dependency =
        service
            .create(org, team, mission, human, UUID.randomUUID(), task(0, null, List.of()))
            .response()
            .task()
            .id();
    long tasks = count("tasks"),
        receipts = count("task_create_receipts"),
        audit = count("audit_events");
    UUID rejectedKey = UUID.randomUUID();
    jdbc.execute(
        "alter table task_create_receipts add constraint reject_task_receipt check (idempotency_key <> '"
            + rejectedKey
            + "')");
    try {
      assertThatThrownBy(
              () ->
                  service.create(
                      org, team, mission, human, rejectedKey, task(1, null, List.of(dependency))))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("tasks")).isEqualTo(tasks);
      assertThat(count("task_create_receipts")).isEqualTo(receipts);
      assertThat(count("audit_events")).isEqualTo(audit);
      assertThat(count("task_dependencies")).isZero();
      assertThat(service.context(org, team, mission, human).intakeVersion()).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from audit_events where organization_id=? and event_type='TASK_CREATED'",
                  Long.class,
                  org))
          .isEqualTo(1);
    } finally {
      jdbc.execute("alter table task_create_receipts drop constraint reject_task_receipt");
    }
  }

  @Test
  void httpValidationAndCandidates() throws Exception {
    UUID owner = manager(team, "JUNIOR", "DRAFT", "Rate%_!");
    manager(null, "MID", "ACTIVE", "Rate%_!");
    assertThat(service.owners(org, team, mission, human, 0, "%_!").items())
        .extracting(com.agentos.mission.application.TaskCreateService.Owner::id)
        .containsExactly(owner);
    String body = json.writeValueAsString(task(0, null, List.of()));
    UUID key = UUID.randomUUID();
    for (int code : List.of(201, 200))
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", key)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().is(code))
          .andExpect(jsonPath("$.data.task.status").value("DRAFT"));
    for (String invalid :
        List.of(
            body.replace("\"title\":\" Title \"", "\"title\":42"),
            body.replace("\"expectedIntakeVersion\":0", "\"expectedIntakeVersion\":-1"),
            body.substring(0, body.length() - 1) + ",\"runtimeClaim\":true}"))
      mvc.perform(
              post(url())
                  .with(auth())
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    mvc.perform(get(url() + "/creation-context").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.intakeVersion").value(1));
    assertThat(service.dependencies(org, team, mission, human, 0, "").items()).hasSize(1);
  }

  @Test
  void legacyFieldsAndForeignScopeAreSafe() {
    UUID legacy = UUID.randomUUID();
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values (?,?,?,?,'Legacy','COMPLETED')",
        legacy,
        org,
        team,
        mission);
    var task = details.get(org, team, mission, legacy, human).task();
    assertThat(task.description()).isNull();
    assertThat(task.acceptanceCriteria()).isNull();
    assertThat(task.priority()).isNull();
    assertThat(task.ownerAgentId()).isNull();
    assertThat(task.dependencyIds()).isEmpty();
    UUID other = missions.create(org, team, human, UUID.randomUUID(), request()).response().id();
    assertThatThrownBy(
            () ->
                service.create(
                    org, team, other, human, UUID.randomUUID(), task(0, null, List.of(legacy))))
        .hasMessage("TASK_SETUP_CONFLICT");
    assertThatThrownBy(() -> service.context(org, UUID.randomUUID(), mission, human))
        .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    for (String status : List.of("COMPLETED", "FAILED", "CANCELLED")) {
      jdbc.update("update missions set status=? where id=?", status, mission);
      assertThat(service.context(org, team, mission, human).canCreate()).isFalse();
      assertThatThrownBy(
              () ->
                  service.create(
                      org, team, mission, human, UUID.randomUUID(), task(0, null, List.of())))
          .hasMessage("TASK_SETUP_CONFLICT");
    }
  }

  @Test
  void everyRevocationDeniesMetadataCandidatesAndSameKeyReplayWithoutWrites() {
    UUID dependency =
        service
            .create(org, team, mission, human, UUID.randomUUID(), task(0, null, List.of()))
            .response()
            .task()
            .id();
    UUID key = UUID.randomUUID();
    var request = task(1, null, List.of(dependency));
    var saved = service.create(org, team, mission, human, key, request).response();
    var before = intakeSnapshot();
    for (String revocation : List.of("disabled", "credentials", "member", "deleted")) {
      switch (revocation) {
        case "disabled" ->
            jdbc.update("update human_accounts set enabled=false where id=?", human.id());
        case "credentials" ->
            jdbc.update(
                "update human_accounts set credential_version=credential_version+1 where id=?",
                human.id());
        case "member" ->
            jdbc.update(
                "update organization_memberships set role='MEMBER' where organization_id=? and account_id=?",
                org,
                human.id());
        case "deleted" ->
            jdbc.update(
                "delete from organization_memberships where organization_id=? and account_id=?",
                org,
                human.id());
        default -> throw new IllegalArgumentException("Unknown revocation");
      }
      try {
        assertThatThrownBy(() -> service.context(org, team, mission, human))
            .as(revocation)
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.owners(org, team, mission, human, 0, ""))
            .as(revocation)
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.dependencies(org, team, mission, human, 0, ""))
            .as(revocation)
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(org, team, mission, human, key, request))
            .as(revocation)
            .isInstanceOf(AccessDeniedException.class);
        assertThat(intakeSnapshot()).as(revocation).isEqualTo(before);
      } finally {
        jdbc.update(
            "update human_accounts set enabled=true,credential_version=? where id=?",
            human.credentialVersion(),
            human.id());
        jdbc.update(
            "delete from organization_memberships where organization_id=? and account_id=?",
            org,
            human.id());
        jdbc.update(
            "insert into organization_memberships(account_id,organization_id,role) values (?,?,'ADMIN')",
            human.id(),
            org);
      }
      var replay = service.create(org, team, mission, human, key, request);
      assertThat(replay.replay()).isTrue();
      assertThat(replay.response()).isEqualTo(saved);
      assertThat(intakeSnapshot()).isEqualTo(before);
    }
  }

  private Object intakeSnapshot() {
    return List.of(
        jdbc.queryForList("select * from tasks where organization_id=? order by id", org),
        jdbc.queryForList("select * from missions where organization_id=? order by id", org),
        jdbc.queryForList(
            "select * from task_dependencies where organization_id=? order by task_id,dependency_task_id",
            org),
        jdbc.queryForList(
            "select * from task_create_receipts where organization_id=? order by account_id,idempotency_key",
            org),
        jdbc.queryForList("select * from audit_events where organization_id=? order by id", org));
  }
}

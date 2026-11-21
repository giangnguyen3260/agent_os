package com.agentos.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.mission.application.MissionPlanReviewService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plan_acceptance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.mission-plan-review.enabled=true",
      "agentos.mission-plan-acceptance.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionPlanAcceptanceIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MissionPlanReviewService service;
  @Autowired MockMvc mvc;

  record Scope(UUID org, UUID team, UUID mission, UUID agent, HumanIdentity human) {}

  Scope fixture() {
    UUID org = UUID.randomUUID(),
        team = UUID.randomUUID(),
        mission = UUID.randomUUID(),
        agent = UUID.randomUUID(),
        actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values(?,'Plan',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values(?,?,'Admin','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values(?,?,'ADMIN')",
        actor,
        org);
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at)"
            + " values(?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values(?,?,?,'Mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        mission,
        org,
        team);
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values(?,?,?,'Writer','Writer','MID','ACTIVE','SUGGEST',current_timestamp,current_timestamp)",
        agent,
        org,
        team);
    return new Scope(
        org, team, mission, agent, new HumanIdentity(actor, actor + "@example.test", "Admin"));
  }

  UUID task(Scope s) {
    var id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id,description,acceptance_criteria,priority)"
            + " values(?,?,?,?,'Task','DRAFT',?,'Input','Output','NORMAL')",
        id,
        s.org(),
        s.team(),
        s.mission(),
        s.agent());
    return id;
  }

  void edge(Scope s, UUID task, UUID prerequisite) {
    jdbc.update(
        "insert into"
            + " task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)"
            + " values(?,?,?,?,?)",
        s.org(),
        s.team(),
        s.mission(),
        task,
        prerequisite);
  }

  MissionPlanReviewService.Review read(Scope s) {
    return service.current(s.org(), s.team(), s.mission(), s.human());
  }

  @Autowired com.agentos.mission.application.MissionPlanAcceptanceService acceptance;

  com.agentos.mission.application.MissionPlanAcceptanceService.Receipt accept(
      Scope s, UUID key, String fingerprint) {
    return acceptance.accept(s.org(), s.team(), s.mission(), s.human(), key, fingerprint);
  }

  com.agentos.mission.application.MissionPlanAcceptanceService.Current current(Scope s) {
    return acceptance.current(s.org(), s.team(), s.mission(), s.human());
  }

  @Test
  void fullDagSnapshotAndChangedReadyPlanKeepImmutableHistory() {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    edge(s, b, a);
    var source = read(s);
    var key = UUID.randomUUID();
    var receipt = accept(s, key, source.fingerprint());
    assertThat(receipt.planRevision()).isEqualTo("1");
    assertThat(current(s).matchesCurrent()).isTrue();
    assertThat(current(s).snapshot()).isEqualTo(source);
    assertThat(read(s).status()).isEqualTo("READY");
    assertThat(read(s).tasks()).allMatch(t -> t.status().equals("DRAFT"));
    assertThatThrownBy(() -> accept(s, UUID.randomUUID(), read(s).fingerprint()))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    jdbc.update(
        "update tasks set description='Changed',task_edit_revision=task_edit_revision+1 where id=?",
        a);
    assertThat(current(s).matchesCurrent()).isFalse();
    assertThat(current(s).snapshot()).isEqualTo(source);
    accept(s, UUID.randomUUID(), read(s).fingerprint());
    assertThat(current(s).planRevision()).isEqualTo("2");
    assertThat(current(s).matchesCurrent()).isTrue();
    jdbc.update("update missions set status='RUNNING' where id=?", s.mission());
    assertThat(accept(s, key, source.fingerprint())).isEqualTo(receipt);
    assertThat(read(s).status()).isEqualTo("RUNNING");
    for (String table : List.of("execution_runs", "mission_execution_controls"))
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?",
                  Long.class,
                  s.org()))
          .isZero();
  }

  @Test
  void staleFingerprintCycleAndLegacyPlanDenyWithoutWrites() {
    var s = fixture();
    var a = task(s);
    String old = read(s).fingerprint();
    var b = task(s);
    assertThatThrownBy(() -> accept(s, UUID.randomUUID(), old))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    edge(s, a, b);
    edge(s, b, a);
    assertThatThrownBy(() -> accept(s, UUID.randomUUID(), read(s).fingerprint()))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from accepted_mission_plans where organization_id=?",
                Long.class,
                s.org()))
        .isZero();
    jdbc.update("delete from task_dependencies where organization_id=?", s.org());
    jdbc.update(
        "insert into"
            + " accepted_mission_plans(organization_id,team_id,mission_id,plan_revision,accepted_by)"
            + " values(?,?,?,1,?)",
        s.org(),
        s.team(),
        s.mission(),
        s.human().id());
    assertThatThrownBy(() -> accept(s, UUID.randomUUID(), read(s).fingerprint()))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    assertThat(read(s).status()).isEqualTo("CREATED");
  }

  @Test
  void replayNeedsCurrentAdminAndConsistentSnapshot() {
    var s = fixture();
    var a = task(s);
    var key = UUID.randomUUID();
    var fp = read(s).fingerprint();
    accept(s, key, fp);
    assertThatThrownBy(() -> accept(s, key, "f".repeat(64)))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=?", s.org());
    assertThatThrownBy(() -> accept(s, key, fp)).isInstanceOf(AccessDeniedException.class);
    jdbc.update(
        "update organization_memberships set role='ADMIN' where organization_id=?", s.org());
    jdbc.update("update accepted_mission_plan_tasks set task_edit_revision=99 where task_id=?", a);
    assertThatThrownBy(() -> current(s))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    assertThatThrownBy(() -> accept(s, key, fp))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
  }

  @Test
  void httpRequiresCsrfStrictBodyAndScopedAdmin() throws Exception {
    var s = fixture();
    task(s);
    var auth =
        new UsernamePasswordAuthenticationToken(
            s.human(),
            null,
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_USER")));
    String path =
        "/api/v1/organizations/"
            + s.org()
            + "/teams/"
            + s.team()
            + "/missions/"
            + s.mission()
            + "/plan-review/accept";
    String body =
        "{\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"expectedFingerprint\":\""
            + read(s).fingerprint()
            + "\"}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(authentication(auth))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(authentication(auth))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(body.replace("}", ",\"extra\":true}")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(authentication(auth))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"));
  }

  @Test
  void receiptInsertFailureRollsBackPlanAndMission() {
    var s = fixture();
    task(s);
    var key = UUID.randomUUID();
    jdbc.execute(
        "alter table mission_plan_acceptance_versions add constraint reject_acceptance_test check"
            + " (idempotency_key <> '"
            + key
            + "')");
    try {
      assertThatThrownBy(() -> accept(s, key, read(s).fingerprint()))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute(
          "alter table mission_plan_acceptance_versions drop constraint reject_acceptance_test");
    }
    assertThat(read(s).status()).isEqualTo("CREATED");
    for (String table : List.of("accepted_mission_plans", "mission_plan_selections"))
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?",
                  Long.class,
                  s.org()))
          .isZero();
  }

  @Test
  void executionControlBlocksFreshAcceptance() {
    var s = fixture();
    task(s);
    jdbc.update(
        "insert into mission_execution_controls(organization_id,team_id,mission_id,revision,state)"
            + " values(?,?,?,1,'OPEN')",
        s.org(),
        s.team(),
        s.mission());
    assertThatThrownBy(() -> accept(s, UUID.randomUUID(), read(s).fingerprint()))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
    assertThat(read(s).status()).isEqualTo("CREATED");
  }

  @Test
  void concurrentSameCommandCreatesOneVersionAndReplays() throws Exception {
    var s = fixture();
    task(s);
    var key = UUID.randomUUID();
    var fp = read(s).fingerprint();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<
              com.agentos.mission.application.MissionPlanAcceptanceService.Receipt>
          call =
              () -> {
                start.await();
                return accept(s, key, fp);
              };
      var a = pool.submit(call);
      var b = pool.submit(call);
      start.countDown();
      assertThat(a.get(15, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(15, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from accepted_mission_plans where organization_id=?",
                Long.class,
                s.org()))
        .isEqualTo(1L);
  }
}

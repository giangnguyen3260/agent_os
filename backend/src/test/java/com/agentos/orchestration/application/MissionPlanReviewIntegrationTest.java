package com.agentos.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
      "spring.datasource.url=jdbc:h2:mem:plan_review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.mission-plan-review.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionPlanReviewIntegrationTest {
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

  @Test
  void completeGraphHasDependencyFirstOrderAndStableFingerprintWithoutWrites() {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    var c = task(s);
    edge(s, b, a);
    edge(s, c, b);
    var r = read(s);
    assertThat(r.canReviewConfiguration()).isTrue();
    assertThat(r.executionAuthorized()).isFalse();
    assertThat(r.order()).containsExactly(a, b, c);
    assertThat(read(s).fingerprint()).isEqualTo(r.fingerprint());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from accepted_mission_plans where organization_id=?",
                Integer.class,
                s.org()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_runs where organization_id=?",
                Integer.class,
                s.org()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select status from missions where id=?", String.class, s.mission()))
        .isEqualTo("CREATED");
  }

  @Test
  void cyclesCannotReturnAPartialExecutableOrder() {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    edge(s, a, b);
    edge(s, b, a);
    var r = read(s);
    assertThat(r.blockers()).contains("INVALID_DEPENDENCY_GRAPH");
    assertThat(r.order()).isEmpty();
    assertThat(r.tasks()).hasSize(2);
    assertThat(r.executionAuthorized()).isFalse();
  }

  @Test
  void scopeAndInputLimitsNeverPresentAPartialPlan() {
    var s = fixture();
    assertThat(read(s).blockers()).contains("NO_TASKS");
    for (int i = 0; i < 201; i++) task(s);
    var r = read(s);
    assertThat(r.taskCount()).isEqualTo(201);
    assertThat(r.tasks()).isEmpty();
    assertThat(r.canReviewConfiguration()).isFalse();
    assertThat(r.blockers()).contains("SCOPE_LIMIT_EXCEEDED");
    var large = fixture();
    for (int i = 0; i < 132; i++) task(large);
    jdbc.update(
        "update tasks set description=? where mission_id=?", "x".repeat(8000), large.mission());
    assertThat(read(large).blockers()).contains("INPUT_LIMIT_EXCEEDED");
    assertThat(read(large).tasks()).isEmpty();
  }

  @Test
  void currentOwnerAndContentChangesAffectAssessment() {
    var s = fixture();
    var id = task(s);
    String digest = read(s).fingerprint();
    jdbc.update(
        "update tasks set description='New input',task_edit_revision=task_edit_revision+1 where"
            + " id=?",
        id);
    assertThat(read(s).fingerprint()).isNotEqualTo(digest);
    jdbc.update("update tasks set priority=null where id=?", id);
    assertThat(read(s).blockers()).contains("TASK_INPUT_UNAVAILABLE");
    jdbc.update("update agents set status='SUSPENDED' where id=?", s.agent());
    assertThat(read(s).blockers()).contains("OWNER_UNAVAILABLE");
    jdbc.update("update tasks set status='READY' where id=?", id);
    assertThat(read(s).blockers()).contains("TASK_STATE_UNAVAILABLE");
  }

  @Test
  void adminScopeAndNoStoreAreEnforced() throws Exception {
    var s = fixture();
    task(s);
    var auth = authentication(new UsernamePasswordAuthenticationToken(s.human(), null, List.of()));
    String path =
        "/api/v1/organizations/"
            + s.org()
            + "/teams/"
            + s.team()
            + "/missions/"
            + s.mission()
            + "/plan-review";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).with(auth))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"));
    mvc.perform(get(path.replace(s.mission().toString(), UUID.randomUUID().toString())).with(auth))
        .andExpect(status().isNotFound());
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=?", s.org());
    mvc.perform(get(path).with(auth)).andExpect(status().isForbidden());
    assertThatThrownBy(() -> read(s)).isInstanceOf(AccessDeniedException.class);
  }
}

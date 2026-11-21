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
      "spring.datasource.url=jdbc:h2:mem:graph_admission;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.mission-plan-review.enabled=true",
      "agentos.mission-plan-acceptance.enabled=true",
      "agentos.mission-graph-admission.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionGraphAdmissionIntegrationTest {
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
  @Autowired MissionGraphAdmissionService admission;
  @Autowired ExecutionReviewDecisionStore decisions;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  MissionGraphAdmissionService.Assessment view(Scope s) {
    return admission.current(s.org(), s.team(), s.mission(), s.human());
  }

  void acceptPlan(Scope s) {
    acceptance.accept(
        s.org(), s.team(), s.mission(), s.human(), UUID.randomUUID(), read(s).fingerprint());
  }

  MissionGraphAdmissionService.Receipt admit(
      Scope s, UUID key, MissionGraphAdmissionService.Assessment a) {
    return admission.admit(
        s.org(), s.team(), s.mission(), s.human(), key, a.planRevision(), a.fingerprint());
  }

  UUID run(Scope s, UUID task) {
    return jdbc.queryForObject(
        "select run_id from mission_graph_admission_runs where organization_id=? and task_id=?",
        UUID.class,
        s.org(),
        task);
  }

  String taskStatus(UUID task) {
    return jdbc.queryForObject("select status from tasks where id=?", String.class, task);
  }

  void check(Scope s, UUID task) {
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              decisions.requireAdmin(s.org(), s.human(), true);
              new MissionGraphDependencyStore(jdbc, true).requireStart(s.org(), run(s, task));
            });
  }

  ExecutionReviewDecisionStore.Request evidence(Scope s, UUID task) throws Exception {
    UUID org = s.org(),
        team = s.team(),
        mission = s.mission(),
        agent = s.agent(),
        actor = s.human().id(),
        run = run(s, task),
        attempt = UUID.randomUUID();
    long base =
        jdbc.queryForObject("select revision from execution_runs where id=?", Long.class, run);
    jdbc.update(
        "update tasks set status='IN_REVIEW',pending_reason=null,waiting_since=null,resolver=null"
            + " where id=?",
        task);
    jdbc.update(
        "update execution_runs set status='IN_REVIEW',revision=? where id=?", base + 2, run);
    jdbc.update(
        "insert into execution_attempts(id,run_id,attempt_number,status,outcome_slot)"
            + " values(?,?,1,'SUCCEEDED',1)",
        attempt,
        run);
    String output =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("Verified output".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    jdbc.update(
        "insert into"
            + " execution_attempt_reconciliations(run_id,attempt_id,outcome,evidence_sha256,output_sha256,reconciled_at)"
            + " values(?,?,'SUCCEEDED',?,?,current_timestamp)",
        run,
        attempt,
        "a".repeat(64),
        output);
    jdbc.update(
        "insert into"
            + " execution_verified_output_content(run_id,attempt_id,output_sha256,content,stored_at)"
            + " values(?,?,?,'Verified output',current_timestamp)",
        run,
        attempt,
        output);
    jdbc.update(
        "insert into"
            + " execution_reconciled_review_submissions(run_id,attempt_id,source_run_revision,submitted_at)"
            + " values(?,?,?,current_timestamp)",
        run,
        attempt,
        base + 1);
    UUID connection = UUID.randomUUID(), action = UUID.randomUUID();
    byte[] request = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String requestSha =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request));
    jdbc.update(
        "insert into"
            + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
            + " values(?,?,'OPENAI',1,'synthetic-locator','1','1','v2','synthetic',current_timestamp)",
        org,
        connection);
    jdbc.update(
        "insert into"
            + " provider_connections(id,organization_id,provider,mode,name,state,configuration_revision,credential_revision,created_at,updated_at)"
            + " values(?,?,'OPENAI','OPENAI_API_KEY','Fixture','ENABLED',1,1,current_timestamp,current_timestamp)",
        connection,
        org);
    jdbc.update(
        "insert into"
            + " execution_claims(run_id,attempt_id,fence,worker_id,issued_at,expires_at,deadline_at,invalidated_at)"
            + " values(?,?,1,?,current_timestamp-interval '60' second,current_timestamp-interval"
            + " '30' second,current_timestamp,current_timestamp)",
        run,
        attempt,
        UUID.randomUUID());
    jdbc.update(
        "insert into"
            + " execution_budget_reservations(run_id,action_id,attempt_id,fence,reserved_microusd,status,actual_microusd)"
            + " values(?,?,?,1,10,'SETTLED',2)",
        run,
        action,
        attempt);
    jdbc.update(
        "insert into"
            + " execution_start_dispatch(id,organization_id,run_id,source_run_revision,actor_id,idempotency_key,actor_credential_version,state)"
            + " values(?,?,?,?,?,?,0,'FINISHED')",
        action,
        org,
        run,
        base,
        actor,
        UUID.randomUUID());
    jdbc.update(
        "insert into"
            + " execution_openai_requests(dispatch_id,connection_id,credential_revision,model_id,request_body,preflight_sha256,request_sha256,max_output_tokens,maximum_microusd,input_rate,output_rate)"
            + " values(?,?,1,'fixture-model',?,?,?,100,10000,1000000,1000000)",
        action,
        connection,
        request,
        "a".repeat(64),
        requestSha);
    jdbc.update(
        "insert into"
            + " execution_model_authorizations(organization_id,run_id,action_id,attempt_id,fence,agent_id,connection_id,credential_revision,model_id,request_sha256,authorized_at,control_revision,handed_off_at)"
            + " values(?,?,?,?,1,?,?,1,'fixture-model',?,current_timestamp,1,current_timestamp)",
        org,
        run,
        action,
        attempt,
        agent,
        connection,
        requestSha);
    jdbc.update(
        "insert into"
            + " execution_model_usage(organization_id,run_id,action_id,connection_id,provider_request_sha256,input_tokens,output_tokens,recorded_at)"
            + " values(?,?,?,?,?,1,1,current_timestamp)",
        org,
        run,
        action,
        connection,
        "b".repeat(64));
    return new ExecutionReviewDecisionStore.Request(
        attempt, base + 2, output, ExecutionReviewDecisionStore.Decision.ACCEPT, null);
  }

  void decide(Scope s, UUID task, ExecutionReviewDecisionStore.Request request) {
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(tx -> decisions.decide(s.org(), s.human(), run(s, task), request));
  }

  @Test
  void diamondAdmissionAndExactAcceptReleaseEntireGraphWithoutEnqueue() throws Exception {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    var c = task(s);
    var d = task(s);
    edge(s, b, a);
    edge(s, c, a);
    edge(s, d, b);
    edge(s, d, c);
    acceptPlan(s);
    var before = view(s);
    var key = UUID.randomUUID();
    var receipt = admit(s, key, before);
    assertThat(receipt.runs()).hasSize(4);
    assertThat(taskStatus(a)).isEqualTo("READY");
    assertThat(taskStatus(b)).isEqualTo("BLOCKED");
    check(s, a);
    assertThatThrownBy(() -> check(s, b)).isInstanceOf(IllegalStateException.class);
    decide(s, a, evidence(s, a));
    assertThat(taskStatus(b)).isEqualTo("READY");
    assertThat(taskStatus(c)).isEqualTo("READY");
    assertThat(taskStatus(d)).isEqualTo("BLOCKED");
    check(s, b);
    decide(s, b, evidence(s, b));
    assertThat(taskStatus(d)).isEqualTo("BLOCKED");
    decide(s, c, evidence(s, c));
    assertThat(taskStatus(d)).isEqualTo("READY");
    check(s, d);
    assertThat(admit(s, key, before)).isEqualTo(receipt);
    assertThat(
            jdbc.queryForObject(
                "select status from missions where id=?", String.class, s.mission()))
        .isEqualTo("RUNNING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_graph_dependency_releases x join execution_runs r on"
                    + " r.id=x.run_id where r.organization_id=?",
                Long.class,
                s.org()))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_start_dispatch d join execution_runs r on"
                    + " r.id=d.run_id where r.organization_id=?",
                Long.class,
                s.org()))
        .isEqualTo(3L); // only synthetic prerequisite evidence, no automatic enqueue
  }

  @Test
  void legacyCompletedTamperedOutputAndUnsettledHistoryCannotRelease() throws Exception {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    edge(s, b, a);
    acceptPlan(s);
    admit(s, UUID.randomUUID(), view(s));
    jdbc.update("update tasks set status='COMPLETED' where id=?", a);
    jdbc.update("update execution_runs set status='COMPLETED' where id=?", run(s, a));
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> new MissionGraphDependencyStore(jdbc, true).release(s.org(), run(s, a)));
    assertThat(taskStatus(b)).isEqualTo("BLOCKED");
    var request = evidence(s, a);
    jdbc.update(
        "update execution_verified_output_content set content='Tampered' where run_id=?",
        run(s, a));
    assertThatThrownBy(() -> decide(s, a, request)).isInstanceOf(IllegalStateException.class);
    assertThat(taskStatus(a)).isEqualTo("IN_REVIEW");
    jdbc.update(
        "update execution_verified_output_content set content='Verified output' where run_id=?",
        run(s, a));
    jdbc.update(
        "update execution_budget_reservations set status='UNKNOWN',actual_microusd=null where"
            + " run_id=?",
        run(s, a));
    assertThatThrownBy(() -> decide(s, a, request)).isInstanceOf(IllegalStateException.class);
    assertThat(taskStatus(b)).isEqualTo("BLOCKED");
    assertThatThrownBy(() -> check(s, b)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reworkDoesNotReleaseAndPauseAllowsDependencyMetadataOnly() throws Exception {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    edge(s, b, a);
    acceptPlan(s);
    admit(s, UUID.randomUUID(), view(s));
    var request = evidence(s, a);
    decide(
        s,
        a,
        new ExecutionReviewDecisionStore.Request(
            request.attempt(),
            request.revision(),
            request.outputSha256(),
            ExecutionReviewDecisionStore.Decision.REWORK,
            "Revise"));
    assertThat(taskStatus(b)).isEqualTo("BLOCKED");
    var other = fixture();
    var x = task(other);
    var y = task(other);
    edge(other, y, x);
    acceptPlan(other);
    admit(other, UUID.randomUUID(), view(other));
    var acceptedRequest = evidence(other, x);
    jdbc.update(
        "update mission_execution_controls set state='PAUSE_REQUESTED' where mission_id=?",
        other.mission());
    decide(other, x, acceptedRequest);
    assertThat(taskStatus(y)).isEqualTo("READY");
  }

  @Test
  void staleNewTaskAndLegacyRuntimeRejectAdmissionAtomically() {
    var s = fixture();
    task(s);
    acceptPlan(s);
    var prior = view(s);
    task(s);
    assertThatThrownBy(() -> admit(s, UUID.randomUUID(), prior))
        .isInstanceOf(MissionGraphAdmissionService.Conflict.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_runs where organization_id=?", Long.class, s.org()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select status from missions where id=?", String.class, s.mission()))
        .isEqualTo("READY");
  }

  @Test
  void admissionInsertFailureRollsBackEveryLifecycleWrite() {
    var s = fixture();
    task(s);
    task(s);
    acceptPlan(s);
    var key = UUID.randomUUID();
    jdbc.execute(
        "alter table mission_graph_admission_runs add constraint reject_graph_test"
            + " check(task_id <> '"
            + read(s).tasks().getLast().id()
            + "')");
    try {
      assertThatThrownBy(() -> admit(s, key, view(s)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("alter table mission_graph_admission_runs drop constraint reject_graph_test");
    }
    assertThat(read(s).status()).isEqualTo("READY");
    assertThat(read(s).tasks()).allMatch(t -> t.status().equals("DRAFT"));
    for (String table :
        List.of("execution_runs", "mission_execution_controls", "mission_graph_admissions"))
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?",
                  Long.class,
                  s.org()))
          .isZero();
  }

  @Test
  void currentAuthorityAndDisabledRuntimeGuardApplyToHistoricalCommands() {
    var s = fixture();
    var a = task(s);
    acceptPlan(s);
    var prior = view(s);
    var key = UUID.randomUUID();
    admit(s, key, prior);
    assertThatThrownBy(
            () -> new MissionGraphDependencyStore(jdbc, false).requireStart(s.org(), run(s, a)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("update tasks set description='Changed' where id=?", a);
    assertThatThrownBy(() -> check(s, a)).isInstanceOf(IllegalStateException.class);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=?", s.org());
    assertThatThrownBy(() -> admit(s, key, prior)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void sameKeyConcurrencyReturnsOneCompleteMapping() throws Exception {
    var s = fixture();
    task(s);
    task(s);
    acceptPlan(s);
    var prior = view(s);
    var key = UUID.randomUUID();
    var latch = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<MissionGraphAdmissionService.Receipt> call =
          () -> {
            latch.await();
            return admit(s, key, prior);
          };
      var a = pool.submit(call);
      var b = pool.submit(call);
      latch.countDown();
      assertThat(a.get(15, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(15, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(view(s).runs()).hasSize(2);
  }

  @Test
  void strictWireCsrfAndScopeChecks() throws Exception {
    var s = fixture();
    task(s);
    acceptPlan(s);
    var p = view(s);
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
            + "/graph-admission";
    String body =
        "{\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"expectedPlanRevision\":\"1\",\"expectedFingerprint\":\""
            + p.fingerprint()
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
                .content(body.replace("\"1\"", "1")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(authentication(auth))
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void disabledGraphCannotAcceptAndStrandDependents() throws Exception {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    edge(s, b, a);
    acceptPlan(s);
    admit(s, UUID.randomUUID(), view(s));
    var request = evidence(s, a);
    org.springframework.test.util.ReflectionTestUtils.setField(
        decisions, "graphAdmissionEnabled", false);
    try {
      assertThatThrownBy(() -> decide(s, a, request)).isInstanceOf(IllegalStateException.class);
      assertThat(taskStatus(a)).isEqualTo("IN_REVIEW");
      assertThat(taskStatus(b)).isEqualTo("BLOCKED");
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(
          decisions, "graphAdmissionEnabled", true);
    }
    decide(s, a, request);
    assertThat(taskStatus(b)).isEqualTo("READY");
  }

  @Test
  void competingKeysAdmitOnlyOnceAndSnapshotBoundsFailClosed() throws Exception {
    var s = fixture();
    task(s);
    acceptPlan(s);
    var prior = view(s);
    admit(s, UUID.randomUUID(), prior);
    assertThatThrownBy(() -> admit(s, UUID.randomUUID(), prior))
        .isInstanceOf(MissionGraphAdmissionService.Conflict.class);
    var other = fixture();
    task(other);
    acceptPlan(other);
    var original =
        acceptance.current(other.org(), other.team(), other.mission(), other.human()).snapshot();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var node = mapper.valueToTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("taskCount", 200);
    String content = mapper.writeValueAsString(node);
    jdbc.update(
        "update mission_plan_acceptance_versions set snapshot_json=?,snapshot_sha256=? where"
            + " organization_id=?",
        content,
        AcceptedRunOutputEvidence.sha(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        other.org());
    assertThatThrownBy(() -> view(other))
        .isInstanceOf(com.agentos.mission.application.MissionPlanAcceptanceService.Conflict.class);
  }
}

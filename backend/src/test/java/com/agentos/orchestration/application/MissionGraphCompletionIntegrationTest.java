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
      "spring.datasource.url=jdbc:h2:mem:graph_completion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.mission-plan-review.enabled=true",
      "agentos.mission-plan-acceptance.enabled=true",
      "agentos.mission-graph-admission.enabled=true",
      "agentos.mission-graph-completion.enabled=true",
      "agentos.mission-completion.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionGraphCompletionIntegrationTest {
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

  @Autowired MissionGraphCompletionService completion;
  @Autowired MissionCompletionService legacy;
  @Autowired com.agentos.mission.application.TaskCreateService taskCreates;

  Scope completed() throws Exception {
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
    admit(s, UUID.randomUUID(), view(s));
    for (UUID task : List.of(a, b, c, d)) decide(s, task, evidence(s, task));
    return s;
  }

  MissionGraphCompletionService.Snapshot current(Scope s) {
    return completion.current(s.org(), s.team(), s.mission(), s.human());
  }

  MissionGraphCompletionService.Receipt apply(
      Scope s, UUID key, MissionGraphCompletionService.Snapshot snapshot) {
    return completion.apply(
        s.org(), s.team(), s.mission(), s.human(), key, snapshot.action(), snapshot.fingerprint());
  }

  @Test
  void completeDiamondWithStableManifestTwoStepsAndHistoricalReplay() throws Exception {
    var s = completed();
    var first = current(s);
    assertThat(first.action()).isEqualTo(MissionGraphCompletionService.Action.SUBMIT_REVIEW);
    assertThat(first.outputs()).hasSize(4);
    var key = UUID.randomUUID();
    var receipt = apply(s, key, first);
    assertThat(receipt.status()).isEqualTo("REVIEW");
    var review = current(s);
    assertThat(review.manifestSha256()).isEqualTo(first.manifestSha256());
    assertThat(review.action()).isEqualTo(MissionGraphCompletionService.Action.COMPLETE);
    var output = review.outputs().getFirst();
    assertThat(
            completion
                .output(
                    s.org(),
                    s.team(),
                    s.mission(),
                    s.human(),
                    output.runId(),
                    output.attemptId(),
                    output.outputSha256())
                .content())
        .isEqualTo("Verified output");
    var done = apply(s, UUID.randomUUID(), review);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(current(s).action()).isNull();
    assertThat(current(s).blockers()).isEmpty();
    assertThat(apply(s, key, first)).isEqualTo(receipt);
    assertThat(current(s).status()).isEqualTo("COMPLETED");
  }

  @Test
  void incompleteAndExtraScopeCannotClose() throws Exception {
    var s = fixture();
    task(s);
    task(s);
    acceptPlan(s);
    admit(s, UUID.randomUUID(), view(s));
    assertThat(current(s).action()).isNull();
    var full = completed();
    task(full);
    assertThat(current(full).blockers()).contains("UNSUPPORTED_SCOPE");
    assertThat(current(full).action()).isNull();
  }

  @Test
  void sourceAndAcceptedEvidenceAreRecheckedAtEachPhase() throws Exception {
    var s = completed();
    apply(s, UUID.randomUUID(), current(s));
    var before = current(s);
    var item = before.outputs().getFirst();
    jdbc.update(
        "update execution_verified_output_content set content='Tampered' where run_id=?",
        item.runId());
    assertThat(current(s).action()).isNull();
    assertThatThrownBy(() -> apply(s, UUID.randomUUID(), before))
        .isInstanceOf(MissionGraphCompletionService.Conflict.class);
    jdbc.update(
        "update execution_verified_output_content set content='Verified output' where run_id=?",
        item.runId());
    jdbc.update("update tasks set description='Changed' where id=?", item.taskId());
    assertThat(current(s).blockers()).contains("PLAN_CONFIGURATION_UNAVAILABLE");
  }

  @Test
  void historicalUnsettledWorkAndPauseCancellationBlockCorrectly() throws Exception {
    var s = completed();
    var item = current(s).outputs().getFirst();
    jdbc.update(
        "update execution_budget_reservations set status='UNKNOWN',actual_microusd=null where"
            + " run_id=?",
        item.runId());
    assertThat(current(s).action()).isNull();
    jdbc.update(
        "update execution_budget_reservations set status='SETTLED',actual_microusd=2 where"
            + " run_id=?",
        item.runId());
    jdbc.update(
        "update mission_execution_controls set state='PAUSE_REQUESTED' where mission_id=?",
        s.mission());
    assertThat(current(s).action()).isEqualTo(MissionGraphCompletionService.Action.SUBMIT_REVIEW);
    jdbc.update(
        "update mission_execution_controls set state='CANCEL_REQUESTED' where mission_id=?",
        s.mission());
    assertThat(current(s).action()).isNull();
  }

  @Test
  void receiptFailureRollsBackMissionControlAndAudit() throws Exception {
    var s = completed();
    var before = current(s);
    var key = UUID.randomUUID();
    jdbc.execute(
        "alter table mission_graph_completion_receipts add constraint reject_close_test"
            + " check(idempotency_key <> '"
            + key
            + "')");
    try {
      assertThatThrownBy(() -> apply(s, key, before))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute(
          "alter table mission_graph_completion_receipts drop constraint reject_close_test");
    }
    assertThat(current(s)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_events where organization_id=? and"
                    + " event_type='MISSION_GRAPH_REVIEW_SUBMITTED'",
                Long.class,
                s.org()))
        .isZero();
  }

  @Test
  void reviewManifestCorruptionAndRevisionHeadroomFailClosed() throws Exception {
    var s = completed();
    jdbc.update(
        "update mission_execution_controls set revision=? where mission_id=?",
        Long.MAX_VALUE - 1,
        s.mission());
    assertThat(current(s).action()).isNull();
    jdbc.update(
        "update mission_execution_controls set revision=? where mission_id=?",
        Long.MAX_VALUE - 2,
        s.mission());
    apply(s, UUID.randomUUID(), current(s));
    var review = current(s);
    assertThat(review.action()).isEqualTo(MissionGraphCompletionService.Action.COMPLETE);
    jdbc.update(
        "update mission_graph_completion_receipts set manifest_json='[]' where organization_id=?",
        s.org());
    assertThat(current(s).action()).isNull();
  }

  @Test
  void singleAdmittedTaskCannotUseLegacyClosureAndIntakeIsFrozen() throws Exception {
    var s = fixture();
    var body =
        new com.agentos.mission.api.dto.TaskCreateRequest(
            "Created", "Input", "Output", "NORMAL", s.agent(), List.of(), 0L);
    var key = UUID.randomUUID();
    var created = taskCreates.create(s.org(), s.team(), s.mission(), s.human(), key, body);
    acceptPlan(s);
    admit(s, UUID.randomUUID(), view(s));
    assertThat(taskCreates.context(s.org(), s.team(), s.mission(), s.human()).canCreate())
        .isFalse();
    assertThat(taskCreates.create(s.org(), s.team(), s.mission(), s.human(), key, body).response())
        .isEqualTo(created.response());
    assertThatThrownBy(
            () ->
                taskCreates.create(
                    s.org(), s.team(), s.mission(), s.human(), UUID.randomUUID(), body))
        .isInstanceOf(com.agentos.mission.application.MissionCreateService.Conflict.class);
    var task = view(s).runs().getFirst().taskId();
    decide(s, task, evidence(s, task));
    assertThat(legacy.current(s.org(), s.team(), s.mission(), s.human()).blockers())
        .contains("UNSUPPORTED_SCOPE");
    assertThat(current(s).action()).isEqualTo(MissionGraphCompletionService.Action.SUBMIT_REVIEW);
  }

  @Test
  void currentAdminAndBoundedOutputIdentityAreRequired() throws Exception {
    var s = completed();
    var prior = current(s);
    var item = prior.outputs().getFirst();
    var key = UUID.randomUUID();
    apply(s, key, prior);
    assertThatThrownBy(
            () ->
                completion.output(
                    s.org(),
                    s.team(),
                    s.mission(),
                    s.human(),
                    item.runId(),
                    UUID.randomUUID(),
                    item.outputSha256()))
        .isInstanceOf(MissionGraphCompletionService.Conflict.class);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=?", s.org());
    assertThatThrownBy(() -> apply(s, key, prior)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void sameKeyConcurrencyCreatesOneReceiptWithoutReset() throws Exception {
    var s = completed();
    var p = current(s);
    var key = UUID.randomUUID();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<MissionGraphCompletionService.Receipt> call =
          () -> {
            start.await();
            return apply(s, key, p);
          };
      var a = pool.submit(call);
      var b = pool.submit(call);
      start.countDown();
      assertThat(a.get(15, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(15, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(current(s).status()).isEqualTo("REVIEW");
  }

  @Test
  void strictWireAndCsrfProtectBothTransitions() throws Exception {
    var s = completed();
    var p = current(s);
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
            + "/graph-completion";
    String body =
        "{\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"action\":\"SUBMIT_REVIEW\",\"expectedFingerprint\":\""
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
  void sourceSnapshotDigestIsPinnedAcrossReviewAndCompletion() throws Exception {
    var s = completed();
    apply(s, UUID.randomUUID(), current(s));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    String raw =
        jdbc.queryForObject(
            "select snapshot_json from mission_plan_acceptance_versions where organization_id=?",
            String.class,
            s.org());
    var node = mapper.readTree(raw);
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("tasks").get(0))
        .put("ownerName", "Another display name");
    String changed = mapper.writeValueAsString(node);
    jdbc.update(
        "update mission_plan_acceptance_versions set snapshot_json=?,snapshot_sha256=? where"
            + " organization_id=?",
        changed,
        AcceptedRunOutputEvidence.sha(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        s.org());
    assertThat(current(s).blockers()).contains("COMPLETION_REVIEW_UNAVAILABLE");
    assertThat(current(s).action()).isNull();
  }

  @Test
  void terminalReadsRemainValidAtMaximumControlRevision() throws Exception {
    var s = completed();
    jdbc.update(
        "update mission_execution_controls set revision=? where mission_id=?",
        Long.MAX_VALUE - 2,
        s.mission());
    apply(s, UUID.randomUUID(), current(s));
    var review = current(s);
    assertThat(review.action()).isEqualTo(MissionGraphCompletionService.Action.COMPLETE);
    apply(s, UUID.randomUUID(), review);
    assertThat(current(s).status()).isEqualTo("COMPLETED");
    assertThat(current(s).blockers()).isEmpty();
  }

  @Test
  void downloadRequiresExactFreshAcceptedEvidenceAndCurrentAdmin() throws Exception {
    var s = completed();
    var item = current(s).outputs().getFirst();
    String path =
        "/api/v1/organizations/"
            + s.org()
            + "/teams/"
            + s.team()
            + "/missions/"
            + s.mission()
            + "/graph-completion/outputs/"
            + item.runId()
            + "/download";
    var auth = authentication(new UsernamePasswordAuthenticationToken(s.human(), null, List.of()));
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
            .param("attemptId", item.attemptId().toString())
            .param("outputSha256", item.outputSha256())
            .with(auth);
    var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    assertThat(response.getContentAsByteArray())
        .isEqualTo("Verified output".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-AgentOS-Output-SHA256")).isEqualTo(item.outputSha256());
    assertThat(response.getHeader("Content-Type"))
        .contains("text/plain")
        .containsIgnoringCase("utf-8");
    assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo(
            "attachment; filename=\"accepted-" + item.runId() + "-" + item.attemptId() + ".txt\"");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(auth)
                .param("attemptId", UUID.randomUUID().toString())
                .param("outputSha256", item.outputSha256()))
        .andExpect(status().isConflict());
    jdbc.update(
        "update execution_verified_output_content set content='changed' where run_id=?",
        item.runId());
    mvc.perform(request).andExpect(status().isConflict());
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=?", s.human().id());
    mvc.perform(request).andExpect(status().isForbidden());
  }

  @Autowired ExecutionReviewService reviewReads;

  @Test
  void accountingUsesFullRecordedLedgerAndKeepsUnknownSeparate() throws Exception {
    var s = completed();
    var run =
        run(
            s,
            jdbc.queryForObject(
                "select task_id from execution_runs where mission_id=? order by id limit 1",
                UUID.class,
                s.mission()));
    var before = reviewReads.run(s.org(), run, s.human()).accounting();
    assertThat(before.settledMicrousd()).isEqualTo("2");
    assertThat(before.inputTokens()).isEqualTo("1");
    assertThat(before.outputTokens()).isEqualTo("1");
    jdbc.update(
        "update execution_budget_reservations set status='UNKNOWN',actual_microusd=null where"
            + " run_id=?",
        run);
    var unknown = reviewReads.run(s.org(), run, s.human()).accounting();
    assertThat(unknown.settledMicrousd()).isEqualTo("0");
    assertThat(unknown.unknownMicrousd()).isEqualTo("10");
    assertThat(unknown.unresolvedReservations()).isEqualTo("1");
    jdbc.update("update execution_budget_reservations set status='HELD' where run_id=?", run);
    var held = reviewReads.run(s.org(), run, s.human()).accounting();
    assertThat(held.heldMicrousd()).isEqualTo("10");
    assertThat(held.unknownMicrousd()).isEqualTo("0");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=?", s.human().id());
    assertThatThrownBy(() -> reviewReads.run(s.org(), run, s.human()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void missionAccountingIncludesAllRunsButExcludesOtherMissions() throws Exception {
    var s = completed();
    completed();
    var initial = current(s).accounting();
    assertThat(initial.settledMicrousd()).isEqualTo("8");
    assertThat(initial.inputTokens()).isEqualTo("4");
    assertThat(initial.outputTokens()).isEqualTo("4");
    var target = current(s).outputs().getFirst().runId();
    jdbc.update(
        "update execution_budget_reservations set status='UNKNOWN',actual_microusd=null where"
            + " run_id=?",
        target);
    var pending = current(s).accounting();
    assertThat(pending.settledMicrousd()).isEqualTo("6");
    assertThat(pending.unknownMicrousd()).isEqualTo("10");
    assertThat(pending.unresolvedReservations()).isEqualTo("1");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=?", s.human().id());
    assertThatThrownBy(() -> current(s)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void dispatchHistoryIsScopedBoundedAndDoesNotInferSuccessFromFinished() throws Exception {
    var s = completed();
    var run = current(s).outputs().getFirst().runId();
    var before = reviewReads.run(s.org(), run, s.human()).dispatches();
    assertThat(before).hasSize(1);
    assertThat(before.getFirst().state()).isEqualTo("FINISHED");
    assertThat(before.getFirst().handedOff()).isTrue();
    for (int i = 0; i < 103; i++)
      jdbc.update(
          "insert into"
              + " execution_start_dispatch(id,organization_id,run_id,source_run_revision,actor_id,idempotency_key,actor_credential_version,state)"
              + " values(?,?,?,?,?,?,0,?)",
          UUID.randomUUID(),
          s.org(),
          run,
          100 + i,
          s.human().id(),
          UUID.randomUUID(),
          i % 2 == 0 ? "UNKNOWN" : "QUEUED");
    var history = reviewReads.run(s.org(), run, s.human()).dispatches();
    assertThat(history).hasSize(100);
    assertThat(history).anyMatch(d -> d.state().equals("UNKNOWN"));
    var other = completed();
    assertThat(
            reviewReads
                .run(other.org(), current(other).outputs().getFirst().runId(), other.human())
                .dispatches())
        .hasSize(1);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=?", s.human().id());
    assertThatThrownBy(() -> reviewReads.run(s.org(), run, s.human()))
        .isInstanceOf(AccessDeniedException.class);
  }
}

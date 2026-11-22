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
      "spring.datasource.url=jdbc:h2:mem:execution_controls;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.mission-plan-review.enabled=true",
      "agentos.mission-plan-acceptance.enabled=true",
      "agentos.mission-graph-admission.enabled=true",
      "agentos.mission-graph-completion.enabled=true",
      "agentos.mission-completion.enabled=true",
      "agentos.mission-execution-controls.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionExecutionControlsIntegrationTest {
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

  MissionGraphCompletionService.Snapshot current(Scope s) {
    return completion.current(s.org(), s.team(), s.mission(), s.human());
  }

  MissionGraphCompletionService.Receipt apply(
      Scope s, UUID key, MissionGraphCompletionService.Snapshot snapshot) {
    return completion.apply(
        s.org(), s.team(), s.mission(), s.human(), key, snapshot.action(), snapshot.fingerprint());
  }

  @Autowired MissionExecutionControlsService controlService;
  @Autowired ExecutionStartService starts;
  @Autowired ExecutionDispatchClaimService dispatcher;
  @Autowired ExecutionClaimStore claims;
  @Autowired ExecutionBudgetStore budgets;

  Scope admitted() {
    var s = fixture();
    var a = task(s);
    var b = task(s);
    edge(s, b, a);
    acceptPlan(s);
    admit(s, UUID.randomUUID(), view(s));
    return s;
  }

  MissionExecutionControlsService.Snapshot controls(Scope s) {
    return controlService.current(s.org(), s.team(), s.mission(), s.human());
  }

  MissionExecutionControlsService.Command command(
      Scope s, MissionExecutionControlsService.Action action) {
    return new MissionExecutionControlsService.Command(
        UUID.randomUUID(), controls(s).fingerprint(), action);
  }

  MissionExecutionControlsService.Result control(
      Scope s, MissionExecutionControlsService.Command command) {
    return controlService.apply(s.org(), s.team(), s.mission(), s.human(), command);
  }

  void act(Scope s, MissionExecutionControlsService.Action action) {
    control(s, command(s, action));
  }

  UUID root(Scope s) {
    return jdbc.queryForObject(
        "select task_id from mission_graph_admission_runs where mission_id=? and is_root=true",
        UUID.class,
        s.mission());
  }

  ExecutionStartService.Receipt queue(Scope s) {
    UUID run = run(s, root(s));
    long revision =
        jdbc.queryForObject("select revision from execution_runs where id=?", Long.class, run);
    return starts.start(s.org(), run, revision, UUID.randomUUID(), s.human());
  }

  ExecutionClaimStore.Token claim(Scope s, UUID dispatch) {
    var now = java.time.OffsetDateTime.now();
    return dispatcher.claim(
        s.org(), dispatch, UUID.randomUUID(), now.plusSeconds(60), now.plusSeconds(90));
  }

  void fund(Scope s) {
    var now = java.time.OffsetDateTime.now();
    for (var scope :
        java.util.Map.of(
                "ORGANIZATION",
                s.org(),
                "TEAM",
                s.team(),
                "AGENT",
                s.agent(),
                "MISSION",
                s.mission())
            .entrySet()) {
      jdbc.update(
          "insert into"
              + " execution_budget_buckets(id,organization_id,scope,subject_id,opens_at,closes_at,limit_microusd)"
              + " values(?,?,?,?,?,?,10000)",
          UUID.randomUUID(),
          s.org(),
          scope.getKey(),
          scope.getValue(),
          now.minusHours(1),
          now.plusHours(1));
      jdbc.update(
          "insert into"
              + " execution_capacity_limits(organization_id,scope,subject_id,max_concurrent,max_admissions,window_seconds)"
              + " values(?,?,?,5,100,60)",
          s.org(),
          scope.getKey(),
          scope.getValue());
    }
  }

  OpenAiExecutionFlow flow() {
    return new OpenAiExecutionFlow(
        jdbc,
        manager,
        starts,
        dispatcher,
        claims,
        budgets,
        null,
        null,
        null,
        null,
        decisions,
        null,
        null,
        org.mockito.Mockito.mock(com.agentos.model.infrastructure.OpenAiTextTransport.class),
        new OpenAiExecutionFlow.Settings("fixture-model", 100, 1000000, 1000000, 20000),
        java.time.Clock.systemUTC());
  }

  Object job(Scope s, ExecutionStartService.Receipt d) throws Exception {
    var ctor =
        Class.forName(OpenAiExecutionFlow.class.getName() + "$Job").getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    return ctor.newInstance(
        s.org(),
        d.dispatchId(),
        d.runId(),
        s.human().id(),
        0L,
        UUID.randomUUID(),
        1L,
        "fixture-model",
        new byte[] {1},
        "a".repeat(64),
        100,
        20000L,
        1000000L,
        1000000L);
  }

  @Test
  void pauseRetainsQueueResumeDoesNotCreateAnotherStartAndReplayIsHistorical() throws Exception {
    var s = admitted();
    var d = queue(s);
    prepareRequest(s, d);
    var pause = command(s, MissionExecutionControlsService.Action.PAUSE);
    var receipt = control(s, pause).receipt();
    assertThat(controls(s).safelyPaused()).isTrue();
    assertThat(controls(s).work().queued()).isEqualTo(1);
    assertThatThrownBy(() -> claim(s, d.dispatchId())).isInstanceOf(IllegalStateException.class);
    var open = admitted();
    var openDispatch = queue(open);
    prepareRequest(open, openDispatch);
    flow().poll();
    assertThat(
            jdbc.queryForObject(
                "select state from execution_start_dispatch where id=?",
                String.class,
                openDispatch.dispatchId()))
        .isEqualTo("FINISHED");
    var pausedJob = job(s, d);
    var reject = OpenAiExecutionFlow.class.getDeclaredMethod("rejectQueued", pausedJob.getClass());
    reject.setAccessible(true);
    reject.invoke(flow(), pausedJob);
    assertThat(
            jdbc.queryForObject(
                "select state from execution_start_dispatch where id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("QUEUED");
    act(s, MissionExecutionControlsService.Action.RESUME);
    assertThat(controls(s).controlState()).isEqualTo("OPEN");
    assertThat(controls(s).work().queued()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_attempts where run_id=?", Long.class, d.runId()))
        .isZero();
    var replay = control(s, pause);
    assertThat(replay.replay()).isTrue();
    assertThat(replay.receipt()).isEqualTo(receipt);
    assertThat(
            controlService.receipt(
                s.org(), s.team(), s.mission(), s.human(), pause.idempotencyKey()))
        .isEqualTo(receipt);
    assertThat(controls(s).controlState()).isEqualTo("OPEN");
  }

  @Test
  void quiescentCancelRetiresUnusedQueueThenExplicitAckCancelsFullGraph() {
    var s = admitted();
    var d = queue(s);
    var c = command(s, MissionExecutionControlsService.Action.REQUEST_CANCEL);
    var r = control(s, c).receipt();
    assertThat(r.retiredQueuedDispatchIds()).containsExactly(d.dispatchId());
    assertThat(controls(s).missionStatus()).isEqualTo("RUNNING");
    assertThat(controls(s).runs()).allMatch(run -> run.status().equals("CANCELLING"));
    assertThat(controls(s).canAcknowledgeCancel()).isTrue();
    assertThat(
            starts.start(
                s.org(),
                d.runId(),
                d.sourceRevision(),
                jdbc.queryForObject(
                    "select idempotency_key from execution_start_dispatch where id=?",
                    UUID.class,
                    d.dispatchId()),
                s.human()))
        .isEqualTo(d);
    var ack = command(s, MissionExecutionControlsService.Action.ACK_CANCEL);
    control(s, ack);
    assertThat(controls(s).missionStatus()).isEqualTo("CANCELLED");
    assertThat(controls(s).runs())
        .allMatch(run -> run.status().equals("CANCELLED") && run.taskStatus().equals("CANCELLED"));
    assertThat(controls(s).canResume()).isFalse();
    assertThat(control(s, c).receipt()).isEqualTo(r);
    assertThat(control(s, ack).replay()).isTrue();
  }

  @Test
  void claimedOrUnknownWorkNeverPermitsCancellationAndResumeWaitsForDrain() {
    var s = admitted();
    var d = queue(s);
    var token = claim(s, d.dispatchId());
    assertThat(controls(s).canRequestCancel()).isFalse();
    act(s, MissionExecutionControlsService.Action.PAUSE);
    assertThat(controls(s).safelyPaused()).isFalse();
    assertThat(controls(s).canResume()).isFalse();
    assertThatThrownBy(() -> act(s, MissionExecutionControlsService.Action.REQUEST_CANCEL))
        .isInstanceOf(MissionExecutionControlsService.Conflict.class);
    jdbc.update("update execution_start_dispatch set state='UNKNOWN' where id=?", d.dispatchId());
    jdbc.update("update execution_attempts set status='UNKNOWN' where id=?", token.attempt());
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(tx -> claims.invalidate(token));
    assertThat(controls(s).canRequestCancel()).isFalse();
    assertThat(controls(s).safelyPaused()).isFalse();
    assertThat(controls(s).work().unknownAttempts()).isEqualTo(1);
  }

  @Test
  void pausedUnsentWorkerReleasesExactHeldBudgetAndRequiresFreshManualStart() throws Exception {
    var s = admitted();
    var d = queue(s);
    var token = claim(s, d.dispatchId());
    fund(s);
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> assertThat(budgets.reserve(token, d.dispatchId(), 100)).isTrue());
    act(s, MissionExecutionControlsService.Action.PAUSE);
    assertThat(controls(s).canResume()).isFalse();
    var flow = flow();
    var job = job(s, d);
    var method =
        OpenAiExecutionFlow.class.getDeclaredMethod(
            "uncertain", job.getClass(), ExecutionClaimStore.Token.class, boolean.class);
    method.setAccessible(true);
    method.invoke(flow, job, token, false);
    assertThat(
            jdbc.queryForObject(
                "select status from execution_runs where id=?", String.class, d.runId()))
        .isEqualTo("CREATED");
    assertThat(
            jdbc.queryForObject(
                "select status from execution_attempts where id=?", String.class, token.attempt()))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "select actual_microusd from execution_budget_reservations where action_id=?",
                Long.class,
                d.dispatchId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select sum(held_microusd) from execution_budget_buckets where organization_id=?",
                Long.class,
                s.org()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_pause_unsent_releases where dispatch_id=?",
                Long.class,
                d.dispatchId()))
        .isEqualTo(1);
    assertThat(controls(s).safelyPaused()).isTrue();
    assertThat(controls(s).canResume()).isTrue();
    act(s, MissionExecutionControlsService.Action.RESUME);
    assertThat(
            jdbc.queryForObject(
                "select state from execution_start_dispatch where id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("FINISHED");
    var fresh = queue(s);
    assertThat(fresh.dispatchId()).isNotEqualTo(d.dispatchId());
    assertThat(fresh.sourceRevision()).isGreaterThan(d.sourceRevision());
  }

  @Test
  void oldTokenCannotRestorePausedAttemptAndReleaseFailureRollsBack() {
    var s = admitted();
    var d = queue(s);
    var token = claim(s, d.dispatchId());
    act(s, MissionExecutionControlsService.Action.PAUSE);
    var store = new ExecutionPausedDispatchStore(jdbc, claims, budgets);
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        store.releaseUnsent(
                            new ExecutionClaimStore.Token(
                                token.organization(),
                                token.run(),
                                token.attempt(),
                                UUID.randomUUID(),
                                token.fence()),
                            d.dispatchId())))
        .isInstanceOf(IllegalStateException.class);
    jdbc.execute(
        "alter table execution_pause_unsent_releases add constraint reject_pause_release"
            + " check(dispatch_id <> '"
            + d.dispatchId()
            + "')");
    try {
      assertThatThrownBy(() -> tx.execute(status -> store.releaseUnsent(token, d.dispatchId())))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute(
          "alter table execution_pause_unsent_releases drop constraint reject_pause_release");
    }
    assertThat(
            jdbc.queryForObject(
                "select state from execution_start_dispatch where id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("CLAIMED");
    assertThat(
            jdbc.queryForObject(
                "select status from execution_runs where id=?", String.class, d.runId()))
        .isEqualTo("RUNNING");
  }

  @Test
  void requestReceiptFailureRollsBackQueueControlRunsAndAudit() {
    var s = admitted();
    var d = queue(s);
    var before = controls(s);
    var command = command(s, MissionExecutionControlsService.Action.REQUEST_CANCEL);
    jdbc.execute(
        "alter table mission_execution_control_receipts add constraint reject_control_receipt"
            + " check(idempotency_key <> '"
            + command.idempotencyKey()
            + "')");
    try {
      assertThatThrownBy(() -> control(s, command))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute(
          "alter table mission_execution_control_receipts drop constraint reject_control_receipt");
    }
    assertThat(controls(s)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select state from execution_start_dispatch where id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("QUEUED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_events where mission_id=? and"
                    + " event_type='MISSION_EXECUTION_CONTROL_CHANGED'",
                Long.class,
                s.mission()))
        .isZero();
  }

  @Test
  void freshFingerprintAuthorityScopeAndChangedBodyAreRequired() {
    var s = admitted();
    var pause = command(s, MissionExecutionControlsService.Action.PAUSE);
    queue(s);
    assertThatThrownBy(() -> control(s, pause))
        .isInstanceOf(MissionExecutionControlsService.Conflict.class);
    var actual = command(s, MissionExecutionControlsService.Action.PAUSE);
    control(s, actual);
    assertThatThrownBy(
            () ->
                control(
                    s,
                    new MissionExecutionControlsService.Command(
                        actual.idempotencyKey(),
                        actual.expectedFingerprint(),
                        MissionExecutionControlsService.Action.RESUME)))
        .isInstanceOf(MissionExecutionControlsService.Conflict.class);
    var other = fixture();
    assertThatThrownBy(
            () -> controlService.current(other.org(), s.team(), s.mission(), other.human()))
        .isInstanceOf(MissionExecutionControlsService.Conflict.class);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=?", s.human().id());
    assertThatThrownBy(() -> control(s, actual)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void cancellationKeepsAcceptedCompletedOutputAndRefusesUnfinishedDispatchAtAck()
      throws Exception {
    var s = admitted();
    var a = root(s);
    decide(s, a, evidence(s, a));
    assertThat(controls(s).canRequestCancel()).isTrue();
    act(s, MissionExecutionControlsService.Action.REQUEST_CANCEL);
    var remaining =
        controls(s).runs().stream()
            .filter(r -> r.status().equals("CANCELLING"))
            .findFirst()
            .orElseThrow();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " execution_start_dispatch(id,organization_id,run_id,source_run_revision,actor_id,idempotency_key,actor_credential_version,state)"
            + " values(?,?,?,?,?,?,0,'UNKNOWN')",
        id,
        s.org(),
        remaining.runId(),
        100,
        s.human().id(),
        UUID.randomUUID());
    assertThat(controls(s).canAcknowledgeCancel()).isFalse();
    jdbc.update("update execution_start_dispatch set state='FINISHED' where id=?", id);
    act(s, MissionExecutionControlsService.Action.ACK_CANCEL);
    assertThat(taskStatus(a)).isEqualTo("COMPLETED");
    assertThat(new AcceptedRunOutputEvidence(jdbc).current(s.org(), run(s, a), 1)).isNotNull();
  }

  @Test
  void terminalRevisionHeadroomAndUnsupportedLegacyFailClosed() {
    var legacy = fixture();
    assertThat(controls(legacy).canPause()).isFalse();
    var s = admitted();
    jdbc.update(
        "update mission_execution_controls set revision=? where mission_id=?",
        Long.MAX_VALUE - 2,
        s.mission());
    assertThat(controls(s).canPause()).isFalse();
    assertThat(controls(s).canRequestCancel()).isTrue();
    act(s, MissionExecutionControlsService.Action.REQUEST_CANCEL);
    act(s, MissionExecutionControlsService.Action.ACK_CANCEL);
    assertThat(controls(s).missionStatus()).isEqualTo("CANCELLED");
    assertThat(controls(s).controlRevision()).isEqualTo(Long.toString(Long.MAX_VALUE));
    assertThat(controls(s).blockers()).doesNotContain("REVISION_HEADROOM_UNAVAILABLE");
  }

  @Test
  void ackAuditFailureRollsBackCancellationProjection() {
    var s = admitted();
    act(s, MissionExecutionControlsService.Action.REQUEST_CANCEL);
    var before = controls(s);
    jdbc.execute(
        "alter table audit_events add constraint reject_control_audit check(event_type <>"
            + " 'MISSION_EXECUTION_CONTROL_CHANGED' or payload not like"
            + " '%\"action\":\"ACK_CANCEL\"%' or mission_id <> '"
            + s.mission()
            + "')");
    try {
      assertThatThrownBy(() -> act(s, MissionExecutionControlsService.Action.ACK_CANCEL))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("alter table audit_events drop constraint reject_control_audit");
    }
    assertThat(controls(s)).isEqualTo(before);
  }

  @Test
  void simultaneousPauseCommandsCommitOnlyOneAndReplayHashMustRemainExact() throws Exception {
    var s = admitted();
    var c = command(s, MissionExecutionControlsService.Action.PAUSE);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var one =
          executor.submit(
              () -> {
                gate.await();
                return control(s, c);
              });
      var two =
          executor.submit(
              () -> {
                gate.await();
                return control(s, c);
              });
      gate.countDown();
      var a = one.get(30, java.util.concurrent.TimeUnit.SECONDS);
      var b = two.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(a.replay()).isNotEqualTo(b.replay());
      assertThat(a.receipt()).isEqualTo(b.receipt());
    }
    jdbc.update(
        "update mission_execution_control_receipts set response_sha256=? where mission_id=?",
        "f".repeat(64),
        s.mission());
    assertThatThrownBy(() -> control(s, c))
        .isInstanceOf(MissionExecutionControlsService.Conflict.class);
  }

  @Test
  void csrfStrictWireAndUnrecognizedActionCannotMutate() throws Exception {
    var s = admitted();
    String path =
        "/api/v1/organizations/"
            + s.org()
            + "/teams/"
            + s.team()
            + "/missions/"
            + s.mission()
            + "/execution-controls";
    var auth = authentication(new UsernamePasswordAuthenticationToken(s.human(), null, List.of()));
    String raw =
        "{\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"expectedFingerprint\":\""
            + controls(s).fingerprint()
            + "\",\"action\":\"PAUSE\"}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(auth)
                .contentType("application/json")
                .content(raw))
        .andExpect(status().isForbidden());
    for (String invalid :
        List.of(
            raw.replace("\"PAUSE\"", "true"),
            raw.replace("PAUSE", "DELETE"),
            raw.replace("\"action\":\"PAUSE\"", "\"action\":\"PAUSE\",\"action\":\"RESUME\""),
            raw + " {}"))
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                  .with(auth)
                  .with(
                      org.springframework.security.test.web.servlet.request
                          .SecurityMockMvcRequestPostProcessors.csrf())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    assertThat(controls(s).controlState()).isEqualTo("OPEN");
  }

  void authorizeFixture(Scope s, ExecutionStartService.Receipt d, ExecutionClaimStore.Token token) {
    UUID connection = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
            + " values(?,?,'OPENAI',1,'fixture','1','1','v2','fixture',current_timestamp)",
        s.org(),
        connection);
    jdbc.update(
        "insert into"
            + " provider_connections(id,organization_id,provider,mode,name,state,configuration_revision,credential_revision,created_at,updated_at)"
            + " values(?,?,'OPENAI','OPENAI_API_KEY','Fixture','ENABLED',1,1,current_timestamp,current_timestamp)",
        connection,
        s.org());
    jdbc.update(
        "insert into"
            + " execution_model_authorizations(organization_id,run_id,action_id,attempt_id,fence,agent_id,connection_id,credential_revision,model_id,request_sha256,authorized_at,control_revision,handed_off_at)"
            + " values(?,?,?,?,?,?,?,1,'fixture-model',?,current_timestamp,1,current_timestamp)",
        s.org(),
        d.runId(),
        d.dispatchId(),
        token.attempt(),
        token.fence(),
        s.agent(),
        connection,
        "a".repeat(64));
  }

  @Test
  void handedOffActionCannotBeReleasedAsUnsentOrZeroCharged() {
    var s = admitted();
    var d = queue(s);
    var token = claim(s, d.dispatchId());
    fund(s);
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    tx.executeWithoutResult(t -> budgets.reserve(token, d.dispatchId(), 100));
    authorizeFixture(s, d, token);
    act(s, MissionExecutionControlsService.Action.PAUSE);
    assertThatThrownBy(
            () ->
                tx.execute(
                    t ->
                        new ExecutionPausedDispatchStore(jdbc, claims, budgets)
                            .releaseUnsent(token, d.dispatchId())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select status from execution_budget_reservations where action_id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("HELD");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_pause_unsent_releases where dispatch_id=?",
                Long.class,
                d.dispatchId()))
        .isZero();
    assertThat(controls(s).canResume()).isFalse();
    assertThat(controls(s).canRequestCancel()).isFalse();
  }

  @Test
  void sentFailureRemainsUnknownWithConservativeReservationWhenPaused() throws Exception {
    var s = admitted();
    var d = queue(s);
    var token = claim(s, d.dispatchId());
    fund(s);
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(t -> budgets.reserve(token, d.dispatchId(), 100));
    authorizeFixture(s, d, token);
    act(s, MissionExecutionControlsService.Action.PAUSE);
    var job = job(s, d);
    var method =
        OpenAiExecutionFlow.class.getDeclaredMethod(
            "uncertain", job.getClass(), ExecutionClaimStore.Token.class, boolean.class);
    method.setAccessible(true);
    method.invoke(flow(), job, token, true);
    assertThat(
            jdbc.queryForObject(
                "select state from execution_start_dispatch where id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "select status from execution_budget_reservations where action_id=?",
                String.class,
                d.dispatchId()))
        .isEqualTo("UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "select sum(held_microusd) from execution_budget_buckets where organization_id=?",
                Long.class,
                s.org()))
        .isEqualTo(400);
    assertThat(controls(s).safelyPaused()).isFalse();
    assertThat(controls(s).canRequestCancel()).isFalse();
  }

  @Test
  void equalCountsDoNotHideChangedDispatchIdentityOrLeasePrecision() {
    var s = admitted();
    var d = queue(s);
    var before = controls(s);
    jdbc.update(
        "update execution_start_dispatch set actor_credential_version=1 where id=?",
        d.dispatchId());
    assertThat(controls(s).work()).isEqualTo(before.work());
    assertThat(controls(s).fingerprint()).isNotEqualTo(before.fingerprint());
    assertThatThrownBy(
            () ->
                control(
                    s,
                    new MissionExecutionControlsService.Command(
                        UUID.randomUUID(),
                        before.fingerprint(),
                        MissionExecutionControlsService.Action.PAUSE)))
        .isInstanceOf(MissionExecutionControlsService.Conflict.class);
    jdbc.update(
        "update execution_start_dispatch set actor_credential_version=0 where id=?",
        d.dispatchId());
    var token = claim(s, d.dispatchId());
    var claimed = controls(s);
    var expires =
        jdbc.queryForObject(
            "select expires_at from execution_claims where run_id=? and fence=?",
            java.time.OffsetDateTime.class,
            d.runId(),
            token.fence());
    jdbc.update(
        "update execution_claims set expires_at=? where run_id=? and fence=?",
        expires.plusNanos(1000),
        d.runId(),
        token.fence());
    assertThat(controls(s).work()).isEqualTo(claimed.work());
    assertThat(controls(s).fingerprint()).isNotEqualTo(claimed.fingerprint());
  }

  @Test
  void inconsistentTaskRunCannotEnterIrreversibleCancelAndReviewedSnapshotIsReadable() {
    var s = admitted();
    var before = controls(s);
    var pause = command(s, MissionExecutionControlsService.Action.PAUSE);
    var receipt = control(s, pause).receipt();
    act(s, MissionExecutionControlsService.Action.RESUME);
    assertThat(
            controlService.reviewedSnapshot(
                s.org(), s.team(), s.mission(), s.human(), pause.idempotencyKey()))
        .isEqualTo(before);
    assertThat(receipt.snapshotSha256()).matches("[0-9a-f]{64}");
    jdbc.update("update tasks set status='CANCELLED' where id=?", root(s));
    assertThat(controls(s).canRequestCancel()).isFalse();
    assertThat(controls(s).blockers()).contains("INCONSISTENT_RUNTIME_SCOPE");
  }

  void prepareRequest(Scope s, ExecutionStartService.Receipt d) {
    UUID connection = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
            + " values(?,?,'OPENAI',1,'fixture','1','1','v2','fixture',current_timestamp)",
        s.org(),
        connection);
    jdbc.update(
        "insert into"
            + " provider_connections(id,organization_id,provider,mode,name,state,configuration_revision,credential_revision,created_at,updated_at)"
            + " values(?,?,'OPENAI','OPENAI_API_KEY','Fixture','ENABLED',1,1,current_timestamp,current_timestamp)",
        connection,
        s.org());
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    jdbc.update(
        "insert into"
            + " execution_openai_requests(dispatch_id,connection_id,credential_revision,model_id,request_body,preflight_sha256,request_sha256,max_output_tokens,maximum_microusd,input_rate,output_rate)"
            + " values(?,?,1,'fixture-model',?,?,?,100,20000,1000000,1000000)",
        d.dispatchId(),
        connection,
        body,
        "a".repeat(64),
        AcceptedRunOutputEvidence.sha(body));
  }
}

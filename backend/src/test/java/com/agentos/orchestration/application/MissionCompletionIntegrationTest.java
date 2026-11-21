package com.agentos.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.orchestration.application.MissionCompletionService.Action;
import com.agentos.orchestration.application.MissionCompletionService.Command;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:mission_completion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.mission-completion.enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionCompletionIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MissionCompletionService service;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager manager;
  @Autowired com.agentos.mission.application.TaskCreateService tasks;

  record Fixture(
      UUID org,
      UUID team,
      UUID mission,
      UUID task,
      UUID run,
      UUID attempt,
      UUID agent,
      HumanIdentity actor) {}

  Fixture fixture() throws Exception {
    UUID org = UUID.randomUUID(),
        team = UUID.randomUUID(),
        mission = UUID.randomUUID(),
        task = UUID.randomUUID(),
        run = UUID.randomUUID(),
        attempt = UUID.randomUUID(),
        agent = UUID.randomUUID(),
        actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values(?,'Fixture',?,'ACTIVE',current_timestamp,current_timestamp)",
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
            + " values(?,?,?,'Mission','Goal','RUNNING','NORMAL',current_timestamp,current_timestamp)",
        mission,
        org,
        team);
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values(?,?,?,'Agent','Writer','MID','ACTIVE','SUGGEST',current_timestamp,current_timestamp)",
        agent,
        org,
        team);
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id)"
            + " values(?,?,?,?,'Task','COMPLETED',?)",
        task,
        org,
        team,
        mission,
        agent);
    jdbc.update(
        "insert into"
            + " accepted_mission_plans(organization_id,team_id,mission_id,plan_revision,accepted_by)"
            + " values(?,?,?,1,?)",
        org,
        team,
        mission,
        actor);
    jdbc.update(
        "insert into"
            + " accepted_mission_plan_tasks(organization_id,team_id,mission_id,plan_revision,task_id,owner_agent_id,task_edit_revision,task_owner_revision,dependency_revision)"
            + " values(?,?,?,1,?,?,0,0,0)",
        org,
        team,
        mission,
        task,
        agent);
    jdbc.update(
        "insert into"
            + " execution_runs(id,organization_id,team_id,mission_id,task_id,plan_revision,revision,status)"
            + " values(?,?,?,?,?,1,3,'COMPLETED')",
        run,
        org,
        team,
        mission,
        task);
    jdbc.update(
        "insert into execution_attempts(id,run_id,attempt_number,status,outcome_slot)"
            + " values(?,?,1,'SUCCEEDED',1)",
        attempt,
        run);
    String output =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("Verified output".getBytes(StandardCharsets.UTF_8)));
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
            + " values(?,?,1,current_timestamp)",
        run,
        attempt);
    jdbc.update(
        "insert into"
            + " execution_review_decisions(run_id,attempt_id,reviewer_id,source_run_revision,decision,decided_at)"
            + " values(?,?,?,2,'ACCEPT',current_timestamp)",
        run,
        attempt,
        actor);
    jdbc.update(
        "insert into mission_execution_controls(organization_id,team_id,mission_id,revision,state)"
            + " values(?,?,?,1,'OPEN')",
        org,
        team,
        mission);
    UUID connection = UUID.randomUUID(), action = UUID.randomUUID();
    byte[] request = "{}".getBytes(StandardCharsets.UTF_8);
    String requestSha =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request));
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
            + " values(?,?,?,0,?,?,0,'FINISHED')",
        action,
        org,
        run,
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
    return new Fixture(
        org,
        team,
        mission,
        task,
        run,
        attempt,
        agent,
        new HumanIdentity(actor, actor + "@example.test", "Admin"));
  }

  MissionCompletionService.Snapshot snapshot(Fixture f) {
    return service.current(f.org(), f.team(), f.mission(), f.actor());
  }

  Command command(Fixture f) {
    var s = snapshot(f);
    return new Command(UUID.randomUUID(), s.action(), s.fingerprint());
  }

  MissionCompletionService.Receipt apply(Fixture f, Command c) {
    return service.apply(f.org(), f.team(), f.mission(), f.actor(), c);
  }

  String state(Fixture f) {
    return jdbc.queryForObject("select status from missions where id=?", String.class, f.mission());
  }

  @Test
  void explicitTwoStepReviewCompletionAndHistoricalReplay() throws Exception {
    var f = fixture();
    var first = command(f);
    assertThat(first.action()).isEqualTo(Action.SUBMIT_REVIEW);
    assertThat(apply(f, first).status()).isEqualTo("REVIEW");
    assertThat(state(f)).isEqualTo("REVIEW");
    var second = command(f);
    assertThat(second.action()).isEqualTo(Action.COMPLETE);
    var completed = apply(f, second);
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(apply(f, second)).isEqualTo(completed);
    assertThat(apply(f, first).status()).isEqualTo("REVIEW");
    assertThat(state(f)).isEqualTo("COMPLETED");
    assertThat(snapshot(f).action()).isNull();
    assertThat(snapshot(f).blockers()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_completion_receipts where mission_id=?",
                Integer.class,
                f.mission()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_events where mission_id=?", Integer.class, f.mission()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select status from execution_runs where id=?", String.class, f.run()))
        .isEqualTo("COMPLETED");
  }

  @Test
  void additionalTaskAndLegacyLabelsBlockWithoutWrites() throws Exception {
    var f = fixture();
    var c = command(f);
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status)"
            + " values(?,?,?,?,'Extra','CANCELLED')",
        UUID.randomUUID(),
        f.org(),
        f.team(),
        f.mission());
    assertThat(snapshot(f).blockers()).contains("UNSUPPORTED_SCOPE");
    assertThatThrownBy(() -> apply(f, c)).isInstanceOf(MissionCompletionService.Conflict.class);
    assertThat(state(f)).isEqualTo("RUNNING");
    var legacy = fixture();
    jdbc.update("delete from execution_review_decisions where run_id=?", legacy.run());
    assertThat(snapshot(legacy).blockers()).contains("ACCEPTED_OUTPUT_UNAVAILABLE");
  }

  @Test
  void tamperedOutputChangedConfigurationAndStaleControlAreDenied() throws Exception {
    var f = fixture();
    var c = command(f);
    jdbc.update(
        "update execution_verified_output_content set content='Tampered' where run_id=?", f.run());
    assertThat(snapshot(f).blockers()).contains("OUTPUT_INTEGRITY_UNAVAILABLE");
    assertThatThrownBy(() -> apply(f, c)).isInstanceOf(MissionCompletionService.Conflict.class);
    var changed = fixture();
    jdbc.update("update tasks set task_owner_revision=1 where id=?", changed.task());
    assertThat(snapshot(changed).blockers()).contains("ACCEPTED_OUTPUT_UNAVAILABLE");
    var stale = fixture();
    var old = command(stale);
    jdbc.update(
        "update mission_execution_controls set revision=revision+1 where mission_id=?",
        stale.mission());
    assertThatThrownBy(() -> apply(stale, old))
        .isInstanceOf(MissionCompletionService.Conflict.class);
  }

  @Test
  void pendingHistoricalEffectsBlockEvenWithAcceptedLatestOutput() throws Exception {
    var f = fixture();
    var c = command(f);
    UUID prior = UUID.randomUUID();
    jdbc.update(
        "update execution_attempts set attempt_number=2,outcome_slot=2 where id=?", f.attempt());
    jdbc.update(
        "insert into execution_attempts(id,run_id,attempt_number,status,outcome_slot)"
            + " values(?,?,1,'UNKNOWN',0)",
        prior,
        f.run());
    assertThat(snapshot(f).blockers()).contains("ATTEMPT_UNRESOLVED");
    jdbc.update("update execution_attempts set status='FAILED',outcome_slot=1 where id=?", prior);
    jdbc.update(
        "insert into"
            + " execution_claims(run_id,attempt_id,fence,worker_id,issued_at,expires_at,deadline_at)"
            + " values(?,?,2,?,current_timestamp-interval '1' second,current_timestamp+interval"
            + " '60' second,current_timestamp+interval '120' second)",
        f.run(),
        prior,
        UUID.randomUUID());
    assertThat(snapshot(f).blockers()).contains("LIVE_CLAIM");
    jdbc.update(
        "update execution_claims set invalidated_at=current_timestamp where run_id=?", f.run());
    jdbc.update(
        "insert into"
            + " execution_budget_reservations(run_id,action_id,attempt_id,fence,reserved_microusd,status)"
            + " values(?,?,?,2,1,'UNKNOWN')",
        f.run(),
        UUID.randomUUID(),
        prior);
    assertThat(snapshot(f).blockers()).contains("ACCOUNTING_UNRESOLVED");
    jdbc.update(
        "update execution_budget_reservations set status='SETTLED',actual_microusd=0 where"
            + " run_id=?",
        f.run());
    jdbc.update(
        "insert into"
            + " execution_start_dispatch(id,organization_id,run_id,source_run_revision,actor_id,idempotency_key,actor_credential_version,state)"
            + " values(?,?,?,1,?,?,0,'QUEUED')",
        UUID.randomUUID(),
        f.org(),
        f.run(),
        f.actor().id(),
        UUID.randomUUID());
    assertThat(snapshot(f).blockers()).contains("DISPATCH_UNRESOLVED");
    assertThatThrownBy(() -> apply(f, c)).isInstanceOf(MissionCompletionService.Conflict.class);
    assertThat(state(f)).isEqualTo("RUNNING");
  }

  @Test
  void cancellationArchivedParentsMissingControlAndWrongStateDenyClosure() throws Exception {
    for (String sql :
        List.of(
            "update mission_execution_controls set state='CANCEL_REQUESTED' where mission_id=?",
            "update teams set status='ARCHIVED' where id=?",
            "delete from mission_execution_controls where mission_id=?",
            "update missions set status='FAILED' where id=?")) {
      var f = fixture();
      var c = command(f);
      jdbc.update(sql, sql.contains("teams") ? f.team() : f.mission());
      assertThat(snapshot(f).action()).isNull();
      assertThatThrownBy(() -> apply(f, c)).isInstanceOf(MissionCompletionService.Conflict.class);
    }
  }

  @Test
  void revokedAuthorityAndScopeAndIdempotencyMismatchDeny() throws Exception {
    var f = fixture();
    var c = command(f);
    apply(f, c);
    assertThatThrownBy(
            () ->
                apply(f, new Command(c.idempotencyKey(), Action.COMPLETE, c.expectedFingerprint())))
        .isInstanceOf(MissionCompletionService.Conflict.class);
    assertThatThrownBy(() -> service.apply(f.org(), f.team(), UUID.randomUUID(), f.actor(), c))
        .isInstanceOf(MissionCompletionService.Conflict.class);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=?", f.org());
    assertThatThrownBy(() -> apply(f, c)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> snapshot(f)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void receiptFailureRollsBackMissionControlAndAudit() throws Exception {
    var f = fixture();
    var c = command(f);
    var tx = new TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      apply(f, c);
                      throw new IllegalStateException("Injected failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(state(f)).isEqualTo("RUNNING");
    assertThat(
            jdbc.queryForObject(
                "select revision from mission_execution_controls where mission_id=?",
                Long.class,
                f.mission()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_completion_receipts where mission_id=?",
                Integer.class,
                f.mission()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_events where mission_id=?", Integer.class, f.mission()))
        .isZero();
    assertThat(apply(f, c).status()).isEqualTo("REVIEW");
  }

  @Test
  void httpRequiresCurrentAdminSessionCsrfAndUsesNoStore() throws Exception {
    var f = fixture();
    var c = command(f);
    String path =
        "/api/v1/organizations/"
            + f.org()
            + "/teams/"
            + f.team()
            + "/missions/"
            + f.mission()
            + "/completion";
    var auth = authentication(new UsernamePasswordAuthenticationToken(f.actor(), null, List.of()));
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).with(auth))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"));
    String body =
        "{\"idempotencyKey\":\""
            + c.idempotencyKey()
            + "\",\"action\":\"SUBMIT_REVIEW\",\"expectedFingerprint\":\""
            + c.expectedFingerprint()
            + "\"}";
    mvc.perform(post(path).with(auth).contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isOk());
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?",
        f.actor().id());
    mvc.perform(get(path).with(auth)).andExpect(status().isUnauthorized());
  }

  @Test
  void directCompletionUntrustedReviewAndMissingExecutionProvenanceAreBlocked() throws Exception {
    var f = fixture();
    var c = command(f);
    assertThatThrownBy(
            () ->
                apply(f, new Command(UUID.randomUUID(), Action.COMPLETE, c.expectedFingerprint())))
        .isInstanceOf(MissionCompletionService.Conflict.class);
    jdbc.update("update missions set status='REVIEW' where id=?", f.mission());
    assertThat(snapshot(f).blockers()).contains("COMPLETION_REVIEW_UNAVAILABLE");
    var absent = fixture();
    jdbc.update("delete from execution_model_usage where run_id=?", absent.run());
    assertThat(snapshot(absent).blockers()).contains("ACCEPTED_OUTPUT_UNAVAILABLE");
    var tampered = fixture();
    jdbc.update(
        "update execution_openai_requests set request_body=? where dispatch_id in(select id from"
            + " execution_start_dispatch where run_id=?)",
        "changed".getBytes(StandardCharsets.UTF_8),
        tampered.run());
    assertThat(snapshot(tampered).blockers()).contains("REQUEST_INTEGRITY_UNAVAILABLE");
    var usage = fixture();
    jdbc.update("update execution_model_usage set input_tokens=99 where run_id=?", usage.run());
    assertThat(snapshot(usage).blockers()).contains("USAGE_EVIDENCE_UNAVAILABLE");
  }

  @Test
  void invalidWireAndForeignScopeCannotChangeState() throws Exception {
    var f = fixture();
    var c = command(f);
    String path =
        "/api/v1/organizations/"
            + f.org()
            + "/teams/"
            + f.team()
            + "/missions/"
            + f.mission()
            + "/completion";
    var auth = authentication(new UsernamePasswordAuthenticationToken(f.actor(), null, List.of()));
    String body =
        "{\"idempotencyKey\":\""
            + c.idempotencyKey()
            + "\",\"action\":\"SUBMIT_REVIEW\",\"expectedFingerprint\":\""
            + c.expectedFingerprint()
            + "\"}";
    for (String bad :
        List.of(
            body.replace("SUBMIT_REVIEW", "INVALID"),
            body.replace("\"SUBMIT_REVIEW\"", "0"),
            body.replace(c.idempotencyKey().toString(), "1-1-1-1-1"),
            body.replace("}", ",\"extra\":true}"),
            body.replace("}", ",\"action\":\"COMPLETE\"}"),
            body + "{}",
            "null"))
      mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(bad))
          .andExpect(status().isBadRequest());
    mvc.perform(get(path.replace(f.team().toString(), UUID.randomUUID().toString())).with(auth))
        .andExpect(status().isNotFound());
    var foreign = fixture();
    assertThatThrownBy(
            () -> service.current(foreign.org(), foreign.team(), foreign.mission(), f.actor()))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(state(f)).isEqualTo("RUNNING");
  }

  @Test
  void concurrentSameKeyReplaysButDifferentKeysCannotBothTransition() throws Exception {
    var f = fixture();
    var c = command(f);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var a =
          workers.submit(
              () -> {
                gate.await();
                return apply(f, c);
              });
      var b =
          workers.submit(
              () -> {
                gate.await();
                return apply(f, c);
              });
      gate.countDown();
      assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from mission_completion_receipts where mission_id=?",
                  Integer.class,
                  f.mission()))
          .isEqualTo(1);
      var other = fixture();
      var first = command(other);
      var second = new Command(UUID.randomUUID(), first.action(), first.expectedFingerprint());
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.function.Function<Command, Boolean> attempt =
          command -> {
            try {
              apply(other, command);
              return true;
            } catch (MissionCompletionService.Conflict conflict) {
              return false;
            }
          };
      var x =
          workers.submit(
              () -> {
                start.await();
                return attempt.apply(first);
              });
      var y =
          workers.submit(
              () -> {
                start.await();
                return attempt.apply(second);
              });
      start.countDown();
      assertThat(
              List.of(
                  x.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  y.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void actualReceiptInsertFailureRollsBackPriorStateWrites() throws Exception {
    var f = fixture();
    var c = command(f);
    jdbc.execute(
        "alter table mission_completion_receipts add constraint reject_test_completion check"
            + " (mission_id<>'"
            + f.mission()
            + "')");
    try {
      assertThatThrownBy(() -> apply(f, c))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(state(f)).isEqualTo("RUNNING");
      assertThat(
              jdbc.queryForObject(
                  "select revision from mission_execution_controls where mission_id=?",
                  Long.class,
                  f.mission()))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from audit_events where mission_id=?",
                  Integer.class,
                  f.mission()))
          .isZero();
    } finally {
      jdbc.execute(
          "alter table mission_completion_receipts drop constraint reject_test_completion");
    }
    assertThat(apply(f, c).status()).isEqualTo("REVIEW");
  }

  @Test
  void taskCreationAndMissionReviewCannotBothWin() throws Exception {
    var f = fixture();
    var c = command(f);
    var request =
        new com.agentos.mission.api.dto.TaskCreateRequest(
            "Competing Task", "Input", "Output", "NORMAL", null, List.of(), 0L);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var submit =
          workers.submit(
              () -> {
                gate.await();
                try {
                  apply(f, c);
                  return true;
                } catch (MissionCompletionService.Conflict conflict) {
                  return false;
                }
              });
      var create =
          workers.submit(
              () -> {
                gate.await();
                try {
                  tasks.create(
                      f.org(), f.team(), f.mission(), f.actor(), UUID.randomUUID(), request);
                  return true;
                } catch (com.agentos.mission.application.MissionCreateService.Conflict conflict) {
                  return false;
                }
              });
      gate.countDown();
      boolean reviewed = submit.get(10, java.util.concurrent.TimeUnit.SECONDS),
          created = create.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(List.of(reviewed, created)).containsExactlyInAnyOrder(true, false);
      assertThat(state(f)).isEqualTo(reviewed ? "REVIEW" : "RUNNING");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from tasks where mission_id=?", Integer.class, f.mission()))
          .isEqualTo(reviewed ? 1 : 2);
    }
  }
}

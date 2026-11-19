package com.agentos.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.agent.application.AgentModelGrantService;
import com.agentos.auth.HumanIdentity;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class RuntimePersistenceTest {
  @Test
  void migrationsPreserveDraftsAndEnforcePlanScopeAttemptUniquenessAndClaimFencing()
      throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
            "sa",
            "");
    Flyway.configure().dataSource(source).target("35").load().migrate();
    var jdbc = new JdbcTemplate(source);
    var org = UUID.randomUUID();
    var team = UUID.randomUUID();
    var mission = UUID.randomUUID();
    var task = UUID.randomUUID();
    var excludedTask = UUID.randomUUID();
    var actor = UUID.randomUUID();
    var agent = UUID.randomUUID();
    var run = UUID.randomUUID();
    jdbc.update(
        """
        insert into organizations (id,name,slug,status,created_at,updated_at)
        values (?,'Fixture','fixture','ACTIVE',current_timestamp,current_timestamp)
        """,
        org);
    jdbc.update(
        """
        insert into teams (id,organization_id,name,status,created_at,updated_at)
        values (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)
        """,
        team,
        org);
    jdbc.update(
        """
insert into missions (id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)
values (?,?,?,'Mission','Goal','DRAFT','NORMAL',current_timestamp,current_timestamp)
""",
        mission,
        org,
        team);
    jdbc.update(
        """
        insert into tasks (id,organization_id,team_id,mission_id,title,status)
        values (?,?,?,?,'Selected','DRAFT'), (?,?,?,?,'Excluded','DRAFT')
        """,
        task,
        org,
        team,
        mission,
        excludedTask,
        org,
        team,
        mission);
    jdbc.update(
        "insert into human_accounts (id,email,display_name,password_hash) values"
            + " (?,'admin@example.test','Admin','unused')",
        actor);
    jdbc.update(
        """
insert into agents (id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
values (?,?,?,'Agent','Reviewer','MID','DRAFT','OBSERVE',current_timestamp,current_timestamp)
""",
        agent,
        org,
        team);

    Flyway.configure().dataSource(source).load().migrate();
    assertThat(jdbc.queryForList("select status from tasks", String.class))
        .containsExactly("DRAFT", "DRAFT");
    for (var table :
        new String[] {
          "accepted_mission_plans",
          "accepted_mission_plan_tasks",
          "accepted_mission_plan_dependencies",
          "execution_runs",
          "execution_attempts",
          "execution_claims",
          "execution_budget_buckets",
          "execution_capacity_limits",
          "execution_model_usage",
          "execution_model_authorizations",
          "mission_execution_controls",
          "execution_budget_reservations",
          "execution_budget_reservation_buckets"
        }) {
      assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
    }

    jdbc.update(
        """
insert into accepted_mission_plans (organization_id,team_id,mission_id,plan_revision,accepted_by)
values (?,?,?,1,?)
""",
        org,
        team,
        mission,
        actor);
    jdbc.update(
        """
        insert into accepted_mission_plan_tasks
          (organization_id,team_id,mission_id,plan_revision,task_id,owner_agent_id,
           task_edit_revision,task_owner_revision,dependency_revision)
        values (?,?,?,1,?,?,0,0,0)
        """,
        org,
        team,
        mission,
        task,
        agent);
    String insertRun =
        """
insert into execution_runs (id,organization_id,team_id,mission_id,task_id,plan_revision,status)
values (?,?,?,?,?,?,'CREATED')
""";
    assertThatThrownBy(() -> jdbc.update(insertRun, run, org, team, mission, excludedTask, 1))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(insertRun, run, org, team, mission, task, 2))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(insertRun, run, UUID.randomUUID(), team, mission, task, 1))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(insertRun, run, org, team, mission, task, 1);
    assertThatThrownBy(() -> jdbc.update(insertRun, UUID.randomUUID(), org, team, mission, task, 1))
        .isInstanceOf(DataIntegrityViolationException.class);

    String insertAttempt =
        """
        insert into execution_attempts (id,run_id,attempt_number,status,outcome_slot)
        values (?,?,?,?,?)
        """;
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    jdbc.update(insertAttempt, first, run, 1, "UNKNOWN", 0);
    assertThatThrownBy(() -> jdbc.update(insertAttempt, second, run, 2, "RUNNING", 0))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(insertAttempt, second, run, 2, "RUNNING", 2))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("update execution_attempts set status='FAILED',outcome_slot=1 where id=?", first);
    jdbc.update(insertAttempt, second, run, 2, "RUNNING", 0);
    assertThat(
            jdbc.queryForList(
                "select status from execution_attempts where run_id=? order by attempt_number",
                String.class,
                run))
        .containsExactly("FAILED", "RUNNING");
    verifyClaims(jdbc, org, team, agent, mission, run, second);
  }

  private void verifyClaims(
      JdbcTemplate jdbc, UUID org, UUID team, UUID agent, UUID mission, UUID run, UUID attempt)
      throws Exception {
    jdbc.update("update execution_runs set status='RUNNING' where id=?", run);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var controls = new MissionExecutionControlStore(jdbc);
    var scope = new MissionExecutionControlStore.Scope(org, team, mission);
    var store = new ExecutionClaimStore(jdbc, controls);
    jdbc.update("update missions set status='RUNNING' where id=?", mission);
    var deadline = OffsetDateTime.now().plusMinutes(10);
    var expiry = deadline.minusMinutes(2);
    var firstWorker = UUID.randomUUID();
    var secondWorker = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> store.issueFresh(org, run, attempt, firstWorker, expiry, deadline)))
        .hasMessage("Mission execution control is unavailable.");
    tx.executeWithoutResult(status -> controls.initialize(scope));
    assertThat(
            tx.<Long>execute(
                status ->
                    controls.change(scope, 1, MissionExecutionControlStore.State.PAUSE_REQUESTED)))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> store.issueFresh(org, run, attempt, firstWorker, expiry, deadline)))
        .hasMessage("Mission execution control is unavailable.");
    assertThat(
            tx.<Long>execute(
                status -> controls.change(scope, 2, MissionExecutionControlStore.State.OPEN)))
        .isEqualTo(3);
    var start = new CountDownLatch(1);
    ExecutionClaimStore.Token token;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          pool.submit(
              () -> {
                start.await();
                return tryIssue(tx, store, org, run, attempt, firstWorker, expiry, deadline);
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return tryIssue(tx, store, org, run, attempt, secondWorker, expiry, deadline);
              });
      start.countDown();
      var firstToken = first.get(10, TimeUnit.SECONDS);
      var secondToken = second.get(10, TimeUnit.SECONDS);
      assertThat(firstToken == null).isNotEqualTo(secondToken == null);
      token = firstToken == null ? secondToken : firstToken;
    }
    assertThat(token.fence()).isEqualTo(1);
    tx.executeWithoutResult(status -> store.requireCurrent(token));
    var wrongWorker =
        new ExecutionClaimStore.Token(org, run, attempt, UUID.randomUUID(), token.fence());
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.requireCurrent(wrongWorker)))
        .isInstanceOf(IllegalStateException.class);
    var wrongOrg =
        new ExecutionClaimStore.Token(
            UUID.randomUUID(), run, attempt, token.worker(), token.fence());
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.requireCurrent(wrongOrg)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> store.renew(token, deadline.plusSeconds(1))))
        .isInstanceOf(IllegalStateException.class);
    tx.executeWithoutResult(status -> store.renew(token, deadline));

    var budgets = new ExecutionBudgetStore(jdbc, store);
    var expiryStore = new ExecutionLeaseExpiryStore(jdbc, store, budgets);
    var expiryScheduler =
        new ExecutionLeaseExpiryScheduler(
            jdbc, expiryStore, new DataSourceTransactionManager(jdbc.getDataSource()));
    expiryScheduler.sweep();
    assertThat(tx.<Boolean>execute(status -> expiryStore.expire(org, run, token.fence())))
        .isFalse();
    var past = OffsetDateTime.now().minusMinutes(1);
    jdbc.update(
        "update execution_claims set issued_at=?, expires_at=? where run_id=?",
        past.minusMinutes(1),
        past,
        run);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.requireCurrent(token)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.renew(token, deadline)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tryIssue(tx, store, org, run, attempt, token.worker(), expiry, deadline)).isNull();
    // Inconsistent projections make expiry roll back and leave the claim eligible for another scan.
    expiryScheduler.sweep();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_claims where run_id=? and invalidated_at is null",
                Integer.class,
                run))
        .isEqualTo(1);
    jdbc.update(
        "update tasks set status='RUNNING' where id=(select task_id from execution_runs where"
            + " id=?)",
        run);
    expiryScheduler.sweep();
    assertThat(tx.<Boolean>execute(status -> expiryStore.expire(org, run, token.fence())))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select status from execution_attempts where id=?", String.class, attempt))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "select pending_reason from tasks where id=(select task_id from execution_runs"
                    + " where id=?)",
                String.class,
                run))
        .isEqualTo("LEASE_EXPIRED");
    assertThat(tryIssue(tx, store, org, run, attempt, token.worker(), expiry, deadline)).isNull();

    verifyReconciledReview(jdbc, tx, store, org, run);

    // Simulate explicit retry and a new attempt admission.
    jdbc.update("update execution_runs set status='RUNNING' where id=?", run);
    jdbc.update(
        "update tasks set status='RUNNING',pending_reason=null,waiting_since=null,resolver=null "
            + "where id=(select task_id from execution_runs where id=?)",
        run);
    var replacement = UUID.randomUUID();
    jdbc.update(
        """
        insert into execution_attempts (id,run_id,attempt_number,status,outcome_slot)
        values (?,?,3,'RUNNING',0)
        """,
        replacement,
        run);
    var next =
        tx.execute(
            status -> store.issueFresh(org, run, replacement, token.worker(), expiry, deadline));
    assertThat(next.fence()).isEqualTo(2);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.invalidate(token)))
        .isInstanceOf(IllegalStateException.class);
    tx.executeWithoutResult(status -> store.requireCurrent(next));
    var pending = verifyBudget(jdbc, tx, store, next, team, agent, mission, controls, scope);
    assertThat(
            tx.<Long>execute(
                status ->
                    controls.change(scope, 5, MissionExecutionControlStore.State.CANCEL_REQUESTED)))
        .isEqualTo(6);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> controls.change(scope, 6, MissionExecutionControlStore.State.OPEN)))
        .hasMessage("Mission execution control is unavailable.");
    assertThat(
            jdbc.queryForList(
                "select status from execution_attempts where run_id=? order by attempt_number",
                String.class,
                run))
        .containsExactly("FAILED", "FAILED", "CANCELLING");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("CANCELLING");
    assertThat(jdbc.queryForObject("select status from missions where id=?", String.class, mission))
        .isEqualTo("RUNNING");
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.requireCurrent(next)))
        .isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(() -> tx.execute(status -> controls.acknowledgeCancellation(scope, 6)))
        .hasMessage("Mission execution control is unavailable.");
    assertThat(tx.<Boolean>execute(status -> expiryStore.expire(org, run, token.fence())))
        .isFalse();
    jdbc.update(
        "update execution_claims set issued_at=?,expires_at=? where run_id=? and fence=?",
        past.minusMinutes(1),
        past,
        run,
        next.fence());
    expiryScheduler.sweep();
    assertThat(
            jdbc.queryForObject(
                "select status from execution_attempts where id=?", String.class, next.attempt()))
        .isEqualTo("UNKNOWN");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("CANCELLING");
    assertThat(
            jdbc.queryForObject(
                "select status from tasks where id=(select task_id from execution_runs where id=?)",
                String.class,
                run))
        .isEqualTo("RUNNING");
    assertThat(
            jdbc.queryForObject(
                "select status from execution_budget_reservations where run_id=? and action_id=?",
                String.class,
                run,
                pending))
        .isEqualTo("UNKNOWN");
    assertThatThrownBy(() -> tx.execute(status -> controls.acknowledgeCancellation(scope, 6)))
        .hasMessage("Mission execution control is unavailable.");
    var reconciliations = new ExecutionAttemptReconciliationStore(jdbc, store);
    var outcome = ExecutionAttemptReconciliationStore.Outcome.SUCCEEDED;
    String evidence = "b".repeat(64);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        reconciliations.recordVerified(
                            org, run, next.attempt(), outcome, evidence)))
        .hasMessage("Execution Attempt reconciliation is unavailable.");
    tx.executeWithoutResult(status -> budgets.settleVerified(org, run, pending, 0));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        reconciliations.recordVerified(
                            UUID.randomUUID(), run, next.attempt(), outcome, evidence)))
        .isInstanceOf(IllegalStateException.class);
    tx.executeWithoutResult(
        status -> reconciliations.recordVerified(org, run, next.attempt(), outcome, evidence));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        reconciliations.recordVerified(
                            org, run, next.attempt(), outcome, "c".repeat(64))))
        .hasMessage("Execution Attempt reconciliation is unavailable.");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("CANCELLING");

    jdbc.update(
        """
        update tasks set status='BLOCKED',pending_reason='Awaiting cancellation',
          waiting_since=current_timestamp,resolver='Runtime'
        where id=(select task_id from execution_runs where id=?)
        """,
        run);
    assertThatThrownBy(() -> tx.execute(status -> controls.acknowledgeCancellation(scope, 5)))
        .hasMessage("Mission execution control is unavailable.");
    assertThat(tx.<Long>execute(status -> controls.acknowledgeCancellation(scope, 6))).isEqualTo(7);
    assertThat(jdbc.queryForObject("select status from missions where id=?", String.class, mission))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForList("select status from tasks where mission_id=?", String.class, mission))
        .containsExactlyInAnyOrder("DRAFT", "CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "select status from execution_attempts where id=?", String.class, next.attempt()))
        .isEqualTo("SUCCEEDED");
    tx.executeWithoutResult(
        status -> reconciliations.recordVerified(org, run, next.attempt(), outcome, evidence));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_attempt_reconciliations", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> tx.execute(status -> controls.acknowledgeCancellation(scope, 6)))
        .hasMessage("Mission execution control is unavailable.");
    verifyAtomicPublication(jdbc, tx, store, org, run);
    verifyStartAdmission(jdbc, tx, store, org, run);
  }

  private void verifyStartAdmission(
      JdbcTemplate jdbc, TransactionTemplate tx, ExecutionClaimStore claims, UUID org, UUID run) {
    var start = new ExecutionStartService(jdbc, new ExecutionReviewDecisionStore(jdbc, claims));
    UUID account = jdbc.queryForObject("select id from human_accounts", UUID.class);
    jdbc.update("update human_accounts set enabled=true,credential_version=0 where id=?", account);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", account);
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) select ?,?,'ADMIN'"
            + " where not exists(select 1 from organization_memberships where account_id=? and"
            + " organization_id=?)",
        account,
        org,
        account,
        org);
    var actor = new HumanIdentity(account, "admin@example.test", "Admin");
    UUID task =
        jdbc.queryForObject("select task_id from execution_runs where id=?", UUID.class, run);
    UUID agent =
        jdbc.queryForObject(
            "select owner_agent_id from accepted_mission_plan_tasks where task_id=?",
            UUID.class,
            task);
    jdbc.update("update agents set status='ACTIVE' where id=?", agent);
    jdbc.update("update execution_runs set status='CREATED' where id=?", run);
    jdbc.update(
        "update tasks set"
            + " status='READY',owner_agent_id=?,pending_reason=null,waiting_since=null,resolver=null"
            + " where id=?",
        agent,
        task);
    long revision =
        jdbc.queryForObject("select revision from execution_runs where id=?", Long.class, run);
    UUID key = UUID.randomUUID();
    assertThatThrownBy(() -> tx.execute(t -> start.start(org, run, revision + 1, key, actor)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("update mission_execution_controls set state='PAUSE_REQUESTED'");
    assertThatThrownBy(() -> tx.execute(t -> start.start(org, run, revision, key, actor)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("update mission_execution_controls set state='OPEN'");
    jdbc.update("update tasks set task_edit_revision=task_edit_revision+1 where id=?", task);
    assertThatThrownBy(() -> tx.execute(t -> start.start(org, run, revision, key, actor)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("update tasks set task_edit_revision=task_edit_revision-1 where id=?", task);
    assertThat(jdbc.queryForObject("select count(*) from execution_start_dispatch", Integer.class))
        .isZero();
    var receipt = tx.execute(t -> start.start(org, run, revision, key, actor));
    ExecutionStartService.Receipt replay =
        tx.execute(t -> start.start(org, run, revision, key, actor));
    assertThat(replay).isEqualTo(receipt);
    assertThatThrownBy(
            () -> tx.execute(t -> start.start(org, run, revision, UUID.randomUUID(), actor)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", account);
    assertThatThrownBy(() -> tx.execute(t -> start.start(org, run, revision, key, actor)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(jdbc.queryForObject("select count(*) from execution_start_dispatch", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select state from execution_start_dispatch", String.class))
        .isEqualTo("QUEUED");
    var worker = new ExecutionDispatchClaimService(jdbc, claims, start);
    UUID workerId = UUID.randomUUID();
    var expires = OffsetDateTime.now().plusSeconds(30);
    var deadline = expires.plusSeconds(30);
    assertThatThrownBy(
            () ->
                tx.execute(
                    t -> worker.claim(org, receipt.dispatchId(), workerId, expires, deadline)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("update organization_memberships set role='ADMIN' where account_id=?", account);
    assertThatThrownBy(
            () ->
                tx.execute(
                    t ->
                        worker.claim(
                            org, receipt.dispatchId(), workerId, expires.minusHours(1), deadline)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("select state from execution_start_dispatch", String.class))
        .isEqualTo("QUEUED");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("CREATED");
    var token =
        tx.execute(t -> worker.claim(org, receipt.dispatchId(), workerId, expires, deadline));
    assertThat(token.worker()).isEqualTo(workerId);
    assertThatThrownBy(
            () ->
                tx.execute(
                    t -> worker.claim(org, receipt.dispatchId(), workerId, expires, deadline)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("select state from execution_start_dispatch", String.class))
        .isEqualTo("CLAIMED");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("RUNNING");
    try {
      verifyOpenAiFlow(jdbc, tx, claims, start, worker, token, org, run, actor, agent, task);
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }

  void verifyOpenAiFlow(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ExecutionClaimStore claims,
      ExecutionStartService starts,
      ExecutionDispatchClaimService dispatch,
      ExecutionClaimStore.Token previous,
      UUID org,
      UUID run,
      HumanIdentity actor,
      UUID agent,
      UUID task)
      throws Exception {
    tx.executeWithoutResult(s -> claims.invalidate(previous));
    jdbc.update(
        "update execution_attempts set status='FAILED',outcome_slot=attempt_number where id=?",
        previous.attempt());
    jdbc.update(
        "update execution_runs set status='CREATED',revision=revision+1,max_reworks=1 where id=?",
        run);
    jdbc.update("update tasks set status='READY' where id=?", task);
    jdbc.update("update execution_capacity_limits set max_admissions=100,max_concurrent=5");
    jdbc.update("update execution_budget_buckets set limit_microusd=1000000");
    UUID connection =
        jdbc.queryForObject(
            "select id from provider_connections where organization_id=?", UUID.class, org);
    UUID check = UUID.randomUUID();
    var at = OffsetDateTime.now().minusMinutes(1);
    var until = at.plusMinutes(5);
    jdbc.update(
        """
insert into provider_access_checks(id,organization_id,connection_id,actor_id,actor_credential_version,connection_revision,credential_revision,policy_revision,accepted_at,queue_expires_at,worker_owner_id,dispatch_token,started_at,dispatch_deadline,authorized_at,completed_at,outcome,check_kind)
values(?,?,?,?,0,1,1,1,?,?,?,?,?,?,?,?,'ACCESSIBLE','MODEL_DISCOVERY')
""",
        check,
        org,
        connection,
        actor.id(),
        at,
        until,
        UUID.randomUUID(),
        UUID.randomUUID(),
        at,
        until,
        at,
        at.plusSeconds(1));
    jdbc.update(
        "insert into"
            + " provider_model_discovery_snapshots(organization_id,connection_id,check_id,catalogue_state)"
            + " values(?,?,?,'COMPLETE')",
        org,
        connection,
        check);
    jdbc.update(
        "insert into"
            + " provider_model_discovery_entries(organization_id,connection_id,check_id,model_ordinal,model_id)"
            + " values(?,?,?,1,'fixture-model')",
        org,
        connection,
        check);
    jdbc.update(
        "insert into"
            + " agent_model_bindings(organization_id,agent_id,revision,connection_id,check_id,model_id)"
            + " values(?,?,1,?,?,'fixture-model')",
        org,
        agent,
        connection,
        check);
    jdbc.update(
        "insert into"
            + " agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria)"
            + " values(?,'Review','Input','Text','Evidence')",
        agent);
    var grants = mock(AgentModelGrantService.class);
    when(grants.hasCurrentGrant(org, agent, connection, "fixture-model")).thenReturn(true);
    var controls = new MissionExecutionControlStore(jdbc);
    var budgets = new ExecutionBudgetStore(jdbc, claims);
    var model = new ExecutionModelAuthorizationStore(jdbc, claims, budgets, controls, grants, true);
    var connections = mock(com.agentos.providerconnections.ProviderConnectionService.class);
    when(connections.get(actor, org, connection))
        .thenReturn(
            new com.agentos.providerconnections.ProviderConnectionService.Connection(
                org,
                connection,
                com.agentos.providercredentials.CredentialBoundary.Provider.OPENAI,
                "OPENAI_API_KEY",
                "Fixture",
                null,
                "ENABLED",
                1,
                1,
                at,
                at,
                null));
    // The flow uses only identity/version for this lookup; email/displayName are not authority.
    Mockito.when(
            connections.get(
                ArgumentMatchers.any(), ArgumentMatchers.eq(org), ArgumentMatchers.eq(connection)))
        .thenReturn(
            new com.agentos.providerconnections.ProviderConnectionService.Connection(
                org,
                connection,
                com.agentos.providercredentials.CredentialBoundary.Provider.OPENAI,
                "OPENAI_API_KEY",
                "Fixture",
                null,
                "ENABLED",
                1,
                1,
                at,
                at,
                null));
    var revokeBeforeSend = new java.util.concurrent.atomic.AtomicBoolean();
    var credentials = mock(com.agentos.providercredentials.CredentialBoundary.class);
    Mockito.doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              com.agentos.providercredentials.CredentialBoundary.CredentialUse use =
                  invocation.getArgument(2);
              if (revokeBeforeSend.get())
                jdbc.update(
                    "update organization_memberships set role='MEMBER' where account_id=?",
                    actor.id());
              use.accept("disposable-fixture-key".getBytes(StandardCharsets.US_ASCII));
              return null;
            })
        .when(credentials)
        .use(ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.any());
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var malformed = new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext(
        "/completion",
        exchange -> {
          int ordinal = calls.incrementAndGet();
          byte[] sentBody = exchange.getRequestBody().readAllBytes();
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from execution_model_authorizations a join"
                          + " execution_budget_reservations b on b.run_id=a.run_id and"
                          + " b.action_id=a.action_id where a.request_sha256=? and a.handed_off_at"
                          + " is not null and b.status='HELD'",
                      Integer.class,
                      OpenAiExecutionFlow.sha(sentBody)))
              .isEqualTo(1);

          byte[] response =
              (malformed.get()
                      ? "{}"
                      : "{\"id\":\"chatcmpl-"
                          + ordinal
                          + "\",\"object\":\"chat.completion\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Verified"
                          + " result\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    var timer = Executors.newSingleThreadScheduledExecutor();
    try {
      var ctor =
          com.agentos.model.infrastructure.OpenAiTextTransport.class.getDeclaredConstructor(
              java.net.URI.class,
              java.time.Clock.class,
              java.util.concurrent.ScheduledExecutorService.class,
              boolean.class);
      ctor.setAccessible(true);
      var transport =
          ctor.newInstance(
              java.net.URI.create(
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/completion"),
              java.time.Clock.systemUTC(),
              timer,
              true);
      var decisions = new ExecutionReviewDecisionStore(jdbc, claims);
      var outputs = new ExecutionOutputContentStore(jdbc, claims);
      var flow =
          new OpenAiExecutionFlow(
              jdbc,
              tx.getTransactionManager(),
              starts,
              dispatch,
              claims,
              budgets,
              model,
              new ExecutionAttemptReconciliationStore(jdbc, claims),
              outputs,
              new ExecutionRunReviewStore(jdbc, claims),
              decisions,
              connections,
              credentials,
              transport,
              new OpenAiExecutionFlow.Settings("fixture-model", 100, 1000000, 1000000, 20000),
              java.time.Clock.systemUTC());
      UUID firstTask = UUID.randomUUID();
      UUID team =
          jdbc.queryForObject("select team_id from execution_runs where id=?", UUID.class, run);
      UUID mission =
          jdbc.queryForObject("select mission_id from execution_runs where id=?", UUID.class, run);
      jdbc.update(
          "insert into tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id)"
              + " values(?,?,?,?,'First Task','READY',?)",
          firstTask,
          org,
          team,
          mission,
          agent);
      var firstPreflight = flow.taskPreflight(org, team, mission, firstTask, actor);
      UUID firstKey = UUID.randomUUID();
      when(grants.hasCurrentGrant(org, agent, connection, "fixture-model")).thenReturn(false);
      assertThatThrownBy(
              () ->
                  flow.startTask(
                      org, team, mission, firstTask, firstKey, firstPreflight.fingerprint(), actor))
          .isInstanceOf(IllegalStateException.class);
      when(grants.hasCurrentGrant(org, agent, connection, "fixture-model")).thenReturn(true);
      jdbc.update("update tasks set title='Changed since review' where id=?", firstTask);
      assertThatThrownBy(
              () ->
                  flow.startTask(
                      org, team, mission, firstTask, firstKey, firstPreflight.fingerprint(), actor))
          .isInstanceOf(IllegalStateException.class);
      jdbc.update("update tasks set title='First Task' where id=?", firstTask);

      boolean nativePostgres =
          jdbc.execute(
              (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                  c -> "PostgreSQL".equals(c.getMetaData().getDatabaseProductName()));
      OpenAiExecutionFlow.Started firstStart;
      if (nativePostgres) {
        var startRace =
            raceOpenAi(
                () ->
                    flow.startTask(
                        org,
                        team,
                        mission,
                        firstTask,
                        firstKey,
                        firstPreflight.fingerprint(),
                        actor),
                () ->
                    flow.startTask(
                        org,
                        team,
                        mission,
                        firstTask,
                        firstKey,
                        firstPreflight.fingerprint(),
                        actor));
        assertThat(startRace.get(0)).isEqualTo(startRace.get(1));
        firstStart = startRace.get(0);
      } else {
        firstStart =
            flow.startTask(
                org, team, mission, firstTask, firstKey, firstPreflight.fingerprint(), actor);
      }
      assertThat(
              flow.startTask(
                  org, team, mission, firstTask, firstKey, firstPreflight.fingerprint(), actor))
          .isEqualTo(firstStart);
      if (nativePostgres) {
        var replica =
            new OpenAiExecutionFlow(
                jdbc,
                tx.getTransactionManager(),
                starts,
                dispatch,
                claims,
                budgets,
                model,
                new ExecutionAttemptReconciliationStore(jdbc, claims),
                outputs,
                new ExecutionRunReviewStore(jdbc, claims),
                decisions,
                connections,
                credentials,
                transport,
                new OpenAiExecutionFlow.Settings("fixture-model", 100, 1000000, 1000000, 20000),
                java.time.Clock.systemUTC());
        raceOpenAi(
            () -> {
              flow.poll();
              return true;
            },
            () -> {
              replica.poll();
              return true;
            });
      } else {
        flow.poll();
      }
      var firstReview = decisions.current(org, firstStart.runId());
      assertThat(firstReview).isNotNull();
      tx.executeWithoutResult(
          t ->
              decisions.decide(
                  org,
                  actor,
                  firstStart.runId(),
                  new ExecutionReviewDecisionStore.Request(
                      firstReview.attempt(),
                      firstReview.revision(),
                      firstReview.outputSha256(),
                      ExecutionReviewDecisionStore.Decision.ACCEPT,
                      null)));
      assertThat(
              jdbc.queryForObject("select status from tasks where id=?", String.class, firstTask))
          .isEqualTo("COMPLETED");
      for (var decision :
          java.util.List.of(
              ExecutionReviewDecisionStore.Decision.REWORK,
              ExecutionReviewDecisionStore.Decision.ACCEPT)) {
        var preflight = flow.preflight(org, run, actor);
        UUID key = UUID.randomUUID();
        long revision = Long.parseLong(preflight.revision());
        var started = flow.start(org, run, revision, key, preflight.fingerprint(), actor);
        assertThat(flow.start(org, run, revision, key, preflight.fingerprint(), actor))
            .isEqualTo(started);
        assertThatThrownBy(() -> flow.start(org, run, revision, key, "0".repeat(64), actor))
            .isInstanceOf(IllegalStateException.class);
        flow.poll();
        assertThat(
                jdbc.queryForObject(
                    "select status from execution_runs where id=?", String.class, run))
            .isEqualTo("IN_REVIEW");
        var review = decisions.current(org, run);
        assertThat(outputs.read(run, review.attempt(), review.outputSha256()))
            .isEqualTo("Verified result");
        tx.executeWithoutResult(
            t ->
                decisions.decide(
                    org,
                    actor,
                    run,
                    new ExecutionReviewDecisionStore.Request(
                        review.attempt(),
                        review.revision(),
                        review.outputSha256(),
                        decision,
                        decision == ExecutionReviewDecisionStore.Decision.REWORK
                            ? "More evidence"
                            : null)));
        assertThat(
                jdbc.queryForObject(
                    "select state from execution_start_dispatch where id=?",
                    String.class,
                    started.dispatchId()))
            .isEqualTo("FINISHED");
        assertThat(
                jdbc.queryForObject(
                    "select status from execution_budget_reservations where action_id=?",
                    String.class,
                    started.dispatchId()))
            .isEqualTo("SETTLED");
      }
      assertThat(calls.get()).isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select status from execution_runs where id=?", String.class, run))
          .isEqualTo("COMPLETED");
      // Revoke the issuer after budget authorization but before committed handoff.
      jdbc.update("update execution_runs set status='CREATED',revision=revision+1 where id=?", run);
      jdbc.update("update tasks set status='READY' where id=?", task);
      revokeBeforeSend.set(true);
      var revokedPreflight = flow.preflight(org, run, actor);
      var revoked =
          flow.start(
              org,
              run,
              Long.parseLong(revokedPreflight.revision()),
              UUID.randomUUID(),
              revokedPreflight.fingerprint(),
              actor);
      flow.poll();
      assertThat(calls.get()).isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select state from execution_start_dispatch where id=?",
                  String.class,
                  revoked.dispatchId()))
          .isEqualTo("FINISHED");
      assertThat(
              jdbc.queryForObject(
                  "select actual_microusd from execution_budget_reservations where action_id=?",
                  Long.class,
                  revoked.dispatchId()))
          .isZero();
      revokeBeforeSend.set(false);
      jdbc.update(
          "update organization_memberships set role='ADMIN' where account_id=?", actor.id());
      // Controlled fixture reset exercises post-delivery ambiguity without accepting a fabricated
      // result.
      jdbc.update("update execution_runs set status='CREATED',revision=revision+1 where id=?", run);
      jdbc.update(
          "update tasks set status='READY',pending_reason=null,waiting_since=null,resolver=null"
              + " where id=?",
          task);
      malformed.set(true);
      var p = flow.preflight(org, run, actor);
      var unknown =
          flow.start(
              org, run, Long.parseLong(p.revision()), UUID.randomUUID(), p.fingerprint(), actor);
      flow.poll();
      flow.poll();
      assertThat(calls.get()).isEqualTo(4);
      assertThat(
              jdbc.queryForObject(
                  "select state from execution_start_dispatch where id=?",
                  String.class,
                  unknown.dispatchId()))
          .isEqualTo("UNKNOWN");
      assertThat(
              jdbc.queryForObject(
                  "select status from execution_budget_reservations where action_id=?",
                  String.class,
                  unknown.dispatchId()))
          .isEqualTo("UNKNOWN");
      assertThat(
              jdbc.queryForObject(
                  "select status from execution_runs where id=?", String.class, run))
          .isEqualTo("BLOCKED");
      assertThat(decisions.current(org, run)).isNull();
    } finally {
      server.stop(0);
      timer.shutdownNow();
    }
  }

  private <T> java.util.List<T> raceOpenAi(
      java.util.concurrent.Callable<T> first, java.util.concurrent.Callable<T> second)
      throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("Race start timeout");
                return first.call();
              });
      var b =
          pool.submit(
              () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("Race start timeout");
                return second.call();
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      return java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
  }

  private void verifyAtomicPublication(
      JdbcTemplate jdbc, TransactionTemplate tx, ExecutionClaimStore claims, UUID org, UUID run) {
    UUID attempt = UUID.randomUUID();
    jdbc.update(
        "update missions set status='RUNNING' where id=(select mission_id from execution_runs where"
            + " id=?)",
        run);
    jdbc.update("update execution_runs set status='BLOCKED' where id=?", run);
    jdbc.update(
        "update tasks set"
            + " status='BLOCKED',pending_reason='OUTCOME_UNKNOWN',waiting_since=current_timestamp,resolver='Reconciler'"
            + " where id=(select task_id from execution_runs where id=?)",
        run);
    jdbc.update(
        "update mission_execution_controls set state='OPEN' where mission_id=(select mission_id"
            + " from execution_runs where id=?)",
        run);
    jdbc.update(
        "insert into execution_attempts(id,run_id,attempt_number,status,outcome_slot)"
            + " values(?,?,4,'UNKNOWN',0)",
        attempt,
        run);
    long revision =
        jdbc.queryForObject("select revision from execution_runs where id=?", Long.class, run);
    String text = "verified output";
    String digest = "eecf56ab92af6fc765c7564f294a6f24b1cd2b4852b0c42d636d77d1501fb664";
    var publisher =
        new ExecutionResultPublicationService(
            tx.getTransactionManager(),
            new ExecutionAttemptReconciliationStore(jdbc, claims),
            new ExecutionOutputContentStore(jdbc, claims),
            new ExecutionRunReviewStore(jdbc, claims));
    assertThatThrownBy(
            () ->
                publisher.publishVerified(
                    org, run, attempt, revision, "a".repeat(64), digest, "wrong content"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                publisher.publishVerified(
                    org, run, attempt, revision + 1, "a".repeat(64), digest, text))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select status from execution_attempts where id=?", String.class, attempt))
        .isEqualTo("UNKNOWN");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_attempt_reconciliations where attempt_id=?",
                Integer.class,
                attempt))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_verified_output_content where attempt_id=?",
                Integer.class,
                attempt))
        .isZero();
    tx.executeWithoutResult(
        status -> {
          assertThatThrownBy(
                  () ->
                      publisher.publishVerified(
                          org, run, attempt, revision, "a".repeat(64), digest, text))
              .hasMessage("Result publication requires an independent worker transaction.");
        });
    publisher.publishVerified(org, run, attempt, revision, "a".repeat(64), digest, text);
    publisher.publishVerified(org, run, attempt, revision, "a".repeat(64), digest, text);
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("IN_REVIEW");
    assertThat(
            jdbc.queryForObject("select revision from execution_runs where id=?", Long.class, run))
        .isEqualTo(revision + 1);
    assertThat(
            jdbc.queryForObject(
                "select content from execution_verified_output_content where attempt_id=?",
                String.class,
                attempt))
        .isEqualTo(text);
  }

  private void verifyReconciledReview(
      JdbcTemplate jdbc, TransactionTemplate tx, ExecutionClaimStore claims, UUID org, UUID run) {
    var review = new ExecutionRunReviewStore(jdbc, claims);
    var reconcile = new ExecutionAttemptReconciliationStore(jdbc, claims);
    // Exercise the late-success branch independently of the cancellation scenario.
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "update tasks set pending_reason='OUTCOME_UNKNOWN' where id="
                  + "(select task_id from execution_runs where id=?)",
              run);
          UUID attempt = UUID.randomUUID();
          jdbc.update(
              """
              insert into execution_attempts (id,run_id,attempt_number,status,outcome_slot)
              values (?,?,3,'UNKNOWN',0)
              """,
              attempt,
              run);
          String textOutput = "verified output";
          String output = "eecf56ab92af6fc765c7564f294a6f24b1cd2b4852b0c42d636d77d1501fb664";
          long revision =
              jdbc.queryForObject(
                  "select revision from execution_runs where id=?", Long.class, run);
          assertThatThrownBy(() -> review.submitReconciled(org, run, attempt, revision, output))
              .hasMessage("Reconciled output review is unavailable.");
          reconcile.recordVerified(
              org,
              run,
              attempt,
              ExecutionAttemptReconciliationStore.Outcome.SUCCEEDED,
              "a".repeat(64),
              output);
          assertThatThrownBy(
                  () -> review.submitReconciled(org, run, attempt, revision, "e".repeat(64)))
              .hasMessage("Reconciled output review is unavailable.");
          assertThatThrownBy(() -> review.submitReconciled(org, run, attempt, revision + 1, output))
              .hasMessage("Reconciled output review is unavailable.");

          var outputs = new ExecutionOutputContentStore(jdbc, claims);
          assertThatThrownBy(() -> outputs.recordVerified(org, run, attempt, "different output"))
              .isInstanceOf(IllegalStateException.class);
          assertThatThrownBy(() -> outputs.recordVerified(org, run, attempt, "x".repeat(262145)))
              .isInstanceOf(IllegalStateException.class);
          outputs.recordVerified(org, run, attempt, textOutput);
          outputs.recordVerified(org, run, attempt, textOutput);
          assertThat(outputs.read(run, attempt, output)).isEqualTo(textOutput);
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from execution_verified_output_content", Integer.class))
              .isEqualTo(1);
          review.submitReconciled(org, run, attempt, revision, output);
          review.submitReconciled(org, run, attempt, revision, output);
          assertThat(jdbc.queryForMap("select status,revision from execution_runs where id=?", run))
              .containsEntry("status", "IN_REVIEW")
              .containsEntry("revision", revision + 1);
          assertThat(
                  jdbc.queryForMap(
                      "select status,pending_reason from tasks where id=(select task_id from"
                          + " execution_runs where id=?)",
                      run))
              .containsEntry("status", "IN_REVIEW")
              .containsEntry("pending_reason", null);
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from execution_reconciled_review_submissions",
                      Integer.class))
              .isEqualTo(1);
          verifyReviewDecisions(jdbc, claims, status, org, run, attempt, revision + 1, output);
          status.setRollbackOnly();
        });
  }

  private void verifyReviewDecisions(
      JdbcTemplate jdbc,
      ExecutionClaimStore claims,
      TransactionStatus status,
      UUID org,
      UUID run,
      UUID attempt,
      long revision,
      String output) {
    var decisions = new ExecutionReviewDecisionStore(jdbc, claims);
    UUID account = jdbc.queryForObject("select id from human_accounts", UUID.class);
    var reviewer = new HumanIdentity(account, "admin@example.test", "Admin");
    var accept =
        new ExecutionReviewDecisionStore.Request(
            attempt, revision, output, ExecutionReviewDecisionStore.Decision.ACCEPT, null);
    var rework =
        new ExecutionReviewDecisionStore.Request(
            attempt,
            revision,
            output,
            ExecutionReviewDecisionStore.Decision.REWORK,
            " Add verification ");
    jdbc.update(
        "insert into organization_memberships (account_id,organization_id,role) values"
            + " (?,?,'MEMBER')",
        account,
        org);
    assertThatThrownBy(() -> decisions.decide(org, reviewer, run, accept))
        .hasMessage("Request denied");
    jdbc.update(
        "update organization_memberships set role='ADMIN' where account_id=? and organization_id=?",
        account,
        org);
    assertThatThrownBy(
            () ->
                decisions.decide(
                    org,
                    new HumanIdentity(account, reviewer.email(), reviewer.displayName(), 999),
                    run,
                    accept))
        .hasMessage("Request denied");
    assertThatThrownBy(() -> decisions.decide(org, reviewer, run, rework))
        .hasMessage("Execution output review decision is unavailable.");

    Object savepoint = status.createSavepoint();
    jdbc.update("update execution_runs set max_reworks=1 where id=?", run);
    decisions.decide(org, reviewer, run, rework);
    decisions.decide(org, reviewer, run, rework);
    assertThat(jdbc.queryForMap("select status,revision from execution_runs where id=?", run))
        .containsEntry("status", "CREATED")
        .containsEntry("revision", revision + 1);
    assertThat(jdbc.queryForObject("select reason from execution_review_decisions", String.class))
        .isEqualTo("Add verification");
    assertThat(
            jdbc.queryForObject(
                "select status from tasks where id=(select task_id from execution_runs where id=?)",
                String.class,
                run))
        .isEqualTo("READY");
    status.rollbackToSavepoint(savepoint);
    status.releaseSavepoint(savepoint);

    decisions.decide(org, reviewer, run, accept);
    decisions.decide(org, reviewer, run, accept);
    assertThatThrownBy(() -> decisions.decide(org, reviewer, run, rework))
        .hasMessage("Execution output review decision is unavailable.");
    assertThat(
            jdbc.queryForObject("select status from execution_runs where id=?", String.class, run))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select status from execution_attempts where id=?", String.class, attempt))
        .isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_events where event_type='EXECUTION_OUTPUT_ACCEPTED'",
                Integer.class))
        .isEqualTo(1);
  }

  private UUID verifyBudget(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ExecutionClaimStore claims,
      ExecutionClaimStore.Token token,
      UUID team,
      UUID agent,
      UUID mission,
      MissionExecutionControlStore controls,
      MissionExecutionControlStore.Scope scope) {
    var budget = new ExecutionBudgetStore(jdbc, claims);
    var action = UUID.randomUUID();
    var rejected = UUID.randomUUID();
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, action, 40)))
        .isInstanceOf(IllegalStateException.class);
    var open = OffsetDateTime.now().minusHours(1);
    var close = open.plusHours(2);
    String insertBucket =
        """
        insert into execution_budget_buckets
          (id,organization_id,scope,subject_id,opens_at,closes_at,limit_microusd)
        values (?,?,?,?,?,?,?)
        """;
    UUID org = token.organization();
    jdbc.update(insertBucket, UUID.randomUUID(), org, "ORGANIZATION", org, open, close, 100);
    jdbc.update(insertBucket, UUID.randomUUID(), org, "TEAM", team, open, close, 100);
    jdbc.update(insertBucket, UUID.randomUUID(), org, "AGENT", agent, open, close, 100);
    jdbc.update(insertBucket, UUID.randomUUID(), org, "MISSION", mission, open, close, 50);
    String insertLimit =
        """
        insert into execution_capacity_limits
          (organization_id,scope,subject_id,max_concurrent,max_admissions,window_seconds)
        values (?,?,?,2,2,60)
        """;
    jdbc.update(insertLimit, org, "ORGANIZATION", org);
    jdbc.update(insertLimit, org, "TEAM", team);
    jdbc.update(insertLimit, org, "AGENT", agent);
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, action, 40)))
        .hasMessage("Execution capacity is unavailable.");
    jdbc.update(insertLimit, org, "MISSION", mission);
    var connection = UUID.randomUUID();
    jdbc.update(
        """
insert into provider_credential_versions
  (organization_id,connection_id,provider,credential_revision,external_locator,
   external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)
values (?,?,'OPENAI',1,'fixture-locator','fixture-key','fixture-hmac','v2',?,current_timestamp)
""",
        org,
        connection,
        "0".repeat(44));
    jdbc.update(
        """
insert into provider_connections
  (id,organization_id,provider,mode,name,state,configuration_revision,
   credential_revision,created_at,updated_at)
values (?,?,'OPENAI','OPENAI_API_KEY','Fixture','ENABLED',1,1,current_timestamp,current_timestamp)
""",
        connection,
        org);
    var grants = mock(AgentModelGrantService.class);
    var authorizations =
        new ExecutionModelAuthorizationStore(jdbc, claims, budget, controls, grants, true);
    var request =
        new ExecutionModelAuthorizationStore.Request(
            action,
            connection,
            "fixture-model",
            "c7183c384504fd060c8a5d4b1b6545b830716de8bffbdc2989923a2244f6697d",
            40);
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> authorizations.authorize(token, request)))
        .hasMessage("Model action authorization is unavailable.");
    jdbc.update("update agents set status='ACTIVE' where id=?", agent);
    jdbc.update("update missions set status='RUNNING' where id=?", mission);
    jdbc.update(
        "update tasks set status='RUNNING',owner_agent_id=? where id="
            + "(select task_id from execution_runs where id=?)",
        agent,
        token.run());
    jdbc.update(
        """
        insert into provider_deployment_policy
          (id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)
        values (1,1,true,false,false,current_timestamp,current_timestamp)
        """);
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> authorizations.authorize(token, request)))
        .hasMessage("Model action authorization is unavailable.");
    when(grants.hasCurrentGrant(org, agent, connection, "fixture-model")).thenReturn(true);
    assertThat(
            tx.<Long>execute(
                status ->
                    controls.change(scope, 3, MissionExecutionControlStore.State.PAUSE_REQUESTED)))
        .isEqualTo(4);
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> authorizations.authorize(token, request)))
        .hasMessage("Mission execution control is unavailable.");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_budget_reservations", Integer.class))
        .isZero();
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> controls.change(scope, 3, MissionExecutionControlStore.State.OPEN)))
        .hasMessage("Mission execution control is unavailable.");
    assertThat(
            tx.<Long>execute(
                status -> controls.change(scope, 4, MissionExecutionControlStore.State.OPEN)))
        .isEqualTo(5);
    tx.executeWithoutResult(status -> authorizations.authorize(token, request));
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> authorizations.authorize(token, request)))
        .hasMessage("Model action authorization is unavailable.");
    assertThat(
            jdbc.queryForMap(
                "select fence,model_id,request_sha256,control_revision from"
                    + " execution_model_authorizations"))
        .containsEntry("fence", token.fence())
        .containsEntry("control_revision", 5L)
        .containsEntry("model_id", request.model())
        .containsEntry("request_sha256", request.sha256());
    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> budget.markUnknown(org, token.run(), action)))
        .hasMessage("Execution budget is unavailable.");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        budget.settleModelUsage(
                            org, token.run(), action, connection, "req_early", 1, 1, 5)))
        .hasMessage("Execution budget is unavailable.");
    verifyHandoff(jdbc, tx, authorizations, token, request, grants);
    assertThat(tx.<Boolean>execute(status -> budget.reserve(token, action, 40))).isFalse();
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, action, 39)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, rejected, 11)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_budget_reservations", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForList("select held_microusd from execution_budget_buckets", Long.class))
        .containsExactly(40L, 40L, 40L, 40L);

    jdbc.update("update execution_capacity_limits set max_concurrent=1 where scope='MISSION'");
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, rejected, 1)))
        .hasMessage("Execution capacity is unavailable.");
    tx.executeWithoutResult(status -> budget.markUnknown(org, token.run(), action));
    var firstUncertainObservation =
        jdbc.queryForObject(
            "select delivery_unknown_at from execution_model_authorizations", OffsetDateTime.class);
    assertThat(firstUncertainObservation).isNotNull();
    tx.executeWithoutResult(status -> budget.markUnknown(org, token.run(), action));
    assertThat(
            jdbc.queryForObject(
                "select delivery_unknown_at from execution_model_authorizations",
                OffsetDateTime.class))
        .isEqualTo(firstUncertainObservation);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> budget.settleVerified(org, token.run(), action, 55)))
        .hasMessage("Execution budget is unavailable.");

    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, rejected, 1)))
        .hasMessage("Execution capacity is unavailable.");
    assertThat(
            jdbc.queryForObject("select status from execution_budget_reservations", String.class))
        .isEqualTo("UNKNOWN");
    assertThat(jdbc.queryForList("select held_microusd from execution_budget_buckets", Long.class))
        .containsExactly(40L, 40L, 40L, 40L);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> budget.settleVerified(UUID.randomUUID(), token.run(), action, 55)))
        .isInstanceOf(IllegalStateException.class);
    String requestId = "req_fixture";
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        budget.settleModelUsage(
                            org, token.run(), action, UUID.randomUUID(), requestId, 17, 6, 55)))
        .hasMessage("Execution budget is unavailable.");
    assertThat(jdbc.queryForList("select held_microusd from execution_budget_buckets", Long.class))
        .containsExactly(40L, 40L, 40L, 40L);
    tx.executeWithoutResult(
        status ->
            budget.settleModelUsage(org, token.run(), action, connection, requestId, 17, 6, 55));
    tx.executeWithoutResult(
        status ->
            budget.settleModelUsage(org, token.run(), action, connection, requestId, 17, 6, 55));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        budget.settleModelUsage(
                            org, token.run(), action, connection, requestId, 18, 6, 55)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select delivery_resolution from execution_model_authorizations", String.class))
        .isEqualTo("VERIFIED_USAGE");
    assertThat(
            jdbc.queryForObject(
                "select reconciled_at from execution_model_authorizations", OffsetDateTime.class))
        .isAfterOrEqualTo(firstUncertainObservation);
    assertThat(jdbc.queryForMap("select input_tokens,output_tokens from execution_model_usage"))
        .containsEntry("input_tokens", 17L)
        .containsEntry("output_tokens", 6L);
    assertThat(
            jdbc.queryForObject(
                "select provider_request_sha256 from execution_model_usage", String.class))
        .matches("[0-9a-f]{64}");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> budget.settleVerified(org, token.run(), action, 54)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForList("select held_microusd from execution_budget_buckets", Long.class))
        .containsExactly(0L, 0L, 0L, 0L);
    assertThat(jdbc.queryForList("select spent_microusd from execution_budget_buckets", Long.class))
        .containsExactly(55L, 55L, 55L, 55L);
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, rejected, 1)))
        .hasMessage("Execution budget is unavailable.");

    jdbc.update("update execution_capacity_limits set max_admissions=1 where scope='MISSION'");
    jdbc.update(
        "update execution_budget_buckets set closes_at=? where scope='MISSION'",
        OffsetDateTime.now().minusMinutes(1));
    jdbc.update(
        insertBucket,
        UUID.randomUUID(),
        org,
        "MISSION",
        mission,
        OffsetDateTime.now().minusMinutes(1),
        close,
        50);
    assertThatThrownBy(() -> tx.execute(status -> budget.reserve(token, rejected, 1)))
        .hasMessage("Execution capacity is unavailable.");
    jdbc.update(
        "update execution_budget_reservations set admitted_at=? where action_id=?",
        OffsetDateTime.now().minusMinutes(2),
        action);
    assertThat(tx.<Boolean>execute(status -> budget.reserve(token, rejected, 1))).isTrue();
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        budget.settleModelUsage(
                            org, token.run(), rejected, connection, requestId, 17, 6, 55)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "select status from execution_budget_reservations where action_id=?",
                String.class,
                rejected))
        .isEqualTo("HELD");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.update(
                          "delete from execution_budget_reservation_buckets where action_id=? and"
                              + " bucket_id in (select id from execution_budget_buckets where"
                              + " scope='ORGANIZATION')",
                          rejected);
                      budget.settleModelUsage(
                          org, token.run(), rejected, connection, "req_rollback", 3, 2, 1);
                    }))
        .hasMessage("Execution budget is unavailable.");
    assertThat(jdbc.queryForObject("select count(*) from execution_model_usage", Integer.class))
        .isEqualTo(1);
    tx.executeWithoutResult(status -> budget.settleVerified(org, token.run(), rejected, 0));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        budget.settleModelUsage(
                            org, token.run(), rejected, connection, "req_late", 0, 0, 0)))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update(
        "update execution_budget_reservations set admitted_at=? where action_id=?",
        OffsetDateTime.now().minusMinutes(2),
        rejected);
    for (boolean handedOff : new boolean[] {false, true}) {
      UUID noCharge = UUID.randomUUID();
      var zeroRequest =
          new ExecutionModelAuthorizationStore.Request(
              noCharge, connection, request.model(), request.sha256(), 1);
      tx.executeWithoutResult(status -> authorizations.authorize(token, zeroRequest));
      if (handedOff) {
        tx.executeWithoutResult(
            status ->
                authorizations.handoff(
                    token,
                    noCharge,
                    connection,
                    request.model(),
                    "fixture-body".getBytes(StandardCharsets.UTF_8)));
      }
      tx.executeWithoutResult(status -> budget.settleVerified(org, token.run(), noCharge, 0));
      assertThat(
              jdbc.queryForObject(
                  "select delivery_resolution from execution_model_authorizations where"
                      + " action_id=?",
                  String.class,
                  noCharge))
          .isEqualTo(handedOff ? "VERIFIED_NO_CHARGE" : "NOT_HANDED_OFF");
      jdbc.update(
          "update execution_budget_reservations set admitted_at=? where action_id=?",
          OffsetDateTime.now().minusMinutes(2),
          noCharge);
    }
    var pending = UUID.randomUUID();
    assertThat(tx.<Boolean>execute(status -> budget.reserve(token, pending, 1))).isTrue();
    return pending;
  }

  private void verifyHandoff(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ExecutionModelAuthorizationStore authorizations,
      ExecutionClaimStore.Token token,
      ExecutionModelAuthorizationStore.Request request,
      AgentModelGrantService grants) {
    byte[] body = "fixture-body".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        authorizations.handoff(
                            token,
                            request.action(),
                            request.connection(),
                            request.model(),
                            new byte[] {1})))
        .hasMessage("Model action authorization is unavailable.");

    var agent =
        jdbc.queryForObject("select agent_id from execution_model_authorizations", UUID.class);
    when(grants.hasCurrentGrant(token.organization(), agent, request.connection(), request.model()))
        .thenReturn(false);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        authorizations.handoff(
                            token, request.action(), request.connection(), request.model(), body)))
        .hasMessage("Model action authorization is unavailable.");
    when(grants.hasCurrentGrant(token.organization(), agent, request.connection(), request.model()))
        .thenReturn(true);
    tx.executeWithoutResult(
        status -> {
          authorizations.handoff(
              token, request.action(), request.connection(), request.model(), body);
          status.setRollbackOnly();
        });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from execution_model_authorizations where handed_off_at is not"
                    + " null",
                Integer.class))
        .isZero();

    var handoff =
        tx.execute(
            status ->
                authorizations.handoff(
                    token, request.action(), request.connection(), request.model(), body));
    assertThat(handoff.credentialRevision()).isEqualTo(1L);
    assertThat(handoff.body().isReadOnly()).isTrue();
    body[0] = 0;
    assertThat(StandardCharsets.UTF_8.decode(handoff.body()).toString()).isEqualTo("fixture-body");
    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        authorizations.handoff(
                            token,
                            request.action(),
                            request.connection(),
                            request.model(),
                            "fixture-body".getBytes(StandardCharsets.UTF_8))))
        .hasMessage("Model action authorization is unavailable.");
  }

  private ExecutionClaimStore.Token tryIssue(
      TransactionTemplate tx,
      ExecutionClaimStore store,
      UUID org,
      UUID run,
      UUID attempt,
      UUID worker,
      OffsetDateTime expiry,
      OffsetDateTime deadline) {
    try {
      return tx.execute(status -> store.issueFresh(org, run, attempt, worker, expiry, deadline));
    } catch (IllegalStateException denial) {
      assertThat(denial).hasMessage("Execution claim is unavailable.");
      return null;
    }
  }
}

package com.agentos.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    var store = new ExecutionClaimStore(jdbc);
    var deadline = OffsetDateTime.now().plusMinutes(10);
    var expiry = deadline.minusMinutes(2);
    var firstWorker = UUID.randomUUID();
    var secondWorker = UUID.randomUUID();
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
    tx.executeWithoutResult(status -> store.invalidate(token));
    assertThat(tryIssue(tx, store, org, run, attempt, token.worker(), expiry, deadline)).isNull();

    jdbc.update("update execution_attempts set status='FAILED',outcome_slot=2 where id=?", attempt);
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
    verifyBudget(jdbc, tx, store, next, team, agent, mission);
    jdbc.update("update execution_runs set status='CANCELLING' where id=?", run);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.requireCurrent(next)))
        .isInstanceOf(IllegalStateException.class);
  }

  private void verifyBudget(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ExecutionClaimStore claims,
      ExecutionClaimStore.Token token,
      UUID team,
      UUID agent,
      UUID mission) {
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
    assertThat(tx.<Boolean>execute(status -> budget.reserve(token, action, 40))).isTrue();
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

    tx.executeWithoutResult(status -> budget.markUnknown(org, token.run(), action));
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
    tx.executeWithoutResult(status -> budget.settleVerified(org, token.run(), action, 55));
    tx.executeWithoutResult(status -> budget.settleVerified(org, token.run(), action, 55));
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
        .isInstanceOf(IllegalStateException.class);
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

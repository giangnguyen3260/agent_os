package com.agentos.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RuntimePersistenceTest {
  @Test
  void migrationPreservesDraftsAndEnforcesPlanScopeAndOneUnresolvedAttempt() {
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
          "execution_attempts"
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
  }
}

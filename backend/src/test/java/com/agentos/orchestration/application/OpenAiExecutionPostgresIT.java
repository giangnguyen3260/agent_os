package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit native gate; no silent environment fallback or production database seed. */
class OpenAiExecutionPostgresIT {
  @Test
  void nativeStartRaceCompetingWorkersAndFullResultReviewLoop() throws Exception {
    String url = required("TASK_EDIT_TEST_JDBC_URL"),
        user = required("TASK_EDIT_TEST_DB_USER"),
        password = required("TASK_EDIT_TEST_DB_PASSWORD");
    if (!url.startsWith("jdbc:postgresql:"))
      throw new IllegalStateException("Native PostgreSQL required");
    var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    String schema = "openai_flow_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("create schema " + schema);
    try {
      var source = new DriverManagerDataSource(url, user, password);
      var props = new Properties();
      props.setProperty("currentSchema", schema);
      source.setConnectionProperties(props);
      Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
      var jdbc = new JdbcTemplate(source);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
      UUID org = UUID.randomUUID(),
          team = UUID.randomUUID(),
          mission = UUID.randomUUID(),
          task = UUID.randomUUID(),
          agent = UUID.randomUUID(),
          actor = UUID.randomUUID(),
          run = UUID.randomUUID(),
          attempt = UUID.randomUUID(),
          connection = UUID.randomUUID();
      jdbc.update(
          "insert into organizations(id,name,slug,status,created_at,updated_at)"
              + " values(?,'Fixture',?,'ACTIVE',current_timestamp,current_timestamp)",
          org,
          org.toString());
      jdbc.update(
          "insert into human_accounts(id,email,display_name,password_hash)"
              + " values(?,?,'Fixture','unused')",
          actor,
          actor + "@example.test");
      jdbc.update(
          "insert into organization_memberships(account_id,organization_id,role)"
              + " values(?,?,'ADMIN')",
          actor,
          org);
      jdbc.update(
          "insert into teams(id,organization_id,name,status,created_at,updated_at)"
              + " values(?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
          team,
          org);
      jdbc.update(
          "insert into"
              + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
              + " values(?,?,?,'Agent','Reviewer','MID','ACTIVE','SUGGEST',current_timestamp,current_timestamp)",
          agent,
          org,
          team);
      jdbc.update(
          "insert into"
              + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
              + " values(?,?,?,'Mission','Goal','RUNNING','NORMAL',current_timestamp,current_timestamp)",
          mission,
          org,
          team);
      jdbc.update(
          "insert into tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id)"
              + " values(?,?,?,?,'Task','RUNNING',?)",
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
              + " execution_runs(id,organization_id,team_id,mission_id,task_id,plan_revision,status,revision)"
              + " values(?,?,?,?,?,1,'RUNNING',0)",
          run,
          org,
          team,
          mission,
          task);
      jdbc.update(
          "insert into execution_attempts(id,run_id,attempt_number,status,outcome_slot)"
              + " values(?,?,1,'RUNNING',0)",
          attempt,
          run);
      jdbc.update(
          "insert into"
              + " mission_execution_controls(organization_id,team_id,mission_id,state,revision)"
              + " values(?,?,?,'OPEN',1)",
          org,
          team,
          mission);
      for (var entry :
          java.util.Map.of("ORGANIZATION", org, "TEAM", team, "AGENT", agent, "MISSION", mission)
              .entrySet()) {
        jdbc.update(
            "insert into"
                + " execution_budget_buckets(id,organization_id,scope,subject_id,opens_at,closes_at,limit_microusd)"
                + " values(?,?,?,?,?,?,1000000)",
            UUID.randomUUID(),
            org,
            entry.getKey(),
            entry.getValue(),
            OffsetDateTime.now().minusHours(1),
            OffsetDateTime.now().plusHours(1));
        jdbc.update(
            "insert into"
                + " execution_capacity_limits(organization_id,scope,subject_id,max_concurrent,max_admissions,window_seconds)"
                + " values(?,?,?,5,100,60)",
            org,
            entry.getKey(),
            entry.getValue());
      }
      jdbc.update(
          "insert into"
              + " provider_deployment_policy(id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)"
              + " values(1,1,true,false,false,current_timestamp,current_timestamp)");
      jdbc.update(
          "insert into"
              + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
              + " values(?,?,'OPENAI',1,'fixture-locator','fixture-key','fixture-hmac','v2',?,current_timestamp)",
          org,
          connection,
          "0".repeat(44));
      jdbc.update(
          "insert into"
              + " provider_connections(id,organization_id,provider,mode,name,state,configuration_revision,credential_revision,created_at,updated_at)"
              + " values(?,?,'OPENAI','OPENAI_API_KEY','Fixture','ENABLED',1,1,current_timestamp,current_timestamp)",
          connection,
          org);
      var controls = new MissionExecutionControlStore(jdbc);
      var claims = new ExecutionClaimStore(jdbc, controls);
      var token =
          tx.execute(
              s ->
                  claims.issueFresh(
                      org,
                      run,
                      attempt,
                      UUID.randomUUID(),
                      OffsetDateTime.now().plusMinutes(2),
                      OffsetDateTime.now().plusMinutes(3)));
      var starts = new ExecutionStartService(jdbc, new ExecutionReviewDecisionStore(jdbc, claims));
      new RuntimePersistenceTest()
          .verifyOpenAiFlow(
              jdbc,
              tx,
              claims,
              starts,
              new ExecutionDispatchClaimService(jdbc, claims, starts),
              token,
              org,
              run,
              new HumanIdentity(actor, actor + "@example.test", "Fixture"),
              agent,
              task);
    } finally {
      admin.execute("drop schema " + schema + " cascade");
    }
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank())
      throw new IllegalStateException(
          "Explicit disposable PostgreSQL configuration required: " + key);
    return value;
  }
}

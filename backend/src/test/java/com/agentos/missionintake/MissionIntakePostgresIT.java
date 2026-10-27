package com.agentos.missionintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Explicit native gate: -Dtest=MissionIntakePostgresIT; missing configuration fails, never skips.
 * MISSION_INTAKE_TEST_JDBC_URL / DB_USER / DB_PASSWORD accept corresponding TASK_EDIT_TEST_*
 * fallbacks. Credentials are read only from the environment and never printed.
 */
class MissionIntakePostgresIT extends MissionIntakeIntegrationTest {
  private static String required(String name) {
    String value=System.getenv(name);
    if (value==null || value.isBlank()) value=System.getenv(name.replace("MISSION_INTAKE_TEST_","TASK_EDIT_TEST_"));
    if(value==null || value.isBlank() || (name.endsWith("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException("Explicit native PostgreSQL configuration required: "+name+" (or "+name.replace("MISSION_INTAKE_TEST_","TASK_EDIT_TEST_")+")");
    return value;
  }
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url",()->required("MISSION_INTAKE_TEST_JDBC_URL"));
    p.add("spring.datasource.username",()->required("MISSION_INTAKE_TEST_DB_USER"));
    p.add("spring.datasource.password",()->required("MISSION_INTAKE_TEST_DB_PASSWORD"));
    p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Test
  void sameKeyRaceHasOneEffect() throws Exception {
    UUID key=UUID.randomUUID(); var c=command(context());
    var results=race(()->save(key,c),()->save(key,c));
    assertThat(results.get(0).receipt()).isEqualTo(results.get(1).receipt());
    assertThat(results).extracting(MissionIntakeDtos.Result::replay).containsExactlyInAnyOrder(true,false);
    assertThat(count("mission_intake_receipts")).isEqualTo(1);
    assertThat(count("audit_events")).isEqualTo(1);
  }
  @Test
  void differentKeysHaveOneWinner() throws Exception {
    var c=command(context());
    assertThat(race(()->attempt(()->save(UUID.randomUUID(),c)),()->attempt(()->save(UUID.randomUUID(),c))))
        .containsExactlyInAnyOrder("SUCCESS","MISSION_INTAKE_CONFLICT");
    assertThat(count("mission_intake_receipts")).isEqualTo(1);
  }
  @Test
  void realDraftEditRaceSerializes() throws Exception {
    var edit= drafts.context(org,team,mission,task,human);
    var c=command(context());
    var results=race(()->attempt(()->save(UUID.randomUUID(),c)),()->attempt(()->drafts.update(org,team,mission,task,human,UUID.randomUUID(),new com.agentos.taskediting.TaskDraftEditDtos.Command(edit.revision(),edit.fingerprint(),"Changed","Description","Criteria","HIGH"))));
    assertThat(results.get(0)).isEqualTo("SUCCESS");
    assertThat(results.get(1)).isIn("SUCCESS","TASK_EDIT_CONFLICT");
    assertThat(context().intakePaused()).isTrue();
  }
  @org.springframework.beans.factory.annotation.Autowired com.agentos.taskediting.TaskDraftEditService drafts;

  @Test
  void realTaskCreateRaceInvalidatesEarlierIntakeVersion() throws Exception {
    var c=command(context());
    var input=new com.agentos.mission.api.dto.TaskCreateRequest("New","Description","Criteria","NORMAL",null,List.of(),0L);
    var results=race(()->attempt(()->save(UUID.randomUUID(),c)),()->attempt(()->taskCreate.create(org,team,mission,human,UUID.randomUUID(),input)));
    assertThat(results).contains("SUCCESS");
    assertThat(results.get(0)).isIn("SUCCESS","MISSION_INTAKE_CONFLICT");
    assertThat(results.get(1)).isIn("SUCCESS","TASK_SETUP_CONFLICT");
    assertThat(count("mission_intake_receipts")+count("task_create_receipts")).isEqualTo(1);
  }

  @Test
  void membershipRevocationDuringLockWaitWins() throws Exception {
    var c=command(context()); var executor=Executors.newSingleThreadExecutor();
    try(Connection holder=jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try(var stmt=holder.prepareStatement("select id from organizations where id=? for update")) {
        stmt.setObject(1,org); stmt.executeQuery().close();
      }
      var waiting=executor.submit(()->save(UUID.randomUUID(),c));
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean observed=false;
      while(System.nanoTime()<deadline) {
        if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and query like 'select id from organizations%'",Long.class)>0) { observed=true; break; }
        Thread.sleep(20);
      }
      assertThat(observed).as("Writer reached PostgreSQL lock wait").isTrue();
      try(var stmt=holder.prepareStatement("update organization_memberships set role='MEMBER' where organization_id=? and account_id=?")) {
        stmt.setObject(1,org); stmt.setObject(2,actor); stmt.executeUpdate();
      }
      holder.commit();
      assertThatThrownBy(()->waiting.get(10,TimeUnit.SECONDS)).rootCause().hasMessage("FORBIDDEN");
      assertThat(count("mission_intake_receipts")).isZero();
    } finally { executor.shutdownNow(); }
  }
  @Test
  void migrationRetainsExistingRowsAndConstrainsExactScope() throws Exception {
    String schema="intake_migration_"+UUID.randomUUID().toString().replace("-","");
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(required("MISSION_INTAKE_TEST_JDBC_URL"),required("MISSION_INTAKE_TEST_DB_USER"),required("MISSION_INTAKE_TEST_DB_PASSWORD"));
    try {
      Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("17").load().migrate();
      try(Connection connection=source.getConnection()) {
        connection.setSchema(schema);
        var isolated=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
        isolated.update("insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Retained',?,'ACTIVE',current_timestamp,current_timestamp)",org,org.toString());
        isolated.update("insert into teams(id,organization_id,name,status,created_at,updated_at) values (?,?,'Retained','DRAFT',current_timestamp,current_timestamp)",team,org);
        isolated.update("insert into missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) values (?,?,?,'Retained','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",mission,org,team);
        isolated.update("insert into tasks(id,organization_id,team_id,mission_id,title,status) values (?,?,?,?,'Retained','DRAFT')",task,org,team,mission);
        var beforeMission=isolated.queryForMap("select * from missions where id=?",mission);
        var beforeTask=isolated.queryForMap("select * from tasks where id=?",task);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(isolated.queryForMap("select * from missions where id=?",mission)).isEqualTo(beforeMission);
        assertThat(isolated.queryForMap("select * from tasks where id=?",task)).isEqualTo(beforeTask);
        assertThat(isolated.queryForObject("select count(*) from mission_intake_receipts",Long.class)).isZero();
        isolated.update("insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Actor','unused')",actor,actor+"@example.test");
        assertThatThrownBy(()->isolated.update("insert into mission_intake_receipts(organization_id,team_id,mission_id,actor_id,idempotency_key,event_id,body_sha256,original_response,created_at) values (?,?,?,?,?,?,?,'{}',current_timestamp)",org,UUID.randomUUID(),mission,actor,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64))).isInstanceOf(DataAccessException.class);
      }
    } finally { jdbc.execute("drop schema if exists "+schema+" cascade"); }
  }

  private String attempt(Callable<?> action) throws Exception {
    try { action.call(); return "SUCCESS"; }
    catch(MissionIntakeFailure ex) { return ex.code(); }
    catch(com.agentos.mission.application.MissionCreateService.Conflict ex) { return ex.getMessage(); }
    catch(RuntimeException ex) {
      if(ex.getClass().getName().equals("com.agentos.taskediting.TaskDraftEditFailure")) return ex.getMessage();
      throw ex;
    }
  }
  private <T> List<T> race(Callable<T> first,Callable<T> second) throws Exception {
    var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
    try {
      var one=executor.submit(()->{start.await();return first.call();});
      var two=executor.submit(()->{start.await();return second.call();});
      start.countDown(); return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
    } finally { executor.shutdownNow(); }
  }
}

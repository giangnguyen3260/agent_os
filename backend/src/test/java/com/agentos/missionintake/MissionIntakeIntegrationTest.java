package com.agentos.missionintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.missionintake.MissionIntakeDtos.Command;
import com.agentos.missionintake.MissionIntakeDtos.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:mission_intake_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class MissionIntakeIntegrationTest {
  @Autowired MissionIntakeService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org, team, mission, task, actor;
  HumanIdentity human;

  public static OffsetDateTime clock() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC);
  }

  @BeforeEach
  void setup() throws Exception {
    try (Connection c = jdbc.getDataSource().getConnection()) {
      if (c.getMetaData().getDatabaseProductName().equals("H2"))
        jdbc.execute(
            "create alias if not exists clock_timestamp for"
                + " 'com.agentos.missionintake.MissionIntakeIntegrationTest.clock'");
    }
    org = UUID.randomUUID();
    team = UUID.randomUUID();
    mission = UUID.randomUUID();
    task = UUID.randomUUID();
    actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Actor','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','DRAFT',current_timestamp,current_timestamp)",
        team,
        org);
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values"
            + " (?,?,?,'Mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        mission,
        org,
        team);
    jdbc.update(
        "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
            + " (?,?,?,?,'Legacy','DRAFT')",
        task,
        org,
        team,
        mission);
    human = new HumanIdentity(actor, actor + "@example.test", "Session");
  }

  Context context() { return service.context(org,team,mission,human); }
  Command command(Context s) { return new Command(s.intakeVersion(),s.fingerprint(),!s.intakePaused()); }
  MissionIntakeDtos.Result save(UUID key,Command c) { return service.update(org,team,mission,human,key,c); }
  long count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where organization_id=?",Long.class,org); }
  String url() { return "/api/v1/organizations/"+org+"/teams/"+team+"/missions/"+mission+"/task-intake"; }
  RequestPostProcessor auth() { return authentication(new UsernamePasswordAuthenticationToken(human,null,List.of())); }

  @Test
  void persistedFlipHistoricalReplayAndOwnRecovery() throws Exception {
    Context before = context(); UUID key=UUID.randomUUID(); Command c=command(before);
    var accepted=save(key,c);
    assertThat(accepted.replay()).isFalse();
    assertThat(accepted.receipt().versionBefore()).isEqualTo("0");
    assertThat(accepted.receipt().versionAfter()).isEqualTo("1");
    assertThat(context().intakePaused()).isTrue();
    assertThat(context().fingerprint()).isNotEqualTo(before.fingerprint());
    assertThat(count("audit_events")).isEqualTo(1);
    assertThat(count("mission_intake_receipts")).isEqualTo(1);
    var audit=jdbc.queryForMap("select * from audit_events where organization_id=?",org);
    assertThat(audit.get("mission_id")).isEqualTo(mission);
    assertThat(audit.get("event_type")).isEqualTo("MISSION_TASK_INTAKE_UPDATED");
    assertThat(audit.get("payload").toString()).doesNotContain("password","credential","Goal");
    jdbc.update("update missions set status='COMPLETED',task_intake_version=99 where id=?",mission);
    assertThat(save(key,c).receipt()).isEqualTo(accepted.receipt());
    assertThat(save(key,c).replay()).isTrue();
    assertThat(service.receipt(org,team,mission,human,key)).isEqualTo(accepted.receipt());
    assertThat(service.receipt(org,team,mission,human,UUID.randomUUID())).isNull();
    assertThatThrownBy(()->save(key,new Command(c.expectedVersion(),c.expectedFingerprint(),false))).hasMessage("IDEMPOTENCY_CONFLICT");
    assertThat(count("audit_events")).isEqualTo(1);
  }

  @Test
  void staleNoopBlockedAndExhaustionHaveNoEffect() {
    var s=context();
    assertThatThrownBy(()->save(UUID.randomUUID(),new Command(s.intakeVersion(),s.fingerprint(),false))).hasMessage("MISSION_INTAKE_NO_CHANGE");
    jdbc.update("update teams set name='Changed' where id=?",team);
    assertThatThrownBy(()->save(UUID.randomUUID(),command(s))).hasMessage("MISSION_INTAKE_CONFLICT");
    jdbc.update("update missions set task_intake_version=? where id=?",Long.MAX_VALUE,mission);
    assertThat(context().blockers()).containsExactly("REVISION_EXHAUSTED");
    assertThatThrownBy(()->save(UUID.randomUUID(),command(context()))).hasMessage("MISSION_INTAKE_BLOCKED");
    assertThat(count("mission_intake_receipts")).isZero();
  }

  @Test
  void receiptFailureRollsBackMissionAndAudit() {
    var before=context();
    jdbc.execute("alter table mission_intake_receipts add constraint intake_test_reject check (original_response='REJECT')");
    try {
      assertThatThrownBy(()->save(UUID.randomUUID(),command(before))).isInstanceOf(DataAccessException.class);
      assertThat(context().intakeVersion()).isEqualTo(before.intakeVersion());
      assertThat(context().intakePaused()).isFalse();
      assertThat(count("audit_events")).isZero();
    } finally { jdbc.execute("alter table mission_intake_receipts drop constraint intake_test_reject"); }
  }

  @Test
  void malformedReceiptFailsSafelyAndAuthorityPrecedesRecovery() throws Exception {
    UUID key=UUID.randomUUID(); save(key,command(context()));
    jdbc.update("update mission_intake_receipts set original_response=? where organization_id=?","{private-secret",org);
    mvc.perform(get(url()+"/receipts/"+key).with(auth())).andExpect(status().isInternalServerError()).andExpect(jsonPath("error.code").value("MISSION_INTAKE_UNAVAILABLE"));
    jdbc.update("update organization_memberships set role='MEMBER' where organization_id=?",org);
    mvc.perform(get(url()+"/receipts/"+key).with(auth())).andExpect(status().isForbidden());
    mvc.perform(get(url()+"?bad=1").content("bad").with(auth())).andExpect(status().isForbidden());
  }

  @Test
  void strictHttpAndReceiptStatuses() throws Exception {
    var c=command(context()); String body=json.writeValueAsString(c); UUID key=UUID.randomUUID();
    mvc.perform(get(url()).with(auth())).andExpect(status().isOk()).andExpect(jsonPath("data.intakeVersion").value("0"));
    mvc.perform(get(url()).content("x").with(auth())).andExpect(status().isBadRequest());
    mvc.perform(get(url()+"?x=1").with(auth())).andExpect(status().isBadRequest());
    mvc.perform(post(url()).with(auth()).with(csrf()).contentType("application/json").header("Idempotency-Key",key.toString(),key.toString()).content(body)).andExpect(status().isBadRequest());
    mvc.perform(post(url()).with(auth()).with(csrf()).contentType("application/json").header("Idempotency-Key",key.toString()).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("data.versionAfter").value("1"));
    mvc.perform(post(url()).with(auth()).with(csrf()).contentType("application/json").header("Idempotency-Key",key.toString()).content(body)).andExpect(status().isOk());
    mvc.perform(get(url()+"/receipts/"+key).content("x").with(auth())).andExpect(status().isBadRequest());
  }

  @Test
  void taskCreateExhaustionGuardAndDraftFingerprintUseSharedVersion() {
    var drafts=new com.agentos.taskediting.TaskDraftEditService(jdbc,json);
    var before=drafts.context(org,team,mission,task,human);
    save(UUID.randomUUID(),command(context()));
    assertThat(drafts.context(org,team,mission,task,human).fingerprint()).isNotEqualTo(before.fingerprint());
    assertThat(drafts.context(org,team,mission,task,human).blockers()).contains("INTAKE_PAUSED");
    jdbc.update("update missions set task_intake_paused=false,task_intake_version=? where id=?",Long.MAX_VALUE,mission);
    assertThat(taskCreate.context(org,team,mission,human).canCreate()).isFalse();
  }
  @Test
  void unknownTeamStatusFailsClosedWithoutEffectsAndRetainsCreateReplay() {
    UUID key=UUID.randomUUID();
    var input=new com.agentos.mission.api.dto.TaskCreateRequest("New","Description","Criteria","NORMAL",null,List.of(),0L);
    var accepted=taskCreate.create(org,team,mission,human,key,input);
    var before=context();
    long tasksBefore=count("tasks"),auditBefore=count("audit_events");
    jdbc.update("update teams set status='FUTURE_STATE' where id=?",team);
    assertThat(taskCreate.context(org,team,mission,human).canCreate()).isFalse();
    assertThat(context().blockers()).containsExactly("UNKNOWN_STATE");
    var next=new com.agentos.mission.api.dto.TaskCreateRequest("Blocked","Description","Criteria","NORMAL",null,List.of(),1L);
    assertThatThrownBy(()->taskCreate.create(org,team,mission,human,UUID.randomUUID(),next)).hasMessage("TASK_SETUP_CONFLICT");
    assertThatThrownBy(()->save(UUID.randomUUID(),command(context()))).hasMessage("MISSION_INTAKE_BLOCKED");
    assertThat(taskCreate.create(org,team,mission,human,key,input).response()).isEqualTo(accepted.response());
    assertThat(count("tasks")).isEqualTo(tasksBefore);
    assertThat(count("audit_events")).isEqualTo(auditBefore);
    assertThat(count("mission_intake_receipts")).isZero();
    assertThat(context().intakeVersion()).isEqualTo(before.intakeVersion());
    assertThat(context().intakePaused()).isEqualTo(before.intakePaused());
  }

  @Test
  void fullInt64PersistenceReachesMaximumWithoutOverflow() throws Exception {
    jdbc.update("update missions set task_intake_version=? where id=?",Long.MAX_VALUE-1,mission);
    var source=context();
    assertThat(source.intakeVersion()).isEqualTo("9223372036854775806");
    UUID key=UUID.randomUUID();
    var accepted=save(key,command(source));
    assertThat(accepted.receipt().versionAfter()).isEqualTo("9223372036854775807");
    assertThat(context().blockers()).containsExactly("REVISION_EXHAUSTED");
    assertThat(save(key,command(source)).receipt()).isEqualTo(accepted.receipt());
    mvc.perform(get(url()).with(auth())).andExpect(status().isOk()).andExpect(jsonPath("data.intakeVersion").value("9223372036854775807"));
  }

  @Test
  void exhaustedTaskCreateRejectsNewButRetainsHistoricalReplay() {
    UUID key=UUID.randomUUID();
    var input=new com.agentos.mission.api.dto.TaskCreateRequest("New","Description","Criteria","NORMAL",null,List.of(),0L);
    var accepted=taskCreate.create(org,team,mission,human,key,input);
    jdbc.update("update missions set task_intake_version=? where id=?",Long.MAX_VALUE,mission);
    assertThat(taskCreate.create(org,team,mission,human,key,input).response()).isEqualTo(accepted.response());
    var next=new com.agentos.mission.api.dto.TaskCreateRequest("New","Description","Criteria","NORMAL",null,List.of(),Long.MAX_VALUE);
    assertThatThrownBy(()->taskCreate.create(org,team,mission,human,UUID.randomUUID(),next)).hasMessage("TASK_SETUP_CONFLICT");
    assertThat(context().intakeVersion()).isEqualTo(Long.toString(Long.MAX_VALUE));
  }

  @Test
  void auditFailureRollsBackReceiptAndMission() {
    var before=context();
    jdbc.execute("alter table audit_events add constraint intake_audit_reject check (event_type <> 'MISSION_TASK_INTAKE_UPDATED')");
    try {
      assertThatThrownBy(()->save(UUID.randomUUID(),command(before))).isInstanceOf(DataAccessException.class);
      assertThat(context().intakeVersion()).isEqualTo(before.intakeVersion());
      assertThat(context().intakePaused()).isFalse();
      assertThat(count("mission_intake_receipts")).isZero();
    } finally { jdbc.execute("alter table audit_events drop constraint intake_audit_reject"); }
  }

  @Test
  void transportAuthorityIsCheckedBeforeMalformedWire() throws Exception {
    mvc.perform(get(url()+"?invalid=1").content("invalid")).andExpect(status().isUnauthorized()).andExpect(jsonPath("error.code").value("UNAUTHORIZED"));
    jdbc.update("update human_accounts set enabled=false where id=?",actor);
    mvc.perform(get(url()+"?invalid=1").content("invalid").with(auth())).andExpect(status().isUnauthorized()).andExpect(jsonPath("error.code").value("UNAUTHORIZED"));
    jdbc.update("update human_accounts set enabled=true,credential_version=credential_version+1 where id=?",actor);
    mvc.perform(get(url()+"/receipts/invalid?invalid=1").with(auth())).andExpect(status().isUnauthorized()).andExpect(jsonPath("error.code").value("UNAUTHORIZED"));
    jdbc.update("update human_accounts set credential_version=0 where id=?",actor);
    jdbc.update("update organization_memberships set role='MEMBER' where organization_id=? and account_id=?",org,actor);
    mvc.perform(get(url()+"?invalid=1").content("invalid").with(auth())).andExpect(status().isForbidden()).andExpect(jsonPath("error.code").value("FORBIDDEN"));
  }

  @Test
  void exactScopeAndActorAreRequiredForRecovery() {
    UUID key=UUID.randomUUID();save(key,command(context()));
    UUID other=UUID.randomUUID();
    jdbc.update("insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Other','unused')",other,other+"@example.test");
    jdbc.update("insert into organization_memberships(account_id,organization_id,role) values (?,?,'ADMIN')",other,org);
    var otherHuman=new HumanIdentity(other,other+"@example.test","Session");
    assertThat(service.receipt(org,team,mission,otherHuman,key)).isNull();
    assertThatThrownBy(()->service.receipt(org,UUID.randomUUID(),mission,human,key)).hasMessage("NOT_FOUND");
    jdbc.update("update human_accounts set credential_version=credential_version+1 where id=?",actor);
    assertThatThrownBy(()->service.receipt(org,team,mission,human,key)).hasMessage("FORBIDDEN");
  }

  @Autowired com.agentos.mission.application.TaskCreateService taskCreate;
}

package com.agentos.teamsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.teamsettings.TeamSettingsDtos.Command;
import com.agentos.teamsettings.TeamSettingsDtos.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:team_settings_isolated;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TeamSettingsIntegrationTest {
  @Autowired TeamSettingsService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  UUID org;
  UUID team;
  UUID actor;
  HumanIdentity human;

  /** Test-only H2 alias: production always executes PostgreSQL clock_timestamp(). */
  public static OffsetDateTime clock() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC);
  }

  @BeforeEach
  void setup() throws Exception {
    try (Connection connection = jdbc.getDataSource().getConnection()) {
      if (connection.getMetaData().getDatabaseProductName().equals("H2"))
        jdbc.execute(
            "create alias if not exists clock_timestamp for"
                + " 'com.agentos.teamsettings.TeamSettingsIntegrationTest.clock'");
    }
    org = UUID.randomUUID();
    team = UUID.randomUUID();
    actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Org',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Original"
            + " actor','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Original','DRAFT',current_timestamp,current_timestamp)",
        team,
        org);
    human = new HumanIdentity(actor, actor + "@example.test", "Untrusted session name");
  }

  UUID agent(String level, String status, boolean assigned) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,'Agent','Role',?,?,"
            + " 'SUPERVISED',current_timestamp,current_timestamp)",
        id,
        org,
        assigned ? team : null,
        level,
        status);
    return id;
  }

  Command command(Context source, String name, UUID manager, List<UUID> ids) {
    return new Command(
        source.revision(),
        source.fingerprint(),
        name,
        "Purpose",
        null,
        manager,
        ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList(),
        "MANUAL",
        3,
        new BigDecimal("12.50"),
        true);
  }

  Context context() {
    return service.context(org, team, human);
  }

  RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/teams/" + team + "/settings";
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  @Test
  void persistsAtomicMembershipManagerLimitsAndSafeHistory() throws Exception {
    UUID old = agent("MANAGER", "DRAFT", true);
    UUID manager = agent("MANAGER", "ACTIVE", false);
    UUID ordinary = agent("MID", "DRAFT", false);
    jdbc.update("update teams set manager_agent_id=? where id=?", old, team);
    Context source = context();
    assertThat(source.team().purpose()).isNull();
    assertThat(source.team().concurrency()).isNull();
    assertThat(count("team_settings_ledgers")).isZero();
    var receipt =
        service
            .update(
                org,
                team,
                human,
                UUID.randomUUID(),
                command(source, "Saved", manager, List.of(ordinary)))
            .receipt();
    assertThat(receipt.before()).isEqualTo(source.team());
    assertThat(receipt.actorName()).isEqualTo("Original actor");
    assertThat(receipt.recordedAt()).matches(".*\\.[0-9]{6}Z");
    assertThat(context().team()).isEqualTo(receipt.after());
    assertThat(jdbc.queryForObject("select team_id from agents where id=?", UUID.class, old))
        .isNull();
    assertThat(jdbc.queryForObject("select status from agents where id=?", String.class, manager))
        .isEqualTo("ACTIVE");
    assertThat(count("team_settings_events")).isEqualTo(1);
    assertThat(count("team_settings_receipts")).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "select payload from audit_events where organization_id=?", String.class, org);
    assertThat(json.readTree(payload).size()).isEqualTo(2);
    assertThat(payload).doesNotContain("Saved", "Purpose", "Original actor");
  }

  @Test
  void exactHistoricalReplayPrecedesMutableEligibilityButRequiresCurrentAuthority() {
    UUID key = UUID.randomUUID();
    Command command = command(context(), "First", null, List.of());
    var first = service.update(org, team, human, key, command);
    service.update(
        org, team, human, UUID.randomUUID(), command(context(), "Second", null, List.of()));
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update("update human_accounts set display_name='Later actor name' where id=?", actor);
    assertThat(service.update(org, team, human, key, command).receipt()).isEqualTo(first.receipt());
    assertThat(service.receipt(org, team, human, key)).isEqualTo(first.receipt());
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    key,
                    new Command(
                        command.expectedRevision(),
                        command.expectedFingerprint(),
                        "Different",
                        command.purpose(),
                        null,
                        null,
                        List.of(),
                        "MANUAL",
                        3,
                        command.monthlyBudget(),
                        true)))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        actor);
    assertThatThrownBy(() -> service.update(org, team, human, key, command))
        .hasMessage("FORBIDDEN");
    assertThatThrownBy(() -> service.receipt(org, team, human, key)).hasMessage("FORBIDDEN");
  }

  @Test
  void configurationRevisionChangesFingerprintWithoutSettingsLedger() {
    UUID member = agent("MID", "DRAFT", true);
    Context source = context();
    jdbc.update(
        "update agents set configuration_revision=configuration_revision+1 where id=?", member);
    assertThat(context().revision()).isZero();
    assertThat(context().fingerprint()).isNotEqualTo(source.fingerprint());
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    UUID.randomUUID(),
                    command(source, "Stale", null, List.of(member))))
        .hasMessage("TEAM_SETTINGS_CONFLICT");
    assertThat(count("team_settings_ledgers")).isZero();
  }

  @Test
  void unchangedIneligibleManagerAndNullManagerPermitMetadataEdits() {
    UUID manager = agent("JUNIOR", "SUSPENDED", true);
    jdbc.update("update teams set manager_agent_id=?,status='ACTIVE' where id=?", manager, team);
    service.update(
        org, team, human, UUID.randomUUID(), command(context(), "Changed", manager, List.of()));
    assertThat(jdbc.queryForObject("select status from agents where id=?", String.class, manager))
        .isEqualTo("SUSPENDED");
    jdbc.update("update teams set manager_agent_id=null where id=?", team);
    service.update(
        org,
        team,
        human,
        UUID.randomUUID(),
        command(context(), "Changed again", null, List.of(manager)));
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    UUID.randomUUID(),
                    command(context(), "Bad", manager, List.of())))
        .hasMessage("TEAM_SETTINGS_BLOCKED");
  }

  @Test
  void historyBlocksRemovalButImmutableConfigurationHistoryDoesNot() {
    UUID member = agent("MID", "DRAFT", true);
    jdbc.update(
        "insert into"
            + " agent_configuration_versions(organization_id,agent_id,revision,account_id,original_response)"
            + " values (?,?,1,?,'immutable')",
        org,
        member,
        actor);
    assertThat(context().members().get(0).canRemove()).isTrue();
    UUID audit = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'AGENT',?,'HISTORY','{}',current_timestamp)",
        audit,
        org,
        member);
    assertThat(context().members().get(0).canRemove()).isFalse();
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    UUID.randomUUID(),
                    command(context(), "Blocked", null, List.of())))
        .hasMessage("TEAM_SETTINGS_BLOCKED");
    jdbc.update("delete from audit_events where id=?", audit);
    service.update(
        org, team, human, UUID.randomUUID(), command(context(), "Allowed", null, List.of()));
    assertThat(
            jdbc.queryForObject(
                "select original_response from agent_configuration_versions where agent_id=?",
                String.class,
                member))
        .isEqualTo("immutable");
  }

  @Test
  void auditFailureRollsBackManagerDetachClaimLedgerEventAndReceipt() {
    UUID old = agent("MANAGER", "DRAFT", true);
    UUID next = agent("MANAGER", "DRAFT", false);
    jdbc.update("update teams set manager_agent_id=? where id=?", old, team);
    Context before = context();
    String constraint = "team_settings_test_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "' or event_type <> 'TEAM_SETTINGS_UPDATED')");
    try {
      assertThatThrownBy(
              () ->
                  service.update(
                      org,
                      team,
                      human,
                      UUID.randomUUID(),
                      command(before, "Rollback", next, List.of())))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(context().fingerprint()).isEqualTo(before.fingerprint());
      assertThat(jdbc.queryForObject("select team_id from agents where id=?", UUID.class, next))
          .isNull();
      assertThat(count("team_settings_ledgers")).isZero();
      assertThat(count("team_settings_events")).isZero();
      assertThat(count("team_settings_receipts")).isZero();
    } finally {
      jdbc.execute("alter table audit_events drop constraint " + constraint);
    }
  }

  @Test
  void rejectsNoopAndExhaustionWithoutSideEffects() {
    service.update(
        org, team, human, UUID.randomUUID(), command(context(), "Saved", null, List.of()));
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    UUID.randomUUID(),
                    command(context(), "Saved", null, List.of())))
        .hasMessage("TEAM_SETTINGS_NO_CHANGES");
    jdbc.update(
        "update team_settings_ledgers set revision=2147483647 where organization_id=? and"
            + " team_id=?",
        org,
        team);
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    UUID.randomUUID(),
                    command(context(), "More", null, List.of())))
        .hasMessage("TEAM_SETTINGS_REVISION_EXHAUSTED");
    assertThat(count("team_settings_events")).isEqualTo(1);
  }

  @Test
  void oversizedContextIsBoundedAndBlocksWrites() {
    for (int i = 0; i < 205; i++) agent("MID", "DRAFT", true);
    Context source = context();
    assertThat(source.members()).hasSize(201);
    assertThat(source.team().agentIds()).hasSize(201);
    assertThat(source.memberTotal()).isEqualTo(205);
    assertThat(source.blockers()).containsExactly("TEAM_SIZE_UNSUPPORTED");
    assertThatThrownBy(
            () ->
                service.update(
                    org, team, human, UUID.randomUUID(), command(source, "Bad", null, List.of())))
        .hasMessage("TEAM_SETTINGS_BLOCKED");
  }

  @Test
  void candidatesAreScopedLiteralAndExcludeReportsToAndAssignments() {
    UUID candidate = agent("MID", "DRAFT", false);
    UUID assigned = agent("MID", "DRAFT", true);
    UUID reporting = agent("MID", "ACTIVE", false);
    jdbc.update("update agents set reports_to_agent_id=? where id=?", assigned, reporting);
    jdbc.update("update agents set name='literal%_!Name' where id=?", candidate);
    assertThat(service.candidates(org, team, human, 0, "%_!").items())
        .extracting(TeamSettingsDtos.Candidate::id)
        .containsExactly(candidate);
    assertThat(service.candidates(org, team, human, 0, "").items())
        .extracting(TeamSettingsDtos.Candidate::id)
        .containsExactly(candidate);
    jdbc.update("update teams set status='ACTIVE' where id=?", team);
    assertThatThrownBy(() -> service.candidates(org, team, human, 0, ""))
        .hasMessage("TEAM_SETTINGS_BLOCKED");
  }

  @Test
  void httpKeepsCsrfAndRejectsWireAndFreshUnauthorizedReads() throws Exception {
    String body = json.writeValueAsString(command(context(), "Saved", null, List.of()));
    mvc.perform(
            post(url())
                .with(auth())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(body.replace("\"concurrency\":3", "\"concurrency\":\"3\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    mvc.perform(get(url()).with(auth()).queryParam("extra", "x"))
        .andExpect(status().isBadRequest());
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        actor);
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
  }

  @Test
  void disabledAndStaleCredentialCannotReadOrReplayAndForeignScopeIsOpaque() {
    UUID key = UUID.randomUUID();
    service.update(org, team, human, key, command(context(), "Saved", null, List.of()));
    jdbc.update(
        "update human_accounts set credential_version=credential_version+1 where id=?", actor);
    assertThatThrownBy(() -> service.receipt(org, team, human, key)).hasMessage("FORBIDDEN");
    human = new HumanIdentity(actor, human.email(), "Tester", 1);
    jdbc.update("update human_accounts set enabled=false where id=?", actor);
    assertThatThrownBy(this::context).hasMessage("FORBIDDEN");
    jdbc.update("update human_accounts set enabled=true where id=?", actor);
    assertThatThrownBy(() -> service.context(org, UUID.randomUUID(), human))
        .hasMessage("NOT_FOUND");
  }

  @Test
  void canonicalHashIsKeyOrderIndependentAndBudgetEquivalent() {
    assertThat(service.hash(Map.of("b", 1, "a", new BigDecimal("1.00"))))
        .isEqualTo(service.hash(Map.of("a", new BigDecimal("1.0"), "b", 1)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSION", "TASK", "APPROVAL", "REPORTS_TO"})
  void referencesOutsideCurrentTeamBlockRemovalEvenWhenAddedAfterContext(String kind) {
    UUID member = agent("MID", "DRAFT", true);
    Context source = context();
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Other','DRAFT',current_timestamp,current_timestamp)",
        other,
        org);
    UUID mission = UUID.randomUUID();
    if (kind.equals("MISSION") || kind.equals("TASK")) {
      jdbc.update(
          "insert into"
              + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at,manager_agent_id)"
              + " values"
              + " (?,?,?,'Historical','Goal','COMPLETED','MEDIUM',current_timestamp,current_timestamp,?)",
          mission,
          org,
          other,
          kind.equals("MISSION") ? member : null);
    }
    switch (kind) {
      case "TASK" ->
          jdbc.update(
              "insert into tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id)"
                  + " values (?,?,?,?,'Historical','COMPLETED',?)",
              UUID.randomUUID(),
              org,
              other,
              mission,
              member);
      case "APPROVAL" ->
          jdbc.update(
              "insert into"
                  + " approval_requests(id,organization_id,team_id,title,action_type,risk_level,status,requested_by_agent_id)"
                  + " values (?,?,?,'Historical','REVIEW','LOW','APPROVED',?)",
              UUID.randomUUID(),
              org,
              other,
              member);
      case "REPORTS_TO" -> {
        UUID reporting = agent("JUNIOR", "DRAFT", false);
        jdbc.update(
            "update agents set team_id=?,reports_to_agent_id=? where id=?",
            other,
            member,
            reporting);
      }
      default -> {}
    }
    // Reference history is rechecked; it is deliberately absent from the source fingerprint.
    assertThat(context().fingerprint()).isEqualTo(source.fingerprint());
    assertThat(context().members().get(0).canRemove()).isFalse();
    assertThatThrownBy(
            () ->
                service.update(
                    org,
                    team,
                    human,
                    UUID.randomUUID(),
                    command(source, "Blocked", null, List.of())))
        .hasMessage("TEAM_SETTINGS_BLOCKED");
    assertThat(count("team_settings_events")).isZero();
  }

  @Test
  void receiptsAreBoundToActorAndTargetWithoutLeakingOtherReceipts() {
    UUID key = UUID.randomUUID();
    Command command = command(context(), "Saved", null, List.of());
    service.update(org, team, human, key, command);
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Other','ARCHIVED',current_timestamp,current_timestamp)",
        other,
        org);
    assertThat(service.receipt(org, other, human, key)).isNull();
    assertThatThrownBy(() -> service.update(org, other, human, key, command))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    UUID secondActor = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,'Other"
            + " actor','unused')",
        secondActor,
        secondActor + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", secondActor, org);
    assertThat(
            service.receipt(
                org, team, new HumanIdentity(secondActor, "other@example.test", "Other"), key))
        .isNull();
  }

  @Test
  void httpCanonicalBudgetTextAndMemberOrderReplayOriginalReceipt() throws Exception {
    UUID first = agent("MID", "DRAFT", false);
    UUID second = agent("JUNIOR", "ACTIVE", false);
    UUID key = UUID.randomUUID();
    var body = json.valueToTree(command(context(), "Saved", null, List.of(first, second)));
    String original = body.toString();
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(original))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.operation").value("UPDATE_TEAM_SETTINGS"))
        .andExpect(jsonPath("$.data.revision").value(1));
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) body;
    changed.put("name", "  Saved  ");
    changed.put("monthlyBudget", new BigDecimal("12.5"));
    var ids = json.createArrayNode();
    ids.add(body.get("agentIds").get(1).textValue());
    ids.add(body.get("agentIds").get(0).textValue());
    changed.set("agentIds", ids);
    mvc.perform(
            post(url())
                .with(auth())
                .with(csrf().asHeader())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(changed.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value(1))
        .andExpect(jsonPath("$.data.after.name").value("Saved"));
    assertThat(count("team_settings_events")).isEqualTo(1);
  }
}

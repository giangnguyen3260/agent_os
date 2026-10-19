package com.agentos.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.TransactionDefinition;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  UUID org, foreign, account, team, mission;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    account = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?, 'Dashboard"
            + " tester','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, org);
    team = team(org, "Primary", "ACTIVE");
    mission = mission(org, team);
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Organization',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID team(UUID organization, String name, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,?,?,current_timestamp,current_timestamp)",
        id,
        organization,
        name,
        status);
    return id;
  }

  UUID mission(UUID organization, UUID teamId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
            + " values"
            + " (?,?,?,'Mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
        id,
        organization,
        teamId);
    return id;
  }

  UUID task(UUID organization, UUID teamId, UUID missionId, String state) {
    UUID id = UUID.randomUUID();
    boolean pending = state.equals("WAITING") || state.equals("BLOCKED");
    jdbc.update(
        "insert into"
            + " tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)"
            + " values (?,?,?,?,'Task',?,?,?,?)",
        id,
        organization,
        teamId,
        missionId,
        state,
        pending ? "Needs approval" : null,
        pending ? java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z") : null,
        pending ? "Team manager" : null);
    return id;
  }

  void agent(UUID teamId, String level, String status) {
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values (?,?,?,'Agent','Role',?,?,'SUGGEST',current_timestamp,current_timestamp)",
        UUID.randomUUID(),
        org,
        teamId,
        level,
        status);
  }

  RequestPostProcessor auth() {
    return SecurityMockMvcRequestPostProcessors.authentication(
        new UsernamePasswordAuthenticationToken(
            new HumanIdentity(account, account + "@example.test", "Tester"),
            null,
            java.util.List.of()));
  }

  String url() {
    return "/api/v1/organizations/" + org + "/dashboard";
  }

  JsonNode read(String suffix) throws Exception {
    return json.readTree(
            mvc.perform(get(url() + suffix).with(auth()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  @Test
  void aggregateCountsHealthAndLiveDrilldownDoNotMultiply() throws Exception {
    agent(team, "MANAGER", "ACTIVE");
    agent(team, "MID", "ACTIVE");
    agent(team, "MID", "DEGRADED");
    for (String state :
        new String[] {
          "RUNNING",
          "RUNNING",
          "WAITING",
          "BLOCKED",
          "IN_REVIEW",
          "DRAFT",
          "READY",
          "COMPLETED",
          "FAILED",
          "CANCELLED"
        }) task(org, team, mission, state);
    UUID archived = team(org, "Archived", "ARCHIVED");
    task(org, archived, mission(org, archived), "RUNNING");
    team(org, "Setup", "ACTIVE");
    UUID normal = team(org, "Normal", "ACTIVE");
    agent(normal, "MANAGER", "ACTIVE");
    UUID other = team(foreign, "Hidden", "ACTIVE");
    task(foreign, other, mission(foreign, other), "RUNNING");
    JsonNode data = read("");
    assertThat(data.at("/snapshot/running").asInt()).isEqualTo(3);
    assertThat(data.at("/snapshot/pending").asInt()).isEqualTo(2);
    assertThat(data.at("/snapshot/review").asInt()).isEqualTo(1);
    assertThat(data.at("/snapshot/totalTeams").asInt()).isEqualTo(4);
    assertThat(data.at("/snapshot/totalAgents").asInt()).isEqualTo(4);
    assertThat(data.at("/snapshot/activeAgents").asInt()).isEqualTo(3);
    assertThat(java.time.Instant.parse(data.get("generatedAt").asText())).isNotNull();
    assertThat(data.at("/capacity/available").asBoolean()).isFalse();
    assertThat(data.at("/performance/available").asBoolean()).isFalse();
    var items = data.at("/teams/items");
    assertThat(items.get(0).get("health").asText()).isEqualTo("ARCHIVED");
    assertThat(items.get(1).get("health").asText()).isEqualTo("NORMAL");
    assertThat(items.get(2).get("health").asText()).isEqualTo("ATTENTION");
    assertThat(items.get(2).get("running").asInt()).isEqualTo(2);
    assertThat(items.get(2).get("agentCount").asInt()).isEqualTo(3);
    assertThat(items.get(3).get("health").asText()).isEqualTo("SETUP_REQUIRED");
    agent(UUID.fromString(items.get(3).get("id").asText()), "MID", "DEGRADED");
    assertThat(read("").at("/teams/items/3/health").asText()).isEqualTo("SETUP_REQUIRED");
    JsonNode pending = read("/tasks?state=PENDING&teamId=" + team);
    assertThat(pending.get("total").asInt()).isEqualTo(2);
    assertThat(pending.at("/items/0/pendingReason").asText()).isEqualTo("Needs approval");
    assertThat(pending.at("/items/0/waitingSince").asText()).isEqualTo("2026-01-01T00:00:00Z");
    assertThat(pending.at("/items/0/resolver").asText()).isEqualTo("Team manager");
    jdbc.update(
        "update tasks set status='COMPLETED',pending_reason=null,waiting_since=null,resolver=null"
            + " where organization_id=? and status in ('WAITING','BLOCKED')",
        org);
    assertThat(read("/tasks?state=PENDING").get("total").asInt()).isZero();
    assertThat(read("").at("/snapshot/pending").asInt()).isZero();
    assertThat(read("/tasks?state=REVIEW").get("total").asInt()).isEqualTo(1);
  }

  @Test
  void emptyAndValidationAndAccessLoss() throws Exception {
    assertThat(read("").at("/snapshot/running").asInt()).isZero();
    assertThat(read("/tasks?state=RUNNING").get("items")).isEmpty();
    for (String query :
        new String[] {
          "?teamPage=-1",
          "?teamPage=107374183",
          "?teamPage=2147483648",
          "?teamPage=bad",
          "/tasks?state=UNKNOWN",
          "/tasks?state=RUNNING&page=-1",
          "/tasks?state=RUNNING&page=107374183",
          "/tasks?state=RUNNING&teamId=bad",
          "/tasks"
        }) mvc.perform(get(url() + query).with(auth())).andExpect(status().isBadRequest());
    assertThat(read("?teamPage=107374182").at("/teams/items")).isEmpty();
    assertThat(read("/tasks?state=RUNNING&page=107374182").get("items")).isEmpty();
    UUID other = team(foreign, "Secret", "ACTIVE");
    for (UUID id : new UUID[] {other, UUID.randomUUID()})
      mvc.perform(get(url() + "/tasks?state=RUNNING&teamId=" + id).with(auth()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("error.message").value("Team not found"));
    mvc.perform(get(url())).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/organizations/" + foreign + "/dashboard").with(auth()))
        .andExpect(status().isForbidden());
    jdbc.update("delete from organization_memberships where account_id=?", account);
    mvc.perform(get(url()).with(auth())).andExpect(status().isForbidden());
    mvc.perform(get(url() + "/tasks?state=RUNNING").with(auth())).andExpect(status().isForbidden());
  }

  @Test
  void freshOrganizationHasZeroPersistedMetrics() throws Exception {
    UUID empty = organization();
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, empty);
    JsonNode data =
        json.readTree(
                mvc.perform(get("/api/v1/organizations/" + empty + "/dashboard").with(auth()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    for (String metric :
        new String[] {
          "running", "pending", "review", "totalTeams", "totalAgents", "activeAgents"
        }) {
      assertThat(data.get("snapshot").get(metric).asLong()).isZero();
    }
    assertThat(data.at("/teams/items")).isEmpty();
    assertThat(data.at("/teams/total").asLong()).isZero();
  }

  @Test
  void stableIndependentPagesAndTeamFilter() throws Exception {
    for (int i = 0; i < 23; i++) {
      team(org, "Same", "ACTIVE");
      task(org, team, mission, "RUNNING");
    }
    // Force timestamp ties so id is the necessary deterministic tiebreaker.
    jdbc.update(
        "update tasks set created_at = ? where organization_id=?",
        java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        org);
    JsonNode first = read("/tasks?state=RUNNING"), second = read("/tasks?state=RUNNING&page=1");
    assertThat(first.get("items")).hasSize(20);
    assertThat(second.get("items")).hasSize(3);
    assertThat(second.get("total").asInt()).isEqualTo(23);
    var ids = new java.util.HashSet<String>();
    first.get("items").forEach(n -> ids.add(n.get("id").asText()));
    second.get("items").forEach(n -> assertThat(ids.add(n.get("id").asText())).isTrue());
    assertThat(read("/tasks?state=RUNNING").get("items")).isEqualTo(first.get("items"));
    JsonNode firstTeams = read("").at("/teams/items");
    JsonNode secondTeams = read("?teamPage=1").at("/teams/items");
    var teamIds = new java.util.HashSet<String>();
    firstTeams.forEach(n -> teamIds.add(n.get("id").asText()));
    secondTeams.forEach(n -> assertThat(teamIds.add(n.get("id").asText())).isTrue());
    assertThat(read("").at("/teams/items")).isEqualTo(firstTeams);
    assertThat(read("?teamPage=1").at("/teams/items")).hasSize(4);
    assertThat(read("?teamPage=1").at("/teams/total").asInt()).isEqualTo(24);
    assertThat(read("/tasks?state=RUNNING&page=2").get("items")).isEmpty();
    assertThat(read("/tasks?state=RUNNING&teamId=" + team).get("total").asInt()).isEqualTo(23);
    UUID empty = team(org, "Empty", "ACTIVE");
    assertThat(read("/tasks?state=RUNNING&teamId=" + empty).get("total").asInt()).isZero();
  }

  @Test
  void queryBudgetStaysConstantAsTeamsAndTasksGrow() {
    assertQueryBudgets();
    for (int i = 0; i < 24; i++) {
      UUID id = team(org, "Budget " + i, "ACTIVE");
      agent(id, "MANAGER", "ACTIVE");
      UUID parent = mission(org, id);
      task(org, id, parent, "RUNNING");
      task(org, id, parent, "WAITING");
    }
    assertQueryBudgets();
  }

  private void assertQueryBudgets() {
    // Count physical statement preparation, rather than JdbcTemplate overload calls.
    // Use the application's transaction manager and database, with a connection view
    // that suppresses close so the transaction manager retains connection ownership.
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    tx.setReadOnly(true);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.executeWithoutResult(
        transaction -> {
          var connection = DataSourceUtils.getConnection(dataSource);
          var statements = new java.util.concurrent.atomic.AtomicInteger();
          var observed =
              (java.sql.Connection)
                  java.lang.reflect.Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {java.sql.Connection.class},
                      (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")
                            || method.getName().equals("createStatement")) {
                          statements.incrementAndGet();
                        }
                        try {
                          return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                          throw ex.getCause();
                        }
                      });
          var single =
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(observed, true);
          var service = new DashboardService(new JdbcTemplate(single));
          try {
            assertThat(connection.getTransactionIsolation())
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
          } catch (java.sql.SQLException ex) {
            throw new AssertionError(ex);
          }
          service.summary(org, 0);
          assertThat(statements.getAndSet(0)).as("summary SQL statements").isEqualTo(5);
          service.summary(org, 1);
          assertThat(statements.getAndSet(0)).as("paged summary SQL statements").isEqualTo(5);
          service.tasks(org, "RUNNING", null, 0);
          assertThat(statements.getAndSet(0))
              .as("unfiltered drilldown SQL statements")
              .isEqualTo(2);
          service.tasks(org, "PENDING", team, 0);
          assertThat(statements.get()).as("team-filtered drilldown SQL statements").isEqualTo(3);
        });
  }

  @Test
  void databaseRejectsCrossTenantCrossTeamAndInvalidPendingRows() {
    UUID otherTeam = team(org, "Other", "ACTIVE"), otherMission = mission(org, otherTeam);
    UUID foreignTeam = team(foreign, "Foreign", "ACTIVE"),
        foreignMission = mission(foreign, foreignTeam);
    assertThatThrownBy(() -> mission(org, foreignTeam))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> task(org, foreignTeam, foreignMission, "RUNNING"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> task(org, team, foreignMission, "RUNNING"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> task(org, team, otherMission, "RUNNING"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> task(org, team, mission, "UNKNOWN"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    UUID pending = task(org, team, mission, "WAITING");
    for (String assignment :
        new String[] {
          "pending_reason=null",
          "pending_reason=' '",
          "waiting_since=null",
          "resolver=null",
          "resolver=' '",
          "status='RUNNING'"
        })
      assertThatThrownBy(
              () -> jdbc.update("update tasks set " + assignment + " where id=?", pending))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    UUID running = task(org, team, mission, "RUNNING");
    assertThatThrownBy(() -> jdbc.update("update tasks set status='BLOCKED' where id=?", running))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}

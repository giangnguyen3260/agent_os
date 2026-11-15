package com.agentos.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.agent.api.dto.AgentDraftRequest;
import com.agentos.agent.application.AgentDraftService;
import com.agentos.auth.HumanIdentity;
import com.agentos.model.application.ModelGateway;
import com.agentos.model.application.ModelRequest;
import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties = {
      "agentos.provider-model-discovery.http-enabled=true",
      "spring.datasource.url=jdbc:h2:mem:agent_model_binding;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AgentModelBindingIntegrationTest {
  private static final String MODEL = "fixture-model-a";
  private static final String OTHER_MODEL = "fixture-model-b";
  private static final String LEGACY_MODEL = "legacy requested model";

  @Autowired JdbcTemplate jdbc;
  @Autowired AgentDraftService drafts;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ModelGateway gateway;

  @MockBean(name = "providerModelDiscoveryCoordinator")
  ProviderMetadataCoordinator discovery;

  UUID org;
  UUID agent;
  HumanIdentity human;

  record Catalogue(UUID connection, UUID check) {}

  @BeforeEach
  void setup() {
    jdbc.execute("create schema if not exists agentos_list_read");
    jdbc.execute(
        "create alias if not exists agentos_list_read.credential_available for '"
            + getClass().getName()
            + ".fixtureCredentialAvailable'");
    jdbc.update(
        """
        merge into provider_deployment_policy
          (id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)
        key (id) values (1,1,true,true,true,current_timestamp,current_timestamp)
        """);
    org = organization();
    UUID actor = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values (?,?,?,?)",
        actor,
        actor + "@example.test",
        "Tester",
        "unused");
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", actor, org);
    human = new HumanIdentity(actor, actor + "@example.test", "Tester");
    agent =
        drafts
            .create(
                org,
                human,
                UUID.randomUUID(),
                new AgentDraftRequest(
                    "Agent",
                    "Role",
                    null,
                    "",
                    "Responsibilities",
                    "Input",
                    "Output",
                    "Criteria",
                    LEGACY_MODEL,
                    null,
                    null,
                    null))
            .response()
            .id();
  }

  /** H2 stand-in for the operator-installed PostgreSQL credential evidence function. */
  public static boolean fixtureCredentialAvailable(Connection db, UUID organization, UUID target)
      throws SQLException {
    try (var query =
        db.prepareStatement(
            """
            select 1 from provider_connections c join provider_credential_versions v
              on c.organization_id=v.organization_id and c.id=v.connection_id
              and c.provider=v.provider and c.credential_revision=v.credential_revision
            where c.organization_id=? and c.id=? and v.external_key_version='fixture-present'
            """)) {
      query.setObject(1, organization);
      query.setObject(2, target);
      try (var rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  @Test
  void savesExactCatalogueModelAndReplaysHistoricalResponseAfterDisableAndClear() throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    var initial = data(mvc.perform(get(path()).with(auth())));
    assertThat(initial.path("revision").asInt(-1)).isZero();
    assertThat(initial.path("editable").asBoolean()).isTrue();
    assertThat(initial.path("binding").isNull()).isTrue();
    mvc.perform(get(path() + "/connections").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].checkId").value(catalogue.check().toString()))
        .andExpect(jsonPath("$.data.items[0].credentialRevision").value("1"))
        .andExpect(jsonPath("$.data.items[0].modelCount").value(2));
    var key = UUID.randomUUID();
    var saved = data(mvc.perform(save(key, 0, catalogue, MODEL).with(csrf())));
    assertThat(saved.path("revision").asInt(-1)).isEqualTo(1);
    assertThat(saved.path("binding").path("modelId").asText()).isEqualTo(MODEL);
    assertThat(saved.path("binding").path("displayName").asText()).isEqualTo("Fixture A");
    assertThat(saved.path("binding").path("status").asText()).isEqualTo("CURRENT");
    assertThat(data(mvc.perform(get(path()).with(auth())))).isEqualTo(saved);
    assertThat(
            jdbc.queryForObject(
                "select model from agent_draft_config where agent_id=?", String.class, agent))
        .isEqualTo(LEGACY_MODEL);
    mvc.perform(save(UUID.randomUUID(), 0, catalogue, OTHER_MODEL).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));

    jdbc.update(
        "update provider_connections set state='DISABLED',configuration_revision=2 where id=?",
        catalogue.connection());
    mvc.perform(get(path()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.binding.status").value("UNAVAILABLE"));
    mvc.perform(get(path() + "/connections").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0));
    assertThat(data(mvc.perform(save(key, 0, catalogue, MODEL).with(csrf())))).isEqualTo(saved);
    mvc.perform(save(key, 0, catalogue, OTHER_MODEL).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    var cleared = data(mvc.perform(save(UUID.randomUUID(), 1, null, null).with(csrf())));
    assertThat(cleared.path("revision").asInt(-1)).isEqualTo(2);
    assertThat(cleared.path("binding").isNull()).isTrue();
    assertThat(data(mvc.perform(save(key, 0, catalogue, MODEL).with(csrf())))).isEqualTo(saved);
    assertThat(data(mvc.perform(get(path()).with(auth())))).isEqualTo(cleared);
  }

  @Test
  void rejectsCrossTenantForeignCatalogueUnknownModelExpiredEvidenceAndFailedHealth()
      throws Exception {
    var usable = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    var foreignOrg = organization();
    jdbc.update(
        "insert into organization_memberships values (?,?,'ADMIN')", human.id(), foreignOrg);
    var foreign = catalogue(foreignOrg, OffsetDateTime.now().minusMinutes(1));
    var expired = catalogue(org, OffsetDateTime.now().minusMinutes(16));
    var unhealthy = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    jdbc.update(
        "update provider_credential_versions set external_key_version='fixture-missing' where"
            + " connection_id=?",
        unhealthy.connection());
    mvc.perform(get(path() + "/connections").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].connectionId").value(usable.connection().toString()));
    for (var invalid :
        List.of(new Catalogue(usable.connection(), foreign.check()), foreign, expired, unhealthy)) {
      mvc.perform(save(UUID.randomUUID(), 0, invalid, MODEL).with(csrf()))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"));
    }
    mvc.perform(save(UUID.randomUUID(), 0, usable, "not-observed").with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"));
    mvc.perform(get(path().replace(org.toString(), foreignOrg.toString())).with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(get(path()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value(0));
  }

  @Test
  void requiresSignedInAdminCsrfAndDraftForNewWritesIncludingClears() throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    var key = UUID.randomUUID();
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    mvc.perform(save(key, 0, catalogue, MODEL)).andExpect(status().isForbidden());
    var saved = data(mvc.perform(save(key, 0, catalogue, MODEL).with(csrf())));

    jdbc.update("update agents set status='ACTIVE' where id=?", agent);
    mvc.perform(get(path()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.editable").value(false));
    mvc.perform(save(UUID.randomUUID(), 1, null, null).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("AGENT_NOT_EDITABLE"));
    assertThat(data(mvc.perform(save(key, 0, catalogue, MODEL).with(csrf())))).isEqualTo(saved);

    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    mvc.perform(get(path()).with(auth())).andExpect(status().isForbidden());
    mvc.perform(get(path() + "/connections").with(auth())).andExpect(status().isForbidden());
    mvc.perform(save(key, 0, catalogue, MODEL).with(csrf())).andExpect(status().isForbidden());
  }

  @Test
  void newerDiscoveryAndPolicyRevisionInvalidateNewSelectionWithoutErasingExistingBinding()
      throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    data(mvc.perform(save(UUID.randomUUID(), 0, catalogue, MODEL).with(csrf())));
    data(mvc.perform(saveGrant(UUID.randomUUID(), 0, 1, true).with(csrf())));
    var pending = UUID.randomUUID();
    jdbc.update(
        """
insert into provider_access_checks
  (id,organization_id,connection_id,actor_id,actor_credential_version,connection_revision,
   credential_revision,policy_revision,accepted_at,queue_expires_at,check_kind)
values (?,?,?,?,0,1,1,1,current_timestamp,current_timestamp + interval '60' second,'MODEL_DISCOVERY')
""",
        pending,
        org,
        catalogue.connection(),
        human.id());
    mvc.perform(get(path() + "/connections").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0));
    reviewModelAuthorization("BLOCKED", "binding and discovery evidence");
    assertGatewayRequiresGrant(org, catalogue.connection(), MODEL);
    mvc.perform(save(UUID.randomUUID(), 1, catalogue, OTHER_MODEL).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"));
    mvc.perform(get(path()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.binding.modelId").value(MODEL))
        .andExpect(jsonPath("$.data.binding.status").value("CURRENT"));
    jdbc.update("update provider_deployment_policy set revision=2 where id=1");
    mvc.perform(get(path()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.binding.status").value("STALE"));
  }

  @Test
  void grantPinsTheBindingRevisionAndCannotBeSilentlyReusedForAnotherModel() throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    assertGrant(data(mvc.perform(get(grantPath()).with(auth()))), 0, 0, "ABSENT", false, false);
    mvc.perform(saveGrant(UUID.randomUUID(), 0, 0, true).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"));

    data(mvc.perform(save(UUID.randomUUID(), 0, catalogue, MODEL).with(csrf())));
    assertGrant(data(mvc.perform(get(grantPath()).with(auth()))), 0, 1, "ABSENT", true, false);
    UUID originalKey = UUID.randomUUID();
    var granted = data(mvc.perform(saveGrant(originalKey, 0, 1, true).with(csrf())));
    assertGrant(granted, 1, 1, "GRANTED", false, true);
    mvc.perform(saveGrant(UUID.randomUUID(), 1, 1, true).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("NO_CHANGE"));

    data(mvc.perform(save(UUID.randomUUID(), 1, catalogue, OTHER_MODEL).with(csrf())));
    assertGrant(data(mvc.perform(get(grantPath()).with(auth()))), 1, 2, "STALE", true, true);
    mvc.perform(saveGrant(UUID.randomUUID(), 1, 1, true).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CONFIGURATION_CONFLICT"));
    assertThat(data(mvc.perform(saveGrant(originalKey, 0, 1, true).with(csrf()))))
        .isEqualTo(granted);
    assertGrant(data(mvc.perform(get(grantPath()).with(auth()))), 1, 2, "STALE", true, true);

    var renewed = data(mvc.perform(saveGrant(UUID.randomUUID(), 1, 2, true).with(csrf())));
    assertGrant(renewed, 2, 2, "GRANTED", false, true);
    assertThat(data(mvc.perform(get(grantPath()).with(auth())))).isEqualTo(renewed);
  }

  @Test
  void requiresAdminAndCsrfWhileAllowingExplicitRevocationOutsideDraft() throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    data(mvc.perform(save(UUID.randomUUID(), 0, catalogue, MODEL).with(csrf())));
    var foreignOrg = organization();
    jdbc.update(
        "insert into organization_memberships values (?,?,'ADMIN')", human.id(), foreignOrg);
    mvc.perform(get(grantPath()).with(auth()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get(grantPath())).andExpect(status().isUnauthorized());
    mvc.perform(get(grantPath().replace(org.toString(), foreignOrg.toString())).with(auth()))
        .andExpect(status().isNotFound());
    UUID originalKey = UUID.randomUUID();
    mvc.perform(saveGrant(originalKey, 0, 1, true)).andExpect(status().isForbidden());
    var granted = data(mvc.perform(saveGrant(originalKey, 0, 1, true).with(csrf())));
    jdbc.update("update agents set status='ACTIVE' where id=?", agent);
    jdbc.update(
        "update provider_connections set state='DISABLED',configuration_revision=2 where id=?",
        catalogue.connection());
    assertGrant(data(mvc.perform(get(grantPath()).with(auth()))), 1, 1, "UNAVAILABLE", false, true);
    UUID revokeKey = UUID.randomUUID();
    var revoked = data(mvc.perform(saveGrant(revokeKey, 1, 1, false).with(csrf())));
    assertGrant(revoked, 2, 1, "REVOKED", false, false);
    mvc.perform(saveGrant(UUID.randomUUID(), 2, 1, true).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("AGENT_NOT_EDITABLE"));
    assertThat(data(mvc.perform(saveGrant(revokeKey, 1, 1, false).with(csrf()))))
        .isEqualTo(revoked);
    assertThat(data(mvc.perform(saveGrant(originalKey, 0, 1, true).with(csrf()))))
        .isEqualTo(granted);
    mvc.perform(saveGrant(revokeKey, 1, 1, true).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    assertThat(data(mvc.perform(get(grantPath()).with(auth())))).isEqualTo(revoked);

    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", human.id());
    mvc.perform(get(grantPath()).with(auth())).andExpect(status().isForbidden());
    mvc.perform(saveGrant(revokeKey, 1, 1, false).with(csrf())).andExpect(status().isForbidden());
  }

  private static void assertGrant(
      JsonNode value,
      int revision,
      int bindingRevision,
      String state,
      boolean canGrant,
      boolean canRevoke) {
    assertThat(value.size()).isEqualTo(5);
    assertThat(value.path("revision").asInt(-1)).isEqualTo(revision);
    assertThat(value.path("bindingRevision").asInt(-1)).isEqualTo(bindingRevision);
    assertThat(value.path("state").asText()).isEqualTo(state);
    assertThat(value.path("canGrant")).isEqualTo(BooleanNode.valueOf(canGrant));
    assertThat(value.path("canRevoke")).isEqualTo(BooleanNode.valueOf(canRevoke));
  }

  private Catalogue catalogue(UUID organization, OffsetDateTime completedAt) {
    UUID connection = UUID.randomUUID();
    UUID check = UUID.randomUUID();
    jdbc.update(
        """
        insert into provider_credential_versions values
        (?,?,'OPENAI',1,'fixture-locator','fixture-present','fixture-mac','v2',?,current_timestamp)
        """,
        organization,
        connection,
        "x".repeat(44));
    jdbc.update(
        """
insert into provider_connections values
(?,?,'OPENAI','OPENAI_API_KEY','Fixture connection',null,'ENABLED',1,1,current_timestamp,current_timestamp)
""",
        connection,
        organization);
    jdbc.update(
        """
insert into provider_access_checks
  (id,organization_id,connection_id,actor_id,actor_credential_version,connection_revision,
   credential_revision,policy_revision,accepted_at,queue_expires_at,worker_owner_id,
   dispatch_token,started_at,dispatch_deadline,authorized_at,completed_at,outcome,check_kind)
values (?,?,?,?,0,1,1,1,?,?,?,?,?,?,?,?,'ACCESSIBLE','MODEL_DISCOVERY')
""",
        check,
        organization,
        connection,
        human.id(),
        completedAt.minusSeconds(1),
        completedAt.plusSeconds(59),
        UUID.randomUUID(),
        UUID.randomUUID(),
        completedAt,
        completedAt.plusSeconds(30),
        completedAt,
        completedAt);
    jdbc.update(
        """
        insert into provider_model_discovery_snapshots
          (organization_id,connection_id,check_id,catalogue_state) values (?,?,?,'TRUNCATED')
        """,
        organization,
        connection,
        check);
    jdbc.update(
        """
        insert into provider_model_discovery_entries values
          (?,?,?,1,?,'Fixture A'), (?,?,?,2,?,null)
        """,
        organization,
        connection,
        check,
        MODEL,
        organization,
        connection,
        check,
        OTHER_MODEL);
    return new Catalogue(connection, check);
  }

  @Test
  void activationReviewsTheSelectedBindingAndGatewayRequiresMoreThanTheGrant() throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    var review = reviewModelAuthorization("BLOCKED", "Grant access");
    assertThat(review.path("checks").get(2).path("message").asText()).contains(LEGACY_MODEL);
    data(mvc.perform(save(UUID.randomUUID(), 0, catalogue, MODEL).with(csrf())));
    review = reviewModelAuthorization("BLOCKED", "Grant access");
    assertThat(review.path("checks").get(2).path("message").asText()).contains(MODEL);
    assertGatewayRequiresGrant(org, catalogue.connection(), MODEL);

    data(mvc.perform(saveGrant(UUID.randomUUID(), 0, 1, true).with(csrf())));
    review = reviewModelAuthorization("PASS", "valid grant");
    String agentPath = path().replace("/model-binding", "");
    mvc.perform(
            post(agentPath + "/activation")
                .with(auth())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("expectedRevision", review.path("revision").asInt())
                        .toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ACTIVATION_BLOCKED"));
    assertThatThrownBy(() -> gateway.execute(modelRequest(org, catalogue.connection(), MODEL)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runtime claim, plan and budget");
    assertGatewayRequiresGrant(organization(), catalogue.connection(), MODEL);
    assertGatewayRequiresGrant(org, catalogue(org, OffsetDateTime.now()).connection(), MODEL);
    assertGatewayRequiresGrant(org, catalogue.connection(), OTHER_MODEL);

    data(mvc.perform(saveGrant(UUID.randomUUID(), 1, 1, false).with(csrf())));
    reviewModelAuthorization("BLOCKED", "revoked");
    assertGatewayRequiresGrant(org, catalogue.connection(), MODEL);
  }

  @Test
  void activationRejectsUnavailableEvidenceAndGrantsPinnedToAnOlderBinding() throws Exception {
    var catalogue = catalogue(org, OffsetDateTime.now().minusMinutes(1));
    data(mvc.perform(save(UUID.randomUUID(), 0, catalogue, MODEL).with(csrf())));
    data(mvc.perform(saveGrant(UUID.randomUUID(), 0, 1, true).with(csrf())));

    jdbc.update("update provider_deployment_policy set openai_enabled=false where id=1");
    reviewModelAuthorization("BLOCKED", "unavailable");
    assertGatewayRequiresGrant(org, catalogue.connection(), MODEL);
    jdbc.update("update provider_deployment_policy set openai_enabled=true where id=1");

    jdbc.update(
        "update provider_connections set state='DISABLED' where id=?", catalogue.connection());
    reviewModelAuthorization("BLOCKED", "unavailable");
    jdbc.update(
        "update provider_connections set state='ENABLED' where id=?", catalogue.connection());

    data(mvc.perform(save(UUID.randomUUID(), 1, catalogue, OTHER_MODEL).with(csrf())));
    reviewModelAuthorization("BLOCKED", "binding and discovery evidence");
    assertGatewayRequiresGrant(org, catalogue.connection(), OTHER_MODEL);
    data(mvc.perform(save(UUID.randomUUID(), 2, null, null).with(csrf())));
    reviewModelAuthorization("BLOCKED", "binding and discovery evidence");
  }

  @Test
  void activationRejectsExpiredDiscoveryEvidence() throws Exception {
    var completed = OffsetDateTime.now().minusMinutes(1);
    var catalogue = catalogue(org, completed);
    data(mvc.perform(save(UUID.randomUUID(), 0, catalogue, MODEL).with(csrf())));
    data(mvc.perform(saveGrant(UUID.randomUUID(), 0, 1, true).with(csrf())));
    reviewModelAuthorization("PASS", "valid grant");

    var expired = completed.minusMinutes(16);
    jdbc.update(
        """
update provider_access_checks
set accepted_at=?,queue_expires_at=?,started_at=?,dispatch_deadline=?,authorized_at=?,completed_at=?
where id=?
""",
        expired.minusSeconds(1),
        expired.plusSeconds(59),
        expired,
        expired.plusSeconds(30),
        expired,
        expired,
        catalogue.check());
    reviewModelAuthorization("BLOCKED", "binding and discovery evidence");
    assertGatewayRequiresGrant(org, catalogue.connection(), MODEL);
  }

  private JsonNode reviewModelAuthorization(String expectedStatus, String message)
      throws Exception {
    var review =
        data(mvc.perform(get(path().replace("/model-binding", "/activation-review")).with(auth())));
    var check = review.path("checks").get(6);
    assertThat(check.path("code").asText()).isEqualTo("MODEL_AUTHORIZATION");
    assertThat(check.path("status").asText()).isEqualTo(expectedStatus);
    assertThat(check.path("message").asText()).contains(message);
    assertThat(review.path("activationAllowed").asBoolean(true)).isFalse();
    return review;
  }

  private ModelRequest modelRequest(UUID organization, UUID connection, String model) {
    return new ModelRequest(organization, null, null, null, agent, connection, model, List.of(), 1);
  }

  private void assertGatewayRequiresGrant(UUID organization, UUID connection, String model) {
    assertThatThrownBy(() -> gateway.execute(modelRequest(organization, connection, model)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("current grant");
  }

  private UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Binding test',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  private String path() {
    return "/api/v1/organizations/" + org + "/agents/" + agent + "/model-binding";
  }

  private String grantPath() {
    return "/api/v1/organizations/" + org + "/agents/" + agent + "/model-grant";
  }

  private RequestPostProcessor auth() {
    return authentication(new UsernamePasswordAuthenticationToken(human, null, List.of()));
  }

  private MockHttpServletRequestBuilder save(
      UUID key, int expectedRevision, Catalogue catalogue, String model) {
    var body = json.createObjectNode().put("expectedRevision", expectedRevision);
    if (catalogue == null) body.putNull("selection");
    else
      body.putObject("selection")
          .put("connectionId", catalogue.connection().toString())
          .put("checkId", catalogue.check().toString())
          .put("modelId", model);
    return put(path())
        .with(auth())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body.toString());
  }

  private MockHttpServletRequestBuilder saveGrant(
      UUID key, int expectedRevision, int expectedBindingRevision, boolean enabled) {
    return put(grantPath())
        .with(auth())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            json.createObjectNode()
                .put("expectedRevision", expectedRevision)
                .put("expectedBindingRevision", expectedBindingRevision)
                .put("enabled", enabled)
                .toString());
  }

  private JsonNode data(ResultActions response) throws Exception {
    return json.readTree(
            response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
        .path("data");
  }
}

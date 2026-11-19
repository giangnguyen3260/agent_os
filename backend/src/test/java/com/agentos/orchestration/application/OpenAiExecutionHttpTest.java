package com.agentos.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providermetadata.ProviderMetadataCoordinator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:openai_http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.execution.openai.enabled=true",
      "agentos.execution.openai.poll-enabled=false",
      "agentos.execution.openai.model=fixture-model",
      "agentos.execution.openai.max-output-tokens=100",
      "agentos.execution.openai.input-rate-microusd-per-million=1000000",
      "agentos.execution.openai.output-rate-microusd-per-million=1000000",
      "agentos.execution.openai.maximum-microusd=20000",
      "agentos.provider-model-discovery.http-enabled=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenAiExecutionHttpTest {
  @MockBean(name = "openAiExecutionCredentials")
  CredentialBoundary credentials;

  @MockBean(name = "providerModelDiscoveryCoordinator")
  ProviderMetadataCoordinator discovery;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired OpenAiExecutionFlow flow;

  @Test
  void enabledAssemblyAuthenticatesAndEnforcesCsrfWithoutExposingPrivateFailures()
      throws Exception {
    assertThat(flow).isNotNull();
    UUID org = UUID.randomUUID(),
        actor = UUID.randomUUID(),
        team = UUID.randomUUID(),
        mission = UUID.randomUUID(),
        task = UUID.randomUUID(),
        run = UUID.randomUUID();
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
        "insert into organization_memberships(account_id,organization_id,role) values(?,?,'ADMIN')",
        actor,
        org);
    var auth =
        authentication(
            new UsernamePasswordAuthenticationToken(
                new HumanIdentity(actor, actor + "@example.test", "Fixture"),
                null,
                java.util.List.of()));
    String taskPath =
        "/api/v1/organizations/"
            + org
            + "/teams/"
            + team
            + "/missions/"
            + mission
            + "/tasks/"
            + task
            + "/start";
    String runPath = "/api/v1/organizations/" + org + "/execution-runs/" + run + "/start";
    String body =
        "{\"expectedRevision\":\"0\",\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\",\"fingerprint\":\""
            + "a".repeat(64)
            + "\"}";
    for (String path : java.util.List.of(taskPath, runPath)) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).with(auth))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("EXECUTION_START_UNAVAILABLE"));
      mvc.perform(post(path).with(auth).contentType("application/json").content(body))
          .andExpect(status().isForbidden());
      mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(body))
          .andExpect(status().isConflict());
    }
    for (String invalid :
        java.util.List.of(
            body.replace("\"expectedRevision\":\"0\"", "\"expectedRevision\":0"),
            body.replace("\"expectedRevision\":\"0\"", "\"expectedRevision\":\"00\""),
            body.replace("{", "{\"unknown\":true,"),
            body.replace("{", "{\"expectedRevision\":\"0\","))) {
      mvc.perform(
              post(taskPath)
                  .with(auth)
                  .with(csrf())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", actor);
    for (String path : java.util.List.of(taskPath, runPath)) {
      mvc.perform(get(path).with(auth)).andExpect(status().isForbidden());
      mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(body))
          .andExpect(status().isForbidden());
    }
    assertThat(jdbc.queryForObject("select count(*) from execution_start_dispatch", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from execution_runs", Integer.class)).isZero();
  }
}

package com.agentos.orchestration.application;

import com.agentos.agent.application.AgentModelGrantService;
import com.agentos.auth.HumanIdentity;
import com.agentos.model.infrastructure.OpenAiTextTransport;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Disposable child JVM used only by native PostgreSQL recovery acceptance. */
public final class OpenAiRecoveryWorkerProcess {
  public static void main(String[] args) {
    try {
      execute();
    } catch (Throwable failure) {
      // Never print JDBC credentials, prepared requests or borrowed provider keys.
      System.err.println("Recovery worker failed: " + failure.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static void execute() throws Exception {
    var source =
        new DriverManagerDataSource(
            required("TASK_EDIT_TEST_JDBC_URL"),
            required("TASK_EDIT_TEST_DB_USER"),
            required("TASK_EDIT_TEST_DB_PASSWORD"));
    var properties = new Properties();
    properties.setProperty("currentSchema", required("OPENAI_RECOVERY_SCHEMA"));
    source.setConnectionProperties(properties);
    var jdbc = new JdbcTemplate(source);
    UUID org = UUID.fromString(required("OPENAI_RECOVERY_ORG"));
    var controls = new MissionExecutionControlStore(jdbc);
    var claims = new ExecutionClaimStore(jdbc, controls);
    var budgets = new ExecutionBudgetStore(jdbc, claims);
    var authority = new ExecutionReviewDecisionStore(jdbc, claims);
    var starts = new ExecutionStartService(jdbc, authority);
    var grants = Mockito.mock(AgentModelGrantService.class);
    Mockito.when(
            grants.hasCurrentGrant(
                ArgumentMatchers.eq(org),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.eq("fixture-model")))
        .thenReturn(true);
    var connections = Mockito.mock(ProviderConnectionService.class);
    Mockito.when(
            connections.get(
                ArgumentMatchers.any(HumanIdentity.class),
                ArgumentMatchers.eq(org),
                ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                new ProviderConnectionService.Connection(
                    org,
                    invocation.getArgument(2),
                    CredentialBoundary.Provider.OPENAI,
                    "OPENAI_API_KEY",
                    "Fixture",
                    null,
                    "ENABLED",
                    1,
                    1,
                    java.time.OffsetDateTime.now(),
                    java.time.OffsetDateTime.now(),
                    null));
    var credentials = Mockito.mock(CredentialBoundary.class);
    Mockito.doAnswer(
            invocation -> {
              CredentialBoundary.CredentialUse use = invocation.getArgument(2);
              byte[] key = "disposable-fixture-key".getBytes(StandardCharsets.US_ASCII);
              try {
                use.accept(key);
              } finally {
                java.util.Arrays.fill(key, (byte) 0);
              }
              return null;
            })
        .when(credentials)
        .use(ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.any());
    try (var timer = Executors.newSingleThreadScheduledExecutor()) {
      var ctor =
          OpenAiTextTransport.class.getDeclaredConstructor(
              URI.class,
              Clock.class,
              java.util.concurrent.ScheduledExecutorService.class,
              boolean.class);
      ctor.setAccessible(true);
      var endpoint = URI.create(required("OPENAI_RECOVERY_ENDPOINT"));
      if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()))
        throw new IllegalArgumentException("Disposable loopback provider required");
      var transport = ctor.newInstance(endpoint, Clock.systemUTC(), timer, true);
      new OpenAiExecutionFlow(
              jdbc,
              new DataSourceTransactionManager(source),
              starts,
              new ExecutionDispatchClaimService(jdbc, claims, starts),
              claims,
              budgets,
              new ExecutionModelAuthorizationStore(jdbc, claims, budgets, controls, grants, true),
              new ExecutionAttemptReconciliationStore(jdbc, claims),
              new ExecutionOutputContentStore(jdbc, claims),
              new ExecutionRunReviewStore(jdbc, claims),
              authority,
              connections,
              credentials,
              transport,
              new OpenAiExecutionFlow.Settings("fixture-model", 100, 1000000, 1000000, 20000),
              Clock.systemUTC())
          .poll();
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing disposable worker configuration");
    return value;
  }
}

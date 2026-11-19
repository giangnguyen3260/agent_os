package com.agentos.orchestration.application;

import com.agentos.model.infrastructure.OpenAiTextTransport;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.execution.openai.enabled", havingValue = "true")
class OpenAiExecutionConfiguration {
  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService openAiExecutionCancellations() {
    return new ScheduledThreadPoolExecutor(
        1, Thread.ofPlatform().daemon().name("openai-execution-deadline-", 0).factory());
  }

  @Bean
  OpenAiExecutionFlow openAiExecutionFlow(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ExecutionStartService starts,
      ExecutionDispatchClaimService dispatch,
      ExecutionClaimStore claims,
      ExecutionBudgetStore budgets,
      ExecutionModelAuthorizationStore models,
      ExecutionAttemptReconciliationStore reconciliations,
      ExecutionOutputContentStore outputs,
      ExecutionRunReviewStore reviews,
      ExecutionReviewDecisionStore authority,
      ProviderConnectionService connections,
      @Qualifier("openAiExecutionCredentials") CredentialBoundary credentials,
      @Qualifier("openAiExecutionCancellations") ScheduledExecutorService cancellations,
      Environment env) {
    if (!env.getProperty("agentos.provider-model-discovery.http-enabled", Boolean.class, false)
        || !env.getProperty("agentos.execution.lease-expiry.enabled", Boolean.class, true))
      throw new IllegalArgumentException("OpenAI runtime requires model grants and lease expiry");
    var settings =
        new OpenAiExecutionFlow.Settings(
            env.getRequiredProperty("agentos.execution.openai.model"),
            required(env, "max-output-tokens").intValueExact(),
            required(env, "input-rate-microusd-per-million").longValueExact(),
            required(env, "output-rate-microusd-per-million").longValueExact(),
            required(env, "maximum-microusd").longValueExact());
    return new OpenAiExecutionFlow(
        jdbc,
        manager,
        starts,
        dispatch,
        claims,
        budgets,
        models,
        reconciliations,
        outputs,
        reviews,
        authority,
        connections,
        credentials,
        new OpenAiTextTransport(Clock.systemUTC(), cancellations),
        settings,
        Clock.systemUTC());
  }

  @Bean
  @ConditionalOnProperty(
      name = "agentos.execution.openai.poll-enabled",
      havingValue = "true",
      matchIfMissing = true)
  Poller openAiExecutionPoller(OpenAiExecutionFlow flow) {
    return new Poller(flow);
  }

  static class Poller implements DisposableBean {
    private final ScheduledExecutorService executor;

    Poller(OpenAiExecutionFlow flow) {
      executor =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().daemon().name("openai-execution-worker-", 0).factory());
      executor.scheduleWithFixedDelay(
          () -> {
            try {
              flow.poll();
            } catch (RuntimeException failure) {
              org.slf4j.LoggerFactory.getLogger(Poller.class)
                  .warn("OpenAI worker poll unavailable; later polling will continue");
            }
          },
          1000,
          1000,
          TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
      executor.shutdownNow();
    }
  }

  private static java.math.BigDecimal required(Environment env, String key) {
    String value = env.getRequiredProperty("agentos.execution.openai." + key);
    return new java.math.BigDecimal(value);
  }
}

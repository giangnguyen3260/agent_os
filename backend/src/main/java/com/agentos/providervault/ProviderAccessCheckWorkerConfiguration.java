package com.agentos.providervault;

import com.agentos.providerchecks.ProviderAccessCheckDispatch;
import com.agentos.providerchecks.ProviderAccessCheckPoller;
import com.agentos.providerchecks.ProviderAccessCheckRunner;
import com.agentos.providerchecks.ProviderModelDiscoveryRunner;
import com.agentos.providerchecks.ProviderModelsTransport;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Runs provider checks only when the managed worker is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-access-check.worker-enabled", havingValue = "true")
public class ProviderAccessCheckWorkerConfiguration {
  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService providerAccessCheckCancellation() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("provider-access-check-timeout-", 0).factory());
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  @Bean
  Worker providerAccessCheckWorker(
      Environment environment,
      @Qualifier("providerAccessCheckCancellation") ScheduledExecutorService cancellations)
      throws Exception {
    var configured = ProviderCredentialVaultRuntime.assemble(environment);
    var runtime = configured.runtime();
    var clock = Clock.systemUTC();
    var dispatch = new ProviderAccessCheckDispatch(runtime.datasource());
    var runner =
        new ProviderAccessCheckRunner(
            dispatch,
            runtime.connections(),
            configured.credentials(),
            new ProviderModelsTransport(clock, cancellations),
            clock);
    var poller = new ProviderAccessCheckPoller(runtime.datasource(), dispatch, runner);
    return new Worker(poller::poll, null, pollDelay(environment));
  }

  @Bean
  @ConditionalOnProperty(
      name = "agentos.provider-model-discovery.worker-enabled",
      havingValue = "true")
  Worker providerModelDiscoveryWorker(
      Environment environment,
      @Qualifier("providerAccessCheckCancellation") ScheduledExecutorService cancellations)
      throws Exception {
    var configured = ProviderCredentialVaultRuntime.assemble(environment);
    var runtime = configured.runtime();
    var clock = Clock.systemUTC();
    var dispatch = ProviderAccessCheckDispatch.forDiscovery(runtime.datasource());
    var transport = new ProviderModelsTransport(clock, cancellations);
    var runner =
        new ProviderModelDiscoveryRunner(
            dispatch,
            runtime.connections(),
            configured.credentials(),
            transport::requestPage,
            clock);
    var poller = ProviderAccessCheckPoller.forDiscovery(runtime.datasource(), dispatch, runner);
    return new Worker(poller::dispatchNext, poller::expireOutstanding, pollDelay(environment));
  }

  private static long pollDelay(Environment environment) {
    long delay =
        environment.getProperty("agentos.provider-access-check.poll-delay-ms", Long.class, 1000L);
    if (delay < 100 || delay > 5000)
      throw new IllegalArgumentException("Invalid Access Check poll delay");
    return delay;
  }

  static final class Worker implements DisposableBean {
    private final ScheduledExecutorService scheduler;

    Worker(Runnable poll, Runnable expire, long delay) {
      scheduler =
          new ScheduledThreadPoolExecutor(
              expire == null ? 1 : 2,
              Thread.ofPlatform().daemon().name("provider-check-", 0).factory());
      schedule(poll, delay);
      if (expire != null) schedule(expire, delay);
    }

    private void schedule(Runnable task, long delay) {
      scheduler.scheduleWithFixedDelay(
          () -> {
            try {
              task.run();
            } catch (RuntimeException failure) {
              // Avoid request headers and JDBC parameters in scheduler logs.
              System.getLogger(Worker.class.getName())
                  .log(System.Logger.Level.WARNING, "PROVIDER_CHECK_POLL_FAILED");
            }
          },
          delay,
          delay,
          TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
      scheduler.shutdownNow();
    }
  }
}

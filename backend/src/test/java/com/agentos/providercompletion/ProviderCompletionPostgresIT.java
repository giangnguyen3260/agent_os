package com.agentos.providercompletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerreceipts.ProviderReceiptService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Native PostgreSQL is mandatory when this class is selected. */
@SpringBootTest
@ActiveProfiles("test")
public class ProviderCompletionPostgresIT extends ProviderCompletionFixture {
  static String environment(String suffix) {
    String value = System.getenv("TASK_EDIT_TEST_" + suffix);
    if (value == null
        || value.isBlank()
        || (suffix.equals("JDBC_URL") && !value.startsWith("jdbc:postgresql:")))
      throw new IllegalStateException("Explicit native PostgreSQL configuration required");
    return value;
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> environment("JDBC_URL"));
    properties.add("spring.datasource.username", () -> environment("DB_USER"));
    properties.add("spring.datasource.password", () -> environment("DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void absentSnapshotDoesNotGrantCommandFailureWhileConcurrentWriteCommits() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var factory = new ProxyFactory(receipts);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        (MethodInterceptor)
            invocation -> {
              if (!invocation.getMethod().getName().equals("findOwn")) return invocation.proceed();
              Object absent = invocation.proceed();
              assertThat(absent).isNull();
              assertThat(
                      executor
                          .submit(
                              () ->
                                  healthy.executePrepared(
                                      human, org, key, command, prepared, id, credential))
                          .get(15, TimeUnit.SECONDS)
                          .state())
                  .isEqualTo(WriteState.COMMITTED);
              assertThat(invocation.proceed()).isNull();
              return absent;
            });
    try {
      var boundary =
          new ProviderCompletionBoundary(
              manager, jdbc, (ProviderReceiptService) factory.getProxy());
      assertThat(boundary.lookupOwn(human, org, key).state()).isEqualTo(LookupState.ABSENT);
      assertThat(healthy.lookupOwn(human, org, key).state()).isEqualTo(LookupState.FOUND);
      one();
    } finally {
      executor.shutdownNow();
    }
  }
}

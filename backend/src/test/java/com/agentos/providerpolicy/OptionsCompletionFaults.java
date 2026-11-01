package com.agentos.providerpolicy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * Tests commit uncertainty after the actual manager has committed; never installed in production.
 */
@TestConfiguration
public class OptionsCompletionFaults {
  static final AtomicReference<String> FAIL_NEXT = new AtomicReference<>();

  @Bean
  static BeanPostProcessor completionFaults() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof PlatformTransactionManager)) return bean;
        var transactions = new ConcurrentHashMap<TransactionStatus, String>();
        var factory = new ProxyFactory();
        factory.setTarget(bean);
        factory.setInterfaces(PlatformTransactionManager.class);
        factory.addAdvice(
            (MethodInterceptor)
                invocation -> {
                  String method = invocation.getMethod().getName();
                  if (method.equals("getTransaction")) {
                    var status = (TransactionStatus) invocation.proceed();
                    String transaction =
                        ((TransactionDefinition) invocation.getArguments()[0]).getName();
                    if (transaction != null) transactions.put(status, transaction);
                    return status;
                  }
                  if (method.equals("commit") || method.equals("rollback")) {
                    String transaction = transactions.remove(invocation.getArguments()[0]);
                    Object result = invocation.proceed();
                    String armed = FAIL_NEXT.get();
                    if (method.equals("commit")
                        && transaction != null
                        && transaction.equals(armed)
                        && FAIL_NEXT.compareAndSet(armed, null))
                      throw new IllegalStateException("commit-sensitive-SQL-sentinel");
                    return result;
                  }
                  return invocation.proceed();
                });
        return factory.getProxy();
      }
    };
  }
}

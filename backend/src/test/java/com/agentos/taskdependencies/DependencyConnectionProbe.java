package com.agentos.taskdependencies;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Observes actual server transaction settings on the same connection as scoped graph reads. */
final class DependencyConnectionProbe {
  record Observation(String isolation, boolean readOnly) {}

  static final ThreadLocal<List<Observation>> READS = ThreadLocal.withInitial(ArrayList::new);

  static Object call(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    static BeanPostProcessor dependencyConnectionObserver() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof DataSource source)) return bean;
          return Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, args) -> {
                Object result = call(source, method, args);
                if (!(result instanceof Connection connection)
                    || !method.getName().equals("getConnection")) return result;
                return Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, operation, parameters) -> {
                      if (operation.getName().equals("prepareStatement")
                          && parameters[0] instanceof String sql
                          && sql.toLowerCase(java.util.Locale.ROOT).contains("tasks")
                          && !connection.getAutoCommit()) {
                        try (var statement = connection.createStatement();
                            var rs =
                                statement.executeQuery(
                                    "select"
                                        + " current_setting('transaction_isolation'),current_setting('transaction_read_only')")) {
                          rs.next();
                          READS
                              .get()
                              .add(new Observation(rs.getString(1), rs.getString(2).equals("on")));
                        }
                      }
                      return call(connection, operation, parameters);
                    });
              });
        }
      };
    }
  }
}

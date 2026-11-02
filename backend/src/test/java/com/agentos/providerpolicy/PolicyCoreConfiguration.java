package com.agentos.providerpolicy;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@TestConfiguration
@EnableTransactionManagement
@Import({PolicyJdbc.class, PolicyAuthority.class, PolicyService.class, AdmissionService.class})
public class PolicyCoreConfiguration {
  @Bean
  DataSource dataSource(Environment environment) {
    var source =
        new DriverManagerDataSource(
            environment.getRequiredProperty("spring.datasource.url"),
            environment.getProperty("spring.datasource.username", "sa"),
            environment.getProperty("spring.datasource.password", ""));
    if (environment.getRequiredProperty("spring.datasource.url").startsWith("jdbc:h2:")) {
      var populator = new ResourceDatabasePopulator();
      for (String name :
          new String[] {
            "V001__create_core_tables.sql",
            "V002__human_authentication.sql",
            "V003__password_recovery.sql",
            "V024__provider_options_policy.sql"
          }) populator.addScript(new ClassPathResource("db/migration/" + name));
      populator.execute(source);
    } else org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
    return source;
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource source) {
    return new JdbcTemplate(source);
  }

  @Bean
  PlatformTransactionManager transactionManager(DataSource source) {
    return new DataSourceTransactionManager(source);
  }
}

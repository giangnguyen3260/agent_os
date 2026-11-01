package com.agentos.providerpolicy;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(OptionsCompletionFaults.class)
public class ProviderOptionsPostgresIT extends ProviderOptionsHttpFixture {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> ProviderPolicyPostgresIT.environment("JDBC_URL"));
    properties.add(
        "spring.datasource.username", () -> ProviderPolicyPostgresIT.environment("DB_USER"));
    properties.add(
        "spring.datasource.password", () -> ProviderPolicyPostgresIT.environment("DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }
}

package com.agentos.providercompletion;

import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class ProviderRenameCoordinatorTest extends ProviderRenameFixture {
  private static final String DATABASE = "rename_" + UUID.randomUUID();

  @DynamicPropertySource
  static void h2(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.datasource.url",
        () ->
            "jdbc:h2:mem:"
                + DATABASE
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF"
                + " NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
  }
}

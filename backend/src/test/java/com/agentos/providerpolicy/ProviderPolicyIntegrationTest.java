package com.agentos.providerpolicy;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PolicyCoreConfiguration.class)
@TestPropertySource(
    properties =
        "spring.datasource.url=jdbc:h2:mem:provider_policy_core;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
public class ProviderPolicyIntegrationTest extends ProviderPolicyFixture {}

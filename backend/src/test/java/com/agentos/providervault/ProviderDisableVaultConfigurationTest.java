package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderDisableVaultConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ProviderDisableVaultConfiguration.class);

  @Test
  void disabledFeatureNeedsNoVaultOrWriterConfiguration() {
    context.run(
        running -> {
          assertThat(running).hasNotFailed();
          assertThat(running).doesNotHaveBean("providerDisableCoordinator");
        });
  }

  @Test
  void enabledFeatureDoesNotFallBackToRenameOrCreateConfiguration() {
    context
        .withPropertyValues(
            "agentos.provider-disable.enabled=true",
            "agentos.provider-rename.url=https://127.0.0.1:8200",
            "agentos.provider-vault.url=https://127.0.0.1:8200")
        .run(
            running ->
                assertThat(running.getStartupFailure())
                    .hasRootCauseMessage("PROVIDER_DISABLE_CONFIGURATION_UNAVAILABLE"));
  }
}

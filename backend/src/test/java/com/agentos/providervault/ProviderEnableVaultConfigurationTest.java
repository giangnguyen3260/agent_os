package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderEnableVaultConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ProviderEnableVaultConfiguration.class);

  @Test
  void enabledFeatureNeedsNoVaultOrWriterConfiguration() {
    context.run(
        running -> {
          assertThat(running).hasNotFailed();
          assertThat(running).doesNotHaveBean("providerEnableCoordinator");
        });
  }

  @Test
  void enabledFeatureDoesNotFallBackToRenameOrCreateConfiguration() {
    context
        .withPropertyValues(
            "agentos.provider-enable.enabled=true",
            "agentos.provider-rename.url=https://127.0.0.1:8200",
            "agentos.provider-vault.url=https://127.0.0.1:8200")
        .run(
            running ->
                assertThat(running.getStartupFailure())
                    .hasRootCauseMessage("PROVIDER_ENABLE_CONFIGURATION_UNAVAILABLE"));
  }
}

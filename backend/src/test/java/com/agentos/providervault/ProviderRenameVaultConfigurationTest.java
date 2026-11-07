package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providerrename.ProviderRenameCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderRenameVaultConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              ProviderRenameVaultConfiguration.class, ProviderVaultConfiguration.class);

  @Test
  void disabledFeaturesNeedNoMountedCredentialsOrDatabase() {
    context.run(
        running -> {
          assertThat(running).hasNotFailed();
          assertThat(running).doesNotHaveBean(ProviderRenameCoordinator.class);
          assertThat(running).doesNotHaveBean(ProviderCreateCoordinator.class);
        });
  }

  @Test
  void suppliedRenameCoordinatorRequiresNoCreateOrVaultSettings() {
    var supplied = Mockito.mock(ProviderRenameCoordinator.class);
    context
        .withPropertyValues("agentos.provider-rename.enabled=true")
        .withBean(ProviderRenameCoordinator.class, () -> supplied)
        .run(
            running -> {
              assertThat(running).hasNotFailed();
              assertThat(running.getBean(ProviderRenameCoordinator.class)).isSameAs(supplied);
              assertThat(running).doesNotHaveBean(ProviderCreateCoordinator.class);
              assertThat(running).doesNotHaveBean(javax.sql.DataSource.class);
            });
  }

  @Test
  void missingCommandOnlySettingsFailClosedWithoutFallingBackToCreateProperties() {
    context
        .withPropertyValues(
            "agentos.provider-rename.enabled=true",
            "agentos.provider-vault.url=https://127.0.0.1:8200",
            "agentos.provider-vault.app-token-file=/private/synthetic-do-not-read")
        .run(
            running ->
                assertThat(running.getStartupFailure())
                    .hasRootCauseMessage("PROVIDER_RENAME_CONFIGURATION_UNAVAILABLE"));
  }
}

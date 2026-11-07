package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercreation.ProviderCreateCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderVaultConfigurationTest {
  @TempDir Path directory;

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ProviderVaultConfiguration.class);

  @Test
  void disabledFeatureRequiresNoVaultConfiguration() {
    context.run(
        running -> {
          assertThat(running).hasNotFailed();
          assertThat(running).doesNotHaveBean(ProviderCreateCoordinator.class);
        });
  }

  @Test
  void explicitlySuppliedCoordinatorNeedsNoManagedVaultSettings() {
    var supplied = Mockito.mock(ProviderCreateCoordinator.class);
    context
        .withPropertyValues("agentos.provider-create.enabled=true")
        .withBean(ProviderCreateCoordinator.class, () -> supplied)
        .run(
            running -> {
              assertThat(running).hasNotFailed();
              assertThat(running.getBean(ProviderCreateCoordinator.class)).isSameAs(supplied);
            });
  }

  @Test
  void enablingManagedCreateWithoutExplicitSettingsFailsClosed() {
    context
        .withPropertyValues("agentos.provider-create.enabled=true")
        .run(
            running ->
                assertThat(running.getStartupFailure())
                    .hasRootCauseMessage("PROVIDER_VAULT_CONFIGURATION_UNAVAILABLE"));
  }

  @Test
  void mountedTokensAreBoundedFreshAndObserveFileRotation() throws Exception {
    Path token = directory.resolve("token");
    Files.writeString(token, "fixture-app-token\r\n");
    char[] first = ProviderVaultConfiguration.readToken(token);
    char[] second = ProviderVaultConfiguration.readToken(token);
    try {
      assertThat(first).containsExactly("fixture-app-token".toCharArray());
      assertThat(second).isNotSameAs(first);
      Arrays.fill(first, '\0');
      assertThat(second).containsExactly("fixture-app-token".toCharArray());
      Files.writeString(token, "fixture-rotated-token\n");
      char[] rotated = ProviderVaultConfiguration.readToken(token);
      try {
        assertThat(rotated).containsExactly("fixture-rotated-token".toCharArray());
      } finally {
        Arrays.fill(rotated, '\0');
      }
    } finally {
      Arrays.fill(first, '\0');
      Arrays.fill(second, '\0');
    }
  }

  @Test
  void invalidTokenFilesFailWithoutEchoingTheirContentOrPath() throws Exception {
    Path token = directory.resolve("private-token-path");
    for (String invalid :
        new String[] {"", "\n", "fixture token", "fixture\ntoken", "x".repeat(4097)}) {
      Files.writeString(token, invalid);
      assertThatThrownBy(() -> ProviderVaultConfiguration.readToken(token))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("PROVIDER_VAULT_CONFIGURATION_UNAVAILABLE")
          .hasNoCause();
    }
  }
}

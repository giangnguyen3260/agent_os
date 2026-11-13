package com.agentos.providervault;

import com.agentos.providerrename.ProviderRenameCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in internal Rename assembly using command HMAC material. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rename.enabled", havingValue = "true")
@ConditionalOnMissingBean(ProviderRenameCoordinator.class)
public class ProviderRenameVaultConfiguration {
  @Bean
  ProviderRenameCoordinator providerRenameCoordinator(Environment environment) {
    try {
      var configured =
          ProviderMetadataVaultRuntime.assemble(environment, "agentos.provider-rename.");
      var runtime = configured.runtime();
      return new ProviderRenameCoordinator(
          runtime.receipts(), runtime.completion(), configured.commands(), runtime.admission());
    } catch (Exception failure) {
      throw new IllegalStateException("PROVIDER_RENAME_CONFIGURATION_UNAVAILABLE");
    }
  }
}

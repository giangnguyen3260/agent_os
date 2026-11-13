package com.agentos.providervault;

import com.agentos.providermetadata.ProviderMetadataCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in internal Enable assembly using command HMAC material. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-enable.enabled", havingValue = "true")
@ConditionalOnMissingBean(name = "providerEnableCoordinator")
public class ProviderEnableVaultConfiguration {
  @Bean
  ProviderMetadataCoordinator providerEnableCoordinator(Environment environment) {
    try {
      var configured =
          ProviderMetadataVaultRuntime.assemble(environment, "agentos.provider-enable.", true);
      var runtime = configured.runtime();
      return new ProviderMetadataCoordinator(
          runtime.receipts(), runtime.completion(), configured.commands(), runtime.admission());
    } catch (Exception failure) {
      throw new IllegalStateException("PROVIDER_ENABLE_CONFIGURATION_UNAVAILABLE");
    }
  }
}

package com.agentos.providervault;

import com.agentos.providerrotation.ProviderRotateCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Managed Rotate assembly using journaled Vault storage and a dedicated database writer. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rotate.enabled", havingValue = "true")
@ConditionalOnMissingBean(ProviderRotateCoordinator.class)
public class ProviderRotateVaultConfiguration {
  @Bean
  ProviderRotateCoordinator providerRotateCoordinator(Environment environment) {
    try {
      var configured = ProviderCredentialVaultRuntime.assemble(environment);
      var runtime = configured.runtime();
      return new ProviderRotateCoordinator(
          runtime.receipts(),
          runtime.completion(),
          configured.commands(),
          configured.credentials(),
          runtime.admission());
    } catch (Exception failure) {
      throw new IllegalStateException("PROVIDER_ROTATE_CONFIGURATION_UNAVAILABLE");
    }
  }
}

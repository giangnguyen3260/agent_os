package com.agentos.providervault;

import com.agentos.providermetadata.ProviderMetadataCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Managed Access Check acceptance using the dedicated database writer and command HMAC. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-access-check.http-enabled", havingValue = "true")
@ConditionalOnMissingBean(name = "providerAccessCheckCoordinator")
public class ProviderAccessCheckVaultConfiguration {
  @Bean
  ProviderMetadataCoordinator providerAccessCheckCoordinator(Environment environment) {
    try {
      var configured = ProviderCredentialVaultRuntime.assemble(environment);
      var runtime = configured.runtime();
      return new ProviderMetadataCoordinator(
          runtime.receipts(), runtime.completion(), configured.commands(), runtime.admission());
    } catch (Exception failure) {
      throw new IllegalStateException("PROVIDER_ACCESS_CHECK_CONFIGURATION_UNAVAILABLE");
    }
  }
}

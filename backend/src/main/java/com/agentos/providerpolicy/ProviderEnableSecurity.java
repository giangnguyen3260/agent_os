package com.agentos.providerpolicy;

import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.agentos.providertransport.ProviderEnableBody;
import com.agentos.providertransport.ProviderEnableResponses;
import com.agentos.providertransport.ProviderRenameRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Opt-in Enable ingress. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-enable.http-enabled", havingValue = "true")
public class ProviderEnableSecurity {
  @Bean
  @Order(-4)
  SecurityFilterChain providerEnableChain(
      HttpSecurity http,
      PolicyAuthority authority,
      @Qualifier("providerEnableCoordinator") ProviderMetadataCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    return ProviderMetadataSecurity.build(
        http,
        authority,
        json,
        "enable",
        ProviderRenameRequest::validateEnable,
        ProviderEnableBody::read,
        coordinator::enable,
        ProviderEnableResponses::writeOutcome);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerEnableUploadTimeout() {
    return ProviderRenameHttpConfiguration.uploadTimeout(
        "PROVIDER_ENABLE_HTTP_CONFIGURATION_UNAVAILABLE");
  }
}

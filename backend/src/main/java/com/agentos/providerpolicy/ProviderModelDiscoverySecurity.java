package com.agentos.providerpolicy;

import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.agentos.providertransport.ProviderAccessCheckBody;
import com.agentos.providertransport.ProviderModelDiscoveryResponses;
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

/** Opt-in Model Discovery ingress. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-model-discovery.http-enabled", havingValue = "true")
public class ProviderModelDiscoverySecurity {
  @Bean
  @Order(-7)
  SecurityFilterChain providerModelDiscoveryChain(
      HttpSecurity http,
      PolicyAuthority authority,
      @Qualifier("providerModelDiscoveryCoordinator") ProviderMetadataCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    return ProviderMetadataSecurity.build(
        http,
        authority,
        json,
        "model-discoveries",
        ProviderRenameRequest::validateModelDiscovery,
        ProviderAccessCheckBody::readDiscovery,
        coordinator::discoverModels,
        ProviderModelDiscoveryResponses::writeOutcome);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerModelDiscoveryUploadTimeout() {
    return ProviderRenameHttpConfiguration.uploadTimeout(
        "PROVIDER_MODEL_DISCOVERY_HTTP_CONFIGURATION_UNAVAILABLE");
  }
}

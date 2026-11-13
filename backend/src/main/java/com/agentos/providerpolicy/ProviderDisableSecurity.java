package com.agentos.providerpolicy;

import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.agentos.providertransport.ProviderDisableBody;
import com.agentos.providertransport.ProviderDisableResponses;
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

/** Opt-in Disable ingress. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-disable.http-enabled", havingValue = "true")
public class ProviderDisableSecurity {
  @Bean
  @Order(-3)
  SecurityFilterChain providerDisableChain(
      HttpSecurity http,
      PolicyAuthority authority,
      @Qualifier("providerDisableCoordinator") ProviderMetadataCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    return ProviderMetadataSecurity.build(
        http,
        authority,
        json,
        "disable",
        ProviderRenameRequest::validateDisable,
        ProviderDisableBody::read,
        coordinator::disable,
        ProviderDisableResponses::writeOutcome);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerDisableUploadTimeout() {
    return ProviderRenameHttpConfiguration.uploadTimeout(
        "PROVIDER_DISABLE_HTTP_CONFIGURATION_UNAVAILABLE");
  }
}

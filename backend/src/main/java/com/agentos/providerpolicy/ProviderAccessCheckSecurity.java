package com.agentos.providerpolicy;

import com.agentos.providermetadata.ProviderMetadataCoordinator;
import com.agentos.providertransport.ProviderAccessCheckBody;
import com.agentos.providertransport.ProviderAccessCheckResponses;
import com.agentos.providertransport.ProviderRenameRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Opt-in Access Check ingress. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-access-check.http-enabled", havingValue = "true")
public class ProviderAccessCheckSecurity {
  @Bean
  @Order(-6)
  SecurityFilterChain providerAccessCheckChain(
      HttpSecurity http,
      PolicyAuthority authority,
      @org.springframework.beans.factory.annotation.Qualifier("providerAccessCheckCoordinator")
          ProviderMetadataCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    return ProviderMetadataSecurity.build(
        http,
        authority,
        json,
        "access-checks",
        ProviderRenameRequest::validateAccessCheck,
        ProviderAccessCheckBody::read,
        coordinator::accessCheck,
        ProviderAccessCheckResponses::writeOutcome);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerAccessCheckUploadTimeout() {
    return ProviderRenameHttpConfiguration.uploadTimeout(
        "PROVIDER_ACCESS_CHECK_HTTP_CONFIGURATION_UNAVAILABLE");
  }
}

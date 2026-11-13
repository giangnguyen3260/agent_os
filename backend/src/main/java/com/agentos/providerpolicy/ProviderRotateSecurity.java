package com.agentos.providerpolicy;

import com.agentos.providerrotation.ProviderRotateCoordinator;
import com.agentos.providertransport.ProviderRenameRequest;
import com.agentos.providertransport.ProviderRotateBody;
import com.agentos.providertransport.ProviderRotateResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Opt-in Rotate ingress. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rotate.http-enabled", havingValue = "true")
public class ProviderRotateSecurity {
  @Bean
  @Order(-5)
  SecurityFilterChain providerRotateChain(
      HttpSecurity http,
      PolicyAuthority authority,
      ProviderRotateCoordinator coordinator,
      ObjectMapper json)
      throws Exception {
    return ProviderMetadataSecurity.build(
        http,
        authority,
        json,
        "rotate",
        ProviderRenameRequest::validateRotate,
        ProviderRotateBody::read,
        coordinator::rotate,
        ProviderRotateResponses::writeOutcome);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerRotateUploadTimeout() {
    return ProviderRenameHttpConfiguration.uploadTimeout(
        "PROVIDER_ROTATE_HTTP_CONFIGURATION_UNAVAILABLE");
  }
}

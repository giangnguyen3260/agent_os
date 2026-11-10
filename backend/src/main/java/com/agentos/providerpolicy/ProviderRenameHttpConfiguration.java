package com.agentos.providerpolicy;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bounds idle body reads even when Rename runs without the Create/Vault configuration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rename.http-enabled", havingValue = "true")
public class ProviderRenameHttpConfiguration {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerRenameUploadTimeout() {
    return factory ->
        factory.addConnectorCustomizers(
            connector -> {
              if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol)) {
                throw new IllegalStateException("PROVIDER_RENAME_HTTP_CONFIGURATION_UNAVAILABLE");
              }
              protocol.setDisableUploadTimeout(false);
              protocol.setConnectionUploadTimeout(10_000);
            });
  }
}

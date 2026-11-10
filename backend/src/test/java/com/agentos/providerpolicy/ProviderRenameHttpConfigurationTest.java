package com.agentos.providerpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

class ProviderRenameHttpConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ProviderRenameHttpConfiguration.class);

  @Test
  void internalRenameDoesNotEnableHttpInfrastructure() {
    context
        .withPropertyValues("agentos.provider-rename.enabled=true")
        .run(running -> assertThat(running).doesNotHaveBean("providerRenameUploadTimeout"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renameHttpAloneBoundsActualTomcatIdleReadsWithoutCreateConfiguration() {
    context
        .withPropertyValues("agentos.provider-rename.http-enabled=true")
        .run(
            running -> {
              assertThat(running).hasNotFailed();
              var customize =
                  (WebServerFactoryCustomizer<TomcatServletWebServerFactory>)
                      running.getBean("providerRenameUploadTimeout");
              var factory = new TomcatServletWebServerFactory();
              customize.customize(factory);
              var connector = new Connector();
              for (var customizer : factory.getTomcatConnectorCustomizers()) {
                customizer.customize(connector);
              }
              var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
              assertThat(protocol.getDisableUploadTimeout()).isFalse();
              assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(10_000);
            });
  }
}

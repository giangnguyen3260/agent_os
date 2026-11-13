package com.agentos.providervault;

import com.agentos.providercreation.ProviderCreateAdmission;
import com.agentos.providercreation.ProviderCreateCoordinator;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit managed Create dependencies using mounted Vault credentials and a deployment CA. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-create.enabled", havingValue = "true")
@ConditionalOnMissingBean(ProviderCreateCoordinator.class)
public class ProviderVaultConfiguration {
  @Bean
  ProviderCreateCoordinator providerCreateCoordinator(Environment environment) {
    try {
      var configured = ProviderCredentialVaultRuntime.assemble(environment);
      var runtime = configured.runtime();
      return new ProviderCreateCoordinator(
          runtime.receipts(),
          runtime.completion(),
          configured.commands(),
          configured.credentials(),
          new ProviderCreateAdmission(runtime.admission()));
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> providerCreateUploadTimeout() {
    return factory ->
        factory.addConnectorCustomizers(
            connector -> {
              if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol)) {
                throw unavailable();
              }
              protocol.setDisableUploadTimeout(false);
              protocol.setConnectionUploadTimeout(10_000);
            });
  }

  static SSLSocketFactory tls(Path ca) throws Exception {
    byte[] encoded;
    try (var input = Files.newInputStream(ca)) {
      encoded = input.readNBytes(1_048_577);
    }
    if (encoded.length > 1_048_576) throw unavailable();
    var certificates =
        CertificateFactory.getInstance("X.509")
            .generateCertificates(new ByteArrayInputStream(encoded));
    if (certificates.isEmpty()) throw unavailable();
    var keys = KeyStore.getInstance(KeyStore.getDefaultType());
    keys.load(null, null);
    int index = 0;
    for (var certificate : certificates) keys.setCertificateEntry("vault-" + index++, certificate);
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keys);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    return context.getSocketFactory();
  }

  /**
   * Returns a fresh bounded token buffer so mounted token rotation is picked up on each request.
   */
  static char[] readToken(Path path) {
    byte[] encoded = null;
    char[] token = null;
    boolean returned = false;
    try {
      try (var input = Files.newInputStream(path)) {
        encoded = input.readNBytes(4099);
      }
      int length = encoded.length;
      if (length > 0 && encoded[length - 1] == '\n') {
        length--;
        if (length > 0 && encoded[length - 1] == '\r') length--;
      }
      if (length < 1 || length > 4096) throw unavailable();
      token = new char[length];
      for (int index = 0; index < length; index++) {
        if (encoded[index] < 0x21 || encoded[index] > 0x7e) throw unavailable();
        token[index] = (char) encoded[index];
      }
      returned = true;
      return token;
    } catch (Exception failure) {
      throw unavailable();
    } finally {
      if (encoded != null) Arrays.fill(encoded, (byte) 0);
      if (!returned && token != null) Arrays.fill(token, '\0');
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("PROVIDER_VAULT_CONFIGURATION_UNAVAILABLE");
  }
}

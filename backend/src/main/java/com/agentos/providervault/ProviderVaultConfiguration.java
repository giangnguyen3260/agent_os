package com.agentos.providervault;

import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercreation.ProviderCreateAdmission;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerpreparation.ProviderPreparationJournal;
import com.agentos.providerreceipts.ProviderReceiptService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Explicit managed Create dependencies using mounted Vault credentials and a deployment CA. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-create.enabled", havingValue = "true")
@ConditionalOnMissingBean(ProviderCreateCoordinator.class)
public class ProviderVaultConfiguration {
  private static final String PREFIX = "agentos.provider-vault.";

  @Bean
  ProviderCreateCoordinator providerCreateCoordinator(Environment environment) {
    try {
      URI origin = URI.create(required(environment, "url"));
      String mount = required(environment, "mount");
      Path ca = file(environment, "ca-file");
      Path appToken = file(environment, "app-token-file");
      Path hmacToken = file(environment, "hmac-token-file");
      if (Files.isSameFile(appToken, hmacToken)) throw unavailable();
      int credentialVersion = version(environment, "credential-key-version");
      int commandVersion = version(environment, "command-key-version");
      SSLSocketFactory tls = tls(ca);
      Supplier<char[]> appTokens = () -> readToken(appToken);
      Supplier<char[]> hmacTokens = () -> readToken(hmacToken);
      validateToken(appTokens);
      validateToken(hmacTokens);
      var credentialKeys =
          new VaultHmacKeys(
              origin,
              mount,
              hmacTokens,
              VaultHmacKeys.Purpose.CREDENTIAL_INTEGRITY,
              credentialVersion,
              tls);
      var commandKeys =
          new VaultHmacKeys(
              origin,
              mount,
              hmacTokens,
              VaultHmacKeys.Purpose.COMMAND_RECEIPTS,
              commandVersion,
              tls);
      var vault =
          new VaultCredentialSecrets(
              origin,
              mount,
              appTokens,
              (scope, reference) -> {
                // The journal remains pinned for explicit operator reconciliation.
                throw new CredentialBoundary.Failure();
              },
              tls);
      String writerUrl = required(environment, "writer-jdbc-url");
      if (!writerUrl.startsWith("jdbc:postgresql://")) throw unavailable();
      var datasource = new DriverManagerDataSource();
      datasource.setUrl(writerUrl);
      datasource.setUsername(required(environment, "writer-username"));
      char[] password = readToken(file(environment, "writer-password-file"));
      try {
        datasource.setPassword(new String(password));
      } finally {
        Arrays.fill(password, '\0');
      }
      var jdbc = new JdbcTemplate(datasource);
      var manager = new DataSourceTransactionManager(datasource);
      var attributes = new AnnotationTransactionAttributeSource();
      var connections = new ProxyFactory(new ProviderConnectionService(jdbc));
      connections.setProxyTargetClass(true);
      connections.addAdvice(new TransactionInterceptor(manager, attributes));
      var receipts =
          new ProxyFactory(
              new ProviderReceiptService(jdbc, (ProviderConnectionService) connections.getProxy()));
      receipts.setProxyTargetClass(true);
      receipts.addAdvice(new TransactionInterceptor(manager, attributes));
      var receiptService = (ProviderReceiptService) receipts.getProxy();
      var journal = new ProviderPreparationJournal(datasource);
      return new ProviderCreateCoordinator(
          receiptService,
          new ProviderCompletionBoundary(manager, jdbc, receiptService),
          new CommandBoundary(commandKeys),
          new CredentialBoundary(credentialKeys, new JournaledVaultSecrets(journal, vault)),
          new ProviderCreateAdmission(datasource));
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

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(PREFIX + property);
    if (value == null || value.isBlank() || !value.equals(value.strip())) throw unavailable();
    return value;
  }

  private static Path file(Environment environment, String property) {
    Path path = Path.of(required(environment, property));
    if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw unavailable();
    }
    return path;
  }

  private static int version(Environment environment, String property) {
    String value = required(environment, property);
    if (!value.matches("[1-9][0-9]{0,9}")) throw unavailable();
    return Integer.parseInt(value);
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

  private static void validateToken(Supplier<char[]> tokens) {
    char[] token = tokens.get();
    Arrays.fill(token, '\0');
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

package com.agentos.providervault;

import com.agentos.providercommands.CommandBoundary;
import com.agentos.providerrename.ProviderRenameCoordinator;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in internal Rename assembly requiring command HMAC material, never provider credentials. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentos.provider-rename.enabled", havingValue = "true")
@ConditionalOnMissingBean(ProviderRenameCoordinator.class)
public class ProviderRenameVaultConfiguration {
  private static final String PREFIX = "agentos.provider-rename.";

  @Bean
  ProviderRenameCoordinator providerRenameCoordinator(Environment environment) {
    try {
      URI origin = URI.create(required(environment, "url"));
      String mount = required(environment, "mount");
      var tls = ProviderVaultConfiguration.tls(file(environment, "ca-file"));
      Path commandToken = file(environment, "command-token-file");
      Supplier<char[]> tokens = () -> ProviderVaultConfiguration.readToken(commandToken);
      char[] validation = tokens.get();
      Arrays.fill(validation, '\0');
      String version = required(environment, "command-key-version");
      if (!version.matches("[1-9][0-9]{0,9}")) throw unavailable();
      var keys =
          new VaultHmacKeys(
              origin,
              mount,
              tokens,
              VaultHmacKeys.Purpose.COMMAND_RECEIPTS,
              Integer.parseInt(version),
              tls);
      String url = required(environment, "writer-jdbc-url");
      if (!url.startsWith("jdbc:postgresql://")) throw unavailable();
      var writer = new DriverManagerDataSource();
      writer.setUrl(url);
      writer.setUsername(required(environment, "writer-username"));
      char[] password =
          ProviderVaultConfiguration.readToken(file(environment, "writer-password-file"));
      try {
        writer.setPassword(new String(password));
      } finally {
        Arrays.fill(password, '\0');
      }
      var runtime = ProviderCommandRuntime.assemble(writer);
      return new ProviderRenameCoordinator(
          runtime.receipts(), runtime.completion(), new CommandBoundary(keys), runtime.admission());
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private static String required(Environment environment, String name) {
    String value = environment.getProperty(PREFIX + name);
    if (value == null || value.isBlank() || !value.equals(value.strip())) throw unavailable();
    return value;
  }

  private static Path file(Environment environment, String name) {
    Path value = Path.of(required(environment, name));
    if (!value.isAbsolute() || !Files.isRegularFile(value) || !Files.isReadable(value))
      throw unavailable();
    return value;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("PROVIDER_RENAME_CONFIGURATION_UNAVAILABLE");
  }
}

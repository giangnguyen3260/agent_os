package com.agentos.providervault;

import com.agentos.providercommands.CommandBoundary;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Assembles a writer and command HMAC boundary for a metadata operation. */
record ProviderMetadataVaultRuntime(ProviderCommandRuntime runtime, CommandBoundary commands) {
  static ProviderMetadataVaultRuntime assemble(Environment environment, String prefix)
      throws Exception {
    URI origin = URI.create(required(environment, prefix, "url"));
    String mount = required(environment, prefix, "mount");
    var tls = ProviderVaultConfiguration.tls(file(environment, prefix, "ca-file"));
    Path commandToken = file(environment, prefix, "command-token-file");
    Supplier<char[]> tokens = () -> ProviderVaultConfiguration.readToken(commandToken);
    char[] validation = tokens.get();
    Arrays.fill(validation, '\0');
    String version = required(environment, prefix, "command-key-version");
    if (!version.matches("[1-9][0-9]{0,9}")) throw unavailable();
    var keys =
        new VaultHmacKeys(
            origin,
            mount,
            tokens,
            VaultHmacKeys.Purpose.COMMAND_RECEIPTS,
            Integer.parseInt(version),
            tls);
    String url = required(environment, prefix, "writer-jdbc-url");
    if (!url.startsWith("jdbc:postgresql://")) throw unavailable();
    var writer = new DriverManagerDataSource();
    writer.setUrl(url);
    writer.setUsername(required(environment, prefix, "writer-username"));
    char[] password =
        ProviderVaultConfiguration.readToken(file(environment, prefix, "writer-password-file"));
    try {
      writer.setPassword(new String(password));
    } finally {
      Arrays.fill(password, '\0');
    }
    return new ProviderMetadataVaultRuntime(
        ProviderCommandRuntime.assemble(writer), new CommandBoundary(keys));
  }

  private static String required(Environment environment, String prefix, String name) {
    String value = environment.getProperty(prefix + name);
    if (value == null || value.isBlank() || !value.equals(value.strip())) throw unavailable();
    return value;
  }

  private static Path file(Environment environment, String prefix, String name) {
    Path value = Path.of(required(environment, prefix, name));
    if (!value.isAbsolute() || !Files.isRegularFile(value) || !Files.isReadable(value))
      throw unavailable();
    return value;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("PROVIDER_METADATA_CONFIGURATION_UNAVAILABLE");
  }
}

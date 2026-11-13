package com.agentos.providervault;

import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerpreparation.ProviderPreparationJournal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared managed writer and journaled secret storage for credential-bearing commands. */
record ProviderCredentialVaultRuntime(
    ProviderCommandRuntime runtime, CommandBoundary commands, CredentialBoundary credentials) {
  private static final String PREFIX = "agentos.provider-vault.";

  static ProviderCredentialVaultRuntime assemble(Environment environment) throws Exception {
    URI origin = URI.create(required(environment, "url"));
    String mount = required(environment, "mount");
    Path ca = file(environment, "ca-file");
    Path appToken = file(environment, "app-token-file");
    Path hmacToken = file(environment, "hmac-token-file");
    if (Files.isSameFile(appToken, hmacToken)) throw unavailable();
    int credentialVersion = version(environment, "credential-key-version");
    int commandVersion = version(environment, "command-key-version");
    SSLSocketFactory tls = ProviderVaultConfiguration.tls(ca);
    Supplier<char[]> appTokens = () -> ProviderVaultConfiguration.readToken(appToken);
    Supplier<char[]> hmacTokens = () -> ProviderVaultConfiguration.readToken(hmacToken);
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
            origin, mount, hmacTokens, VaultHmacKeys.Purpose.COMMAND_RECEIPTS, commandVersion, tls);
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
    char[] password =
        ProviderVaultConfiguration.readToken(file(environment, "writer-password-file"));
    try {
      datasource.setPassword(new String(password));
    } finally {
      Arrays.fill(password, '\0');
    }
    return new ProviderCredentialVaultRuntime(
        ProviderCommandRuntime.assemble(datasource),
        new CommandBoundary(commandKeys),
        new CredentialBoundary(
            credentialKeys,
            new JournaledVaultSecrets(new ProviderPreparationJournal(datasource), vault)));
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

  private static void validateToken(Supplier<char[]> tokens) {
    char[] token = tokens.get();
    Arrays.fill(token, '\0');
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("PROVIDER_VAULT_CONFIGURATION_UNAVAILABLE");
  }
}

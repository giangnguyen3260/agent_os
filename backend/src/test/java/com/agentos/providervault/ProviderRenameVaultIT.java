package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerrename.ProviderRenameCoordinator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Managed Rename with real PostgreSQL/TLS command HMAC and no credential-store configuration. */
@SpringBootTest(
    properties = {
      "agentos.provider-rename.enabled=true",
      "agentos.provider-create.enabled=false",
      "agentos.provider-list.enabled=false"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AGENTOS_TEST_VAULT_URL", matches = "https://.*")
class ProviderRenameVaultIT {
  private static final String READER = "vault_rename_web";
  private static final String WRITER = "vault_rename_writer";
  private static final String READER_PASSWORD = UUID.randomUUID().toString();
  private static Path writerPasswordFile;
  private static Path commandTokenFile;

  private final JdbcTemplate admin = new JdbcTemplate(administrator());
  @Autowired ProviderRenameCoordinator coordinator;
  @Autowired JdbcTemplate reader;
  @Autowired Environment environment;
  @Autowired ApplicationContext context;

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry properties) {
    provisionPrincipals();
    properties.add("spring.flyway.enabled", () -> "false");
    properties.add("spring.datasource.url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("spring.datasource.username", () -> READER);
    properties.add("spring.datasource.password", () -> READER_PASSWORD);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("agentos.provider-rename.url", () -> required("AGENTOS_TEST_VAULT_URL"));
    properties.add("agentos.provider-rename.mount", () -> "agentos");
    properties.add("agentos.provider-rename.ca-file", () -> required("AGENTOS_TEST_VAULT_CA"));
    properties.add("agentos.provider-rename.command-token-file", () -> commandTokenFile.toString());
    properties.add("agentos.provider-rename.command-key-version", () -> "1");
    properties.add(
        "agentos.provider-rename.writer-jdbc-url", () -> required("AGENTOS_TEST_JOURNAL_URL"));
    properties.add("agentos.provider-rename.writer-username", () -> WRITER);
    properties.add(
        "agentos.provider-rename.writer-password-file", () -> writerPasswordFile.toString());
  }

  @Test
  void managedRenameUsesOnlyCommandHmacAndItsDedicatedMetadataWriter() throws Exception {
    assertThat(context.getBeansOfType(ProviderCreateCoordinator.class)).isEmpty();
    for (String property :
        new String[] {"url", "app-token-file", "hmac-token-file", "credential-key-version"}) {
      assertThat(environment.getProperty("agentos.provider-vault." + property)).isNull();
    }
    assertThat(reader.queryForObject("select current_user", String.class)).isEqualTo(READER);
    assertThat(privilege(READER, "SELECT")).isFalse();
    assertThat(privilege(WRITER, "SELECT")).isTrue();
    for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"}) {
      assertThat(privilege(WRITER, operation)).isFalse();
    }

    UUID org = UUID.randomUUID(), actor = UUID.randomUUID(), connection = UUID.randomUUID();
    admin.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at)"
            + " values (?,'Rename managed',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    admin.update(
        "insert into human_accounts(id,email,display_name,password_hash)"
            + " values (?,?,'Rename managed','unused')",
        actor,
        actor + "@example.test");
    admin.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    var human = new HumanIdentity(actor, actor + "@example.test", "Rename managed", 0);
    var seed = ProviderCommandRuntime.assemble(administrator());
    String digest = Base64.getEncoder().encodeToString(new byte[32]);
    var prepared =
        new CommandBoundary.Metadata(CommandBoundary.DIGEST_VERSION, "fixture-v1", digest);
    var scope =
        new CredentialBoundary.Scope(org, connection, CredentialBoundary.Provider.OPENAI, 1);
    var credential =
        new CredentialBoundary.Metadata(
            scope,
            "fixture-v1",
            new CredentialBoundary.Reference(scope, "not-a-live-credential-reference", "1"),
            digest);
    assertThat(
            seed.completion()
                .executePrepared(
                    human,
                    org,
                    UUID.randomUUID(),
                    new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Before", null),
                    prepared,
                    connection,
                    credential)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    assertThat(
            seed.completion()
                .executePrepared(
                    human,
                    org,
                    UUID.randomUUID(),
                    new RevisionCommand(Operation.DISABLE, connection, 1),
                    prepared,
                    null,
                    null)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    byte[] retainedCredential = fingerprint(org, "provider_credential_versions");
    Map<String, Object> policy =
        admin.queryForMap(
            "select openai_enabled,anthropic_enabled,gemini_enabled from"
                + " provider_deployment_policy where id=1");
    try {
      admin.update(
          "update provider_deployment_policy set openai_enabled=false,anthropic_enabled=true,"
              + " revision=revision+1,updated_at=clock_timestamp() where id=1");
      UUID key = UUID.randomUUID();
      var command = new Rename(connection, 2, " Managed renamed ");
      var first = coordinator.rename(human, org, key, command);
      assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(first.result().replayed()).isFalse();
      var receipt = first.result().receipt();
      assertThat(receipt.operation()).isEqualTo(Operation.RENAME);
      assertThat(receipt.before().revision()).isEqualTo(2);
      assertThat(receipt.after().revision()).isEqualTo(3);
      assertThat(receipt.after().name()).isEqualTo("Managed renamed");
      assertThat(receipt.after().state()).isEqualTo("DISABLED");
      assertThat(receipt.after().credentialRevision()).isEqualTo(1);
      assertThat(receipt.actorId()).isEqualTo(actor);
      assertThat(
              admin.queryForObject(
                  "select count(*) from provider_command_receipts where organization_id=? and"
                      + " actor_id=? and idempotency_key=? and operation='RENAME' and"
                      + " connection_id=? and key_version='1'",
                  Long.class,
                  org,
                  actor,
                  key,
                  connection))
          .isEqualTo(1L);
      var later =
          coordinator.rename(
              human, org, UUID.randomUUID(), new Rename(connection, 3, "Later name"));
      assertThat(later.state()).isEqualTo(WriteState.COMMITTED);
      String quota = samples(org, actor);
      var replay = coordinator.rename(human, org, key, command);
      assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(replay.result().replayed()).isTrue();
      assertThat(replay.result().receipt()).isEqualTo(receipt);
      assertThat(samples(org, actor)).isEqualTo(quota);
      assertThat(
              admin.queryForObject(
                  "select name from provider_connections where organization_id=? and id=?",
                  String.class,
                  org,
                  connection))
          .isEqualTo("Later name");
      assertThat(
              Arrays.equals(retainedCredential, fingerprint(org, "provider_credential_versions")))
          .isTrue();
      assertThat(
              admin.queryForObject(
                  "select count(*) from provider_secret_preparations where organization_id=?",
                  Long.class,
                  org))
          .isZero();

      byte[] beforeOutage = businessFingerprint(org);
      byte[] token = Files.readAllBytes(commandTokenFile);
      try {
        Files.delete(commandTokenFile);
        var unavailable =
            coordinator.rename(
                human, org, UUID.randomUUID(), new Rename(connection, 4, "No write"));
        assertThat(unavailable.state()).isEqualTo(WriteState.UNAVAILABLE);
        assertThat(unavailable.result()).isNull();
        assertThat(unavailable.failure().code()).isEqualTo(Code.COMMAND_UNAVAILABLE);
        assertThat(Arrays.equals(beforeOutage, businessFingerprint(org))).isTrue();
        assertThat(samples(org, actor).split(",")).hasSize(3);
      } finally {
        try {
          if (!Files.exists(commandTokenFile)) {
            Files.createFile(
                commandTokenFile,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
          }
          Files.write(commandTokenFile, token);
        } finally {
          Arrays.fill(token, (byte) 0);
        }
      }
      var recovered = coordinator.rename(human, org, key, command);
      assertThat(recovered.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(recovered.result().replayed()).isTrue();
      assertThat(recovered.result().receipt()).isEqualTo(receipt);
      assertThat(Arrays.equals(beforeOutage, businessFingerprint(org))).isTrue();
    } finally {
      admin.update(
          "update provider_deployment_policy set openai_enabled=?,anthropic_enabled=?,"
              + "gemini_enabled=?,revision=revision+1,updated_at=clock_timestamp() where id=1",
          policy.get("openai_enabled"),
          policy.get("anthropic_enabled"),
          policy.get("gemini_enabled"));
    }
  }

  private boolean privilege(String role, String operation) {
    return Boolean.TRUE.equals(
        admin.queryForObject(
            "select has_table_privilege(?, 'public.provider_credential_versions', ?)",
            Boolean.class,
            role,
            operation));
  }

  private String samples(UUID org, UUID actor) {
    return admin.queryForObject(
        "select recent_requests from provider_write_admission where organization_id=? and"
            + " actor_id=?",
        String.class,
        org,
        actor);
  }

  private byte[] businessFingerprint(UUID org) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    for (String table :
        new String[] {
          "provider_connections",
          "provider_credential_versions",
          "provider_connection_events",
          "provider_command_receipts",
          "provider_secret_preparations"
        }) {
      digest.update(fingerprint(org, table));
    }
    return digest.digest();
  }

  private byte[] fingerprint(UUID org, String table) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    for (String row :
        admin.queryForList(
            "select to_jsonb(t)::text from " + table + " t where organization_id=? order by 1",
            String.class,
            org)) {
      digest.update(row.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    return digest.digest();
  }

  private static DataSource administrator() {
    var datasource = new DriverManagerDataSource();
    datasource.setUrl(required("AGENTOS_TEST_JOURNAL_URL"));
    datasource.setUsername("vault_journal_test");
    try {
      datasource.setPassword(
          Files.readString(Path.of(required("AGENTOS_TEST_PG_PASSWORD_FILE"))).strip());
    } catch (Exception failure) {
      throw new IllegalStateException("Disposable Rename administrator unavailable");
    }
    return datasource;
  }

  private static void provisionPrincipals() {
    try (var connection = administrator().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "create role "
              + READER
              + " login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit"
              + " password '"
              + READER_PASSWORD
              + "'");
      String password = UUID.randomUUID().toString();
      writerPasswordFile = privateFile("rename-writer-");
      Files.writeString(writerPasswordFile, password);
      commandTokenFile = privateFile("rename-command-");
      byte[] commandToken =
          Files.readAllBytes(Path.of(required("AGENTOS_TEST_RENAME_COMMAND_TOKEN_FILE")));
      try {
        Files.write(commandTokenFile, commandToken);
      } finally {
        Arrays.fill(commandToken, (byte) 0);
      }
      statement.execute(
          "create role "
              + WRITER
              + " login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit"
              + " password '"
              + password
              + "'");
      statement.execute("grant usage on schema public to " + READER + "," + WRITER);
      statement.execute(
          "grant select on public.organizations,public.human_accounts,"
              + "public.organization_memberships,public.provider_deployment_policy,"
              + "public.provider_connections,public.provider_credential_versions,"
              + "public.provider_connection_events,public.provider_command_receipts,"
              + "public.provider_write_admission to "
              + WRITER);
      statement.execute(
          "grant update(id) on public.organizations,public.human_accounts to " + WRITER);
      statement.execute("grant update(account_id) on public.organization_memberships to " + WRITER);
      statement.execute("grant update(id) on public.provider_deployment_policy to " + WRITER);
      statement.execute("grant update on public.provider_connections to " + WRITER);
      statement.execute(
          "grant insert on public.provider_connection_events,public.provider_command_receipts,"
              + "public.provider_write_admission to "
              + WRITER);
      statement.execute("grant update on public.provider_write_admission to " + WRITER);
    } catch (Exception failure) {
      throw new IllegalStateException("Disposable Rename principal provisioning failed");
    }
  }

  private static Path privateFile(String prefix) throws Exception {
    Path file =
        Files.createTempFile(
            prefix,
            ".token",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    file.toFile().deleteOnExit();
    return file;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Disposable Rename fixture unavailable");
    return value;
  }
}

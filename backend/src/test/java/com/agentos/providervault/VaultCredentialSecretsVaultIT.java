package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercreation.ProviderCreateAdmission;
import com.agentos.providercreation.ProviderCreateCoordinator;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.agentos.providerpreparation.ProviderPreparationJournal;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providertransport.ProviderCreateBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Explicit real Vault integration, invoked only by the disposable Vault CI harness. */
@EnabledIfEnvironmentVariable(named = "AGENTOS_TEST_VAULT_URL", matches = "https://.*")
class VaultCredentialSecretsVaultIT {
  private static final String CREATE_SENTINEL = "coordinator-vault-integration-synthetic";

  @Test
  void realVaultScopedVersionedRoundTripAndRestartReadback() throws Exception {
    var keys = KeyStore.getInstance(KeyStore.getDefaultType());
    keys.load(null, null);
    try (var input = Files.newInputStream(Path.of(required("AGENTOS_TEST_VAULT_CA")))) {
      keys.setCertificateEntry(
          "vault", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keys);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trust.getTrustManagers(), null);
    Path token = Path.of(required("AGENTOS_TEST_VAULT_TOKEN_FILE"));
    var vaultRequests = new AtomicInteger();
    var adapter =
        new VaultCredentialSecrets(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            () -> {
              vaultRequests.incrementAndGet();
              try {
                return Files.readString(token, StandardCharsets.UTF_8).strip().toCharArray();
              } catch (Exception failure) {
                throw new Failure();
              }
            },
            (scope, reference) -> {
              throw new Failure();
            },
            tls.getSocketFactory());
    Path hmacToken = Path.of(required("AGENTOS_TEST_HMAC_TOKEN_FILE"));
    java.util.function.Supplier<char[]> hmacTokens =
        () -> {
          try {
            return Files.readString(hmacToken, StandardCharsets.UTF_8).strip().toCharArray();
          } catch (Exception failure) {
            throw new Failure();
          }
        };
    var credentialKeys =
        new VaultHmacKeys(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            hmacTokens,
            VaultHmacKeys.Purpose.CREDENTIAL_INTEGRITY,
            2,
            tls.getSocketFactory());
    var commandKeys =
        new VaultHmacKeys(
            URI.create(required("AGENTOS_TEST_VAULT_URL")),
            "agentos",
            hmacTokens,
            VaultHmacKeys.Purpose.COMMAND_RECEIPTS,
            1,
            tls.getSocketFactory());
    assertThat(credentialKeys.currentVersion()).isEqualTo("2");
    for (String version : new String[] {"1", "2"}) {
      byte[] expectedKey =
          java.util.Base64.getDecoder()
              .decode(
                  Files.readString(
                          Path.of(
                              required("AGENTOS_TEST_HMAC_FIXTURE_DIR"), "credential-v" + version))
                      .strip());
      byte[] fetched = credentialKeys.key(version).getEncoded();
      try {
        assertThat(Arrays.equals(fetched, expectedKey))
            .as("exact historical credential HMAC key")
            .isTrue();
      } finally {
        Arrays.fill(expectedKey, (byte) 0);
        Arrays.fill(fetched, (byte) 0);
      }
    }
    byte[] expectedCommand =
        java.util.Base64.getDecoder()
            .decode(
                Files.readString(Path.of(required("AGENTOS_TEST_HMAC_FIXTURE_DIR"), "command-v1"))
                    .strip());
    byte[] fetchedCommand = commandKeys.key("1").getEncoded();
    try {
      assertThat(Arrays.equals(fetchedCommand, expectedCommand))
          .as("separate command HMAC key")
          .isTrue();
    } finally {
      Arrays.fill(expectedCommand, (byte) 0);
      Arrays.fill(fetchedCommand, (byte) 0);
    }
    assertThatThrownBy(() -> credentialKeys.key("3")).isInstanceOf(Failure.class).hasNoCause();
    assertThatThrownBy(() -> credentialKeys.key("01")).isInstanceOf(Failure.class).hasNoCause();
    var datasource =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            required("AGENTOS_TEST_JOURNAL_URL"),
            "vault_journal_test",
            Files.readString(Path.of(required("AGENTOS_TEST_PG_PASSWORD_FILE"))).strip());
    org.flywaydb.core.Flyway.configure().dataSource(datasource).load().migrate();
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(datasource);
    var journal = new com.agentos.providerpreparation.ProviderPreparationJournal(datasource);
    var composed = new JournaledVaultSecrets(journal, adapter);
    Path evidence = Path.of(required("AGENTOS_TEST_VAULT_REFERENCE_FILE"));
    String phase = required("AGENTOS_TEST_VAULT_PHASE");
    var json = new ObjectMapper();
    byte[] expected = "java-vault-integration-synthetic-only".getBytes(StandardCharsets.US_ASCII);
    byte[] actual = null;
    var boundary = new com.agentos.providercredentials.CredentialBoundary(credentialKeys, composed);
    com.agentos.providercredentials.CredentialBoundary.Metadata persisted;
    try {
      Reference reference;
      if (phase.equals("prepare")) {
        var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), Provider.OPENAI, 1);
        jdbc.update(
            "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Vault"
                + " journal fixture',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            scope.organization(),
            scope.organization().toString());
        char[] submitted = new String(expected, StandardCharsets.US_ASCII).toCharArray();
        persisted = boundary.prepare(scope, submitted);
        assertThat(submitted).containsOnly('\0');
        reference = persisted.reference();
        UUID preparationId =
            jdbc.queryForObject(
                "select id from provider_secret_preparations where external_locator=?",
                UUID.class,
                reference.locator());
        var preparation =
            new com.agentos.providerpreparation.ProviderPreparationJournal.Preparation(
                preparationId, scope, reference.locator());
        assertThat(journal.observe(preparation))
            .isEqualTo(
                com.agentos.providerpreparation.ProviderPreparationJournal.Observation.UNRESOLVED);
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(datasource))
            .executeWithoutResult(
                status -> {
                  jdbc.update(
                      "insert into provider_credential_versions"
                          + "(organization_id,connection_id,provider,credential_revision,external_locator,"
                          + "external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
                          + " values (?,?,'OPENAI',1,?,'1',?,'v2',?,CURRENT_TIMESTAMP)",
                      scope.organization(),
                      scope.connection(),
                      persisted.reference().locator(),
                      persisted.macVersion(),
                      persisted.digest());
                  jdbc.update(
                      "insert into provider_connections"
                          + "(id,organization_id,provider,mode,name,state,configuration_revision,"
                          + "credential_revision,created_at,updated_at) values"
                          + " (?,?,'OPENAI','OPENAI_API_KEY','Synthetic"
                          + " fixture','ENABLED',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                      scope.connection(),
                      scope.organization());
                });
        var safe = json.createObjectNode();
        safe.put("organization", scope.organization().toString());
        safe.put("connection", scope.connection().toString());
        safe.put("locator", reference.locator());
        Files.write(evidence, json.writeValueAsBytes(safe));
      } else {
        assertThat(phase).isEqualTo("restart");
        var safe = json.readTree(Files.readAllBytes(evidence));
        var scope =
            new Scope(
                UUID.fromString(safe.path("organization").asText()),
                UUID.fromString(safe.path("connection").asText()),
                Provider.OPENAI,
                1);
        reference = new Reference(scope, safe.path("locator").asText(), "1");
        var stored =
            jdbc.queryForMap(
                "select hmac_key_version,verifier from provider_credential_versions where"
                    + " organization_id=? and connection_id=? and provider='OPENAI' and"
                    + " credential_revision=1",
                scope.organization(),
                scope.connection());
        persisted =
            new com.agentos.providercredentials.CredentialBoundary.Metadata(
                scope,
                (String) stored.get("hmac_key_version"),
                reference,
                (String) stored.get("verifier"));
      }
      var verified = new java.util.concurrent.atomic.AtomicBoolean();
      boundary.use(
          reference.scope(),
          persisted,
          borrowed -> verified.set(Arrays.equals(borrowed, expected)));
      assertThat(verified.get()).as("HMAC verified before credential callback").isTrue();
      actual = composed.read(reference);
      assertThat(Arrays.equals(actual, expected))
          .as("exact synthetic credential readback")
          .isTrue();
      var row =
          jdbc.queryForMap(
              "select * from provider_secret_preparations where external_locator=?",
              reference.locator());
      assertThat(row.get("organization_id")).isEqualTo(reference.scope().organization());
      assertThat(row.get("connection_id")).isEqualTo(reference.scope().connection());
      assertThat(row.get("state")).isEqualTo("WRITTEN");
      assertThat(row.get("external_version")).isEqualTo("1");
      String beforeObservation =
          jdbc.queryForObject(
              "select row_to_json(p)::text from provider_secret_preparations p where id=?",
              String.class,
              row.get("id"));
      var preparation =
          new com.agentos.providerpreparation.ProviderPreparationJournal.Preparation(
              (UUID) row.get("id"), reference.scope(), reference.locator());
      assertThat(journal.observe(preparation))
          .isEqualTo(
              com.agentos.providerpreparation.ProviderPreparationJournal.Observation
                  .RETAINED_REFERENCE_FOUND);
      assertThat(
              jdbc.queryForObject(
                  "select row_to_json(p)::text from provider_secret_preparations p where id=?",
                  String.class,
                  preparation.id()))
          .isEqualTo(beforeObservation);
      assertThat(
              jdbc.queryForObject("select count(*) from provider_secret_preparations", Long.class))
          .isEqualTo(1L);
      var foreignPreparation =
          new com.agentos.providerpreparation.ProviderPreparationJournal.Preparation(
              preparation.id(),
              new Scope(reference.scope().organization(), UUID.randomUUID(), Provider.OPENAI, 1),
              reference.locator());
      assertThatThrownBy(() -> journal.observe(foreignPreparation))
          .hasMessage("PREPARATION_JOURNAL_UNAVAILABLE")
          .hasNoCause();
      jdbc.update(
          "update provider_credential_versions set external_key_version='2' where organization_id=?"
              + " and connection_id=?",
          reference.scope().organization(),
          reference.scope().connection());
      try {
        assertThat(journal.observe(preparation))
            .isEqualTo(
                com.agentos.providerpreparation.ProviderPreparationJournal.Observation.UNRESOLVED);
      } finally {
        jdbc.update(
            "update provider_credential_versions set external_key_version='1' where"
                + " organization_id=? and connection_id=?",
            reference.scope().organization(),
            reference.scope().connection());
      }
      if (phase.equals("restart")) {
        realCreateAndReplay(
            datasource, jdbc, journal, composed, credentialKeys, commandKeys, vaultRequests);
      }
      var forbidden = new java.util.ArrayList<String>();
      forbidden.add(new String(expected, StandardCharsets.US_ASCII));
      forbidden.add(java.util.Base64.getEncoder().encodeToString(expected));
      forbidden.add(CREATE_SENTINEL);
      forbidden.add(
          java.util.Base64.getEncoder()
              .encodeToString(CREATE_SENTINEL.getBytes(StandardCharsets.US_ASCII)));
      for (String keyFile : new String[] {"credential-v1", "credential-v2", "command-v1"}) {
        forbidden.add(
            Files.readString(Path.of(required("AGENTOS_TEST_HMAC_FIXTURE_DIR"), keyFile)).strip());
      }
      for (String table :
          jdbc.queryForList(
              "select tablename from pg_catalog.pg_tables where schemaname='public'",
              String.class)) {
        if (!table.matches("[a-z_][a-z0-9_]*"))
          throw new IllegalStateException("Unexpected test table");
        for (String stored :
            jdbc.queryForList(
                "select row_to_json(t)::text from public." + table + " t", String.class)) {
          for (String sentinel : forbidden) {
            assertThat(stored.contains(sentinel))
                .as("no synthetic secret in public DB rows")
                .isFalse();
          }
        }
      }
      Scope foreign =
          new Scope(UUID.randomUUID(), reference.scope().connection(), Provider.OPENAI, 1);
      assertThatThrownBy(() -> adapter.read(new Reference(foreign, reference.locator(), "1")))
          .isInstanceOf(Failure.class)
          .hasNoCause();
      assertThatThrownBy(
              () -> adapter.read(new Reference(reference.scope(), reference.locator(), "2")))
          .isInstanceOf(Failure.class)
          .hasNoCause();
    } finally {
      Arrays.fill(expected, (byte) 0);
      if (actual != null) Arrays.fill(actual, (byte) 0);
    }
  }

  private static void realCreateAndReplay(
      DataSource datasource,
      JdbcTemplate jdbc,
      ProviderPreparationJournal journal,
      JournaledVaultSecrets composed,
      VaultHmacKeys credentialKeys,
      VaultHmacKeys commandKeys,
      AtomicInteger vaultRequests)
      throws Exception {
    UUID org = UUID.randomUUID(), key = UUID.randomUUID();
    var human = seedCreateActor(jdbc, org);
    var credentials = new CredentialBoundary(credentialKeys, composed);
    jdbc.update(
        "insert into provider_deployment_policy"
            + " (id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)"
            + " values (1,1,true,false,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      context.registerBean(
          PlatformTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
      context.register(
          TransactionConfiguration.class,
          ProviderReceiptService.class,
          ProviderConnectionService.class);
      context.refresh();
      var receipts = context.getBean(ProviderReceiptService.class);
      var connections = context.getBean(ProviderConnectionService.class);
      assertThat(AopUtils.isCglibProxy(receipts)).isTrue();
      assertThat(AopUtils.isCglibProxy(connections)).isTrue();
      var completion =
          new ProviderCompletionBoundary(
              context.getBean(PlatformTransactionManager.class), jdbc, receipts);
      var coordinator =
          new ProviderCreateCoordinator(
              receipts,
              completion,
              new CommandBoundary(commandKeys),
              credentials,
              new ProviderCreateAdmission(datasource));
      int beforeCreate = vaultRequests.get();
      var first = coordinator.create(human, org, key, createBody());
      assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(first.result().replayed()).isFalse();
      assertThat(vaultRequests.get()).isEqualTo(beforeCreate + 1);
      UUID connection = first.result().receipt().connectionId();
      var stored = connections.credentialVersion(human, org, connection, 1);
      var reference = stored.reference();
      assertThat(reference.scope()).isEqualTo(new Scope(org, connection, Provider.OPENAI, 1));
      assertThat(reference.keyVersion()).isEqualTo("1");
      UUID preparationId =
          jdbc.queryForObject(
              "select id from provider_secret_preparations where organization_id=? and"
                  + " connection_id=? and external_locator=? and external_version=? and"
                  + " state='WRITTEN'",
              UUID.class,
              org,
              connection,
              reference.locator(),
              reference.keyVersion());
      var preparation =
          new ProviderPreparationJournal.Preparation(
              preparationId, reference.scope(), reference.locator());
      assertThat(journal.observe(preparation))
          .isEqualTo(ProviderPreparationJournal.Observation.RETAINED_REFERENCE_FOUND);
      var replay = coordinator.create(human, org, key, createBody());
      assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(replay.result().replayed()).isTrue();
      assertThat(replay.result().receipt()).isEqualTo(first.result().receipt());
      assertThat(vaultRequests.get()).isEqualTo(beforeCreate + 1);
      for (String table :
          new String[] {
            "provider_secret_preparations",
            "provider_credential_versions",
            "provider_connections",
            "provider_command_receipts",
            "provider_connection_events"
          }) {
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from " + table + " where organization_id=?", Long.class, org))
            .as("exactly one scoped row in %s", table)
            .isEqualTo(1L);
      }
      byte[] expected = CREATE_SENTINEL.getBytes(StandardCharsets.US_ASCII);
      try {
        credentials.use(
            reference.scope(),
            stored,
            borrowed -> assertThat(Arrays.equals(borrowed, expected)).isTrue());
      } finally {
        Arrays.fill(expected, (byte) 0);
      }
      realConcurrentCreate(
          datasource,
          jdbc,
          journal,
          composed,
          credentialKeys,
          commandKeys,
          vaultRequests,
          receipts,
          connections,
          completion);
      realCreateWithLostCommitAck(
          datasource,
          jdbc,
          journal,
          credentials,
          commandKeys,
          vaultRequests,
          context.getBean(PlatformTransactionManager.class),
          receipts,
          connections);
    }
  }

  private static void realConcurrentCreate(
      DataSource datasource,
      JdbcTemplate jdbc,
      ProviderPreparationJournal journal,
      JournaledVaultSecrets composed,
      VaultHmacKeys credentialKeys,
      VaultHmacKeys commandKeys,
      AtomicInteger vaultRequests,
      ProviderReceiptService receipts,
      ProviderConnectionService connections,
      ProviderCompletionBoundary completion)
      throws Exception {
    UUID org = UUID.randomUUID(), key = UUID.randomUUID();
    var human = seedCreateActor(jdbc, org);
    var acknowledgedStores = new CountDownLatch(2);
    var gatedSecrets =
        new CredentialBoundary.Secrets() {
          @Override
          public Reference store(Scope scope, byte[] borrowedCredential) {
            Reference written = composed.store(scope, borrowedCredential);
            acknowledgedStores.countDown();
            try {
              if (!acknowledgedStores.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Both Vault stores must finish before completion");
              }
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError("Interrupted waiting for both Vault stores", failure);
            }
            return written;
          }

          @Override
          public byte[] read(Reference reference) {
            return composed.read(reference);
          }

          @Override
          public void preparationFailed(Scope scope, Reference reference) throws Exception {
            composed.preparationFailed(scope, reference);
          }
        };
    var coordinator =
        new ProviderCreateCoordinator(
            receipts,
            completion,
            new CommandBoundary(commandKeys),
            new CredentialBoundary(credentialKeys, gatedSecrets),
            new ProviderCreateAdmission(datasource));
    int before = vaultRequests.get();
    var workers = Executors.newFixedThreadPool(2);
    UUID connection;
    try {
      var left = workers.submit(() -> coordinator.create(human, org, key, createBody()));
      var right = workers.submit(() -> coordinator.create(human, org, key, createBody()));
      var first = left.get(35, TimeUnit.SECONDS);
      var second = right.get(35, TimeUnit.SECONDS);
      assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(second.state()).isEqualTo(WriteState.COMMITTED);
      assertThat(List.of(first.result().replayed(), second.result().replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(second.result().receipt()).isEqualTo(first.result().receipt());
      connection = first.result().receipt().connectionId();
    } finally {
      workers.shutdownNow();
    }
    assertThat(vaultRequests.get()).isEqualTo(before + 2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from provider_secret_preparations where organization_id=?",
                Long.class,
                org))
        .isEqualTo(2L);
    var preparations =
        jdbc.query(
            "select id,connection_id,external_locator from provider_secret_preparations"
                + " where organization_id=? and provider='OPENAI' and credential_revision=1"
                + " and state='WRITTEN' and external_version='1'",
            (row, index) ->
                new ProviderPreparationJournal.Preparation(
                    row.getObject("id", UUID.class),
                    new Scope(org, row.getObject("connection_id", UUID.class), Provider.OPENAI, 1),
                    row.getString("external_locator")),
            org);
    assertThat(preparations).hasSize(2);
    var retained = connections.credentialVersion(human, org, connection, 1).reference();
    assertThat(preparations.stream().map(journal::observe).toList())
        .containsExactlyInAnyOrder(
            ProviderPreparationJournal.Observation.RETAINED_REFERENCE_FOUND,
            ProviderPreparationJournal.Observation.UNRESOLVED);
    byte[] expected = CREATE_SENTINEL.getBytes(StandardCharsets.US_ASCII);
    try {
      for (var preparation : preparations) {
        var reference = new Reference(preparation.scope(), preparation.locator(), "1");
        assertThat(journal.observe(preparation))
            .isEqualTo(
                retained.equals(reference)
                    ? ProviderPreparationJournal.Observation.RETAINED_REFERENCE_FOUND
                    : ProviderPreparationJournal.Observation.UNRESOLVED);
        byte[] readback = composed.read(reference);
        try {
          assertThat(Arrays.equals(readback, expected)).isTrue();
        } finally {
          Arrays.fill(readback, (byte) 0);
        }
      }
    } finally {
      Arrays.fill(expected, (byte) 0);
    }
    assertThat(vaultRequests.get()).isEqualTo(before + 4);
    for (String table :
        List.of(
            "provider_credential_versions",
            "provider_connections",
            "provider_command_receipts",
            "provider_connection_events")) {
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + table + " where organization_id=?", Long.class, org))
          .as("exactly one scoped row in %s", table)
          .isEqualTo(1L);
    }
  }

  private static HumanIdentity seedCreateActor(JdbcTemplate jdbc, UUID org) {
    UUID actor = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Vault Create fixture',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Vault Create fixture','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    return new HumanIdentity(actor, actor + "@example.test", "Vault Create fixture", 0);
  }

  private static ProviderCreateBody createBody() {
    return ProviderCreateBody.consume(
        """
        {"provider":"OPENAI","credentialMode":"OPENAI_API_KEY","name":"Vault Create fixture",
        "options":{},"apiKey":"%s"}
        """
            .formatted(CREATE_SENTINEL)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static void realCreateWithLostCommitAck(
      DataSource datasource,
      JdbcTemplate jdbc,
      ProviderPreparationJournal journal,
      CredentialBoundary credentials,
      VaultHmacKeys commandKeys,
      AtomicInteger vaultRequests,
      PlatformTransactionManager manager,
      ProviderReceiptService receipts,
      ProviderConnectionService connections) {
    UUID org = UUID.randomUUID(), key = UUID.randomUUID();
    var human = seedCreateActor(jdbc, org);
    var commits = new AtomicInteger();
    PlatformTransactionManager lostAck =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return manager.getTransaction(definition);
          }

          @Override
          public void commit(TransactionStatus status) {
            manager.commit(status);
            commits.incrementAndGet();
            throw new IllegalStateException("Simulated lost commit acknowledgement");
          }

          @Override
          public void rollback(TransactionStatus status) {
            manager.rollback(status);
          }
        };
    var commands = new CommandBoundary(commandKeys);
    var admission = new ProviderCreateAdmission(datasource);
    var uncertainCoordinator =
        new ProviderCreateCoordinator(
            receipts,
            new ProviderCompletionBoundary(lostAck, jdbc, receipts),
            commands,
            credentials,
            admission);
    int beforeCreate = vaultRequests.get();
    var uncertain = uncertainCoordinator.create(human, org, key, createBody());
    assertThat(commits.get()).isEqualTo(1);
    assertThat(uncertain.state()).isEqualTo(WriteState.UNKNOWN);
    assertThat(uncertain.failure().code()).isEqualTo(Code.COMMAND_OUTCOME_UNKNOWN);
    assertThat(uncertain.result()).isNull();
    assertThat(vaultRequests.get()).isEqualTo(beforeCreate + 1);

    var healthy = new ProviderCompletionBoundary(manager, jdbc, receipts);
    var recovered = healthy.lookupOwn(human, org, key);
    assertThat(recovered.state()).isEqualTo(LookupState.FOUND);
    assertThat(vaultRequests.get()).isEqualTo(beforeCreate + 1);
    var replay =
        new ProviderCreateCoordinator(receipts, healthy, commands, credentials, admission)
            .create(human, org, key, createBody());
    assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(replay.result().replayed()).isTrue();
    assertThat(replay.result().receipt()).isEqualTo(recovered.receipt());
    assertThat(vaultRequests.get()).isEqualTo(beforeCreate + 1);

    UUID connection = recovered.receipt().connectionId();
    var reference = connections.credentialVersion(human, org, connection, 1).reference();
    var row =
        jdbc.queryForMap(
            "select id,external_version,state from provider_secret_preparations"
                + " where organization_id=? and external_locator=?",
            org,
            reference.locator());
    assertThat(row).containsEntry("state", "WRITTEN").containsEntry("external_version", "1");
    var preparation =
        new ProviderPreparationJournal.Preparation(
            (UUID) row.get("id"), reference.scope(), reference.locator());
    assertThat(journal.observe(preparation))
        .isEqualTo(ProviderPreparationJournal.Observation.RETAINED_REFERENCE_FOUND);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TransactionConfiguration {}

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing test configuration");
    return value;
  }
}

package com.agentos.providercompletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.auth.HumanIdentity;
import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.agentos.providerrename.ProviderRenameCoordinator;
import java.security.Key;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared JDBC/proxy acceptance for Rename; no inherited Create baseline tests. */
abstract class ProviderRenameFixture {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired ProviderReceiptService receipts;
  @Autowired ProviderConnectionService connections;
  UUID org, actor, id, createKey;
  HumanIdentity human;
  ProviderCompletionBoundary completion;
  ProviderWriteAdmission admission;
  CommandBoundary commands;
  ProviderRenameCoordinator coordinator;
  final java.util.concurrent.atomic.AtomicReference<String> currentVersion =
      new java.util.concurrent.atomic.AtomicReference<>("rename-v1");
  final java.util.List<String> requestedVersions =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  boolean requireAdmission;
  final AtomicBoolean historicalUnavailable = new AtomicBoolean();

  @BeforeEach
  void seedRename() {
    org = UUID.randomUUID();
    actor = UUID.randomUUID();
    id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,"
            + " 'Rename',?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Rename','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    jdbc.update("delete from provider_deployment_policy");
    jdbc.update(
        "insert into"
            + " provider_deployment_policy(id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)"
            + " values (1,1,true,true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    human = new HumanIdentity(actor, "unused@example.test", "Rename", 0);
    commands =
        new CommandBoundary(
            new CommandBoundary.Keys() {
              public String currentVersion() {
                outside();
                if (requireAdmission) assertThat(quota()).isPositive();
                return currentVersion.get();
              }

              public Key key(String version) {
                outside();
                requestedVersions.add(version);
                if (historicalUnavailable.get())
                  throw new IllegalStateException("Synthetic key outage");
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            });
    completion = new ProviderCompletionBoundary(manager, jdbc, receipts);
    admission = new ProviderWriteAdmission(jdbc.getDataSource());
    coordinator = new ProviderRenameCoordinator(receipts, completion, commands, admission);
    var create = new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Original", null);
    char[] secret = "disposable-rename-seed-key".toCharArray();
    var metadata = commands.prepareCurrent(new CommandBoundary.Scope(org, actor), create, secret);
    java.util.Arrays.fill(secret, '\0');
    var scope = new CredentialBoundary.Scope(org, id, CredentialBoundary.Provider.OPENAI, 1);
    var credential =
        new CredentialBoundary.Metadata(
            scope,
            "credential-v1",
            new CredentialBoundary.Reference(scope, "rename-fixture-opaque", "version-1"),
            Base64.getEncoder().encodeToString(new byte[32]));
    createKey = UUID.randomUUID();
    assertThat(
            completion
                .executeCreatePrepared(human, org, createKey, create, metadata, id, credential)
                .state())
        .isEqualTo(WriteState.COMMITTED);
    requireAdmission = true;
  }

  static void outside() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  @Test
  void renameCommitsAtomicHistoricalConfigurationWithoutCredentialMutationOrEnablementGates() {
    var credentials =
        jdbc.queryForList(
            "select * from provider_credential_versions where organization_id=?", org);
    connections.setEnabled(human, org, id, 1, false);
    jdbc.update("update provider_deployment_policy set openai_enabled=false where id=1");
    UUID key = UUID.randomUUID();
    var outcome = coordinator.rename(human, org, key, new Rename(id, 2, "Renamed"));
    assertThat(outcome.state()).isEqualTo(WriteState.COMMITTED);
    var receipt = outcome.result().receipt();
    assertThat(receipt.before().name()).isEqualTo("Original");
    assertThat(receipt.after().name()).isEqualTo("Renamed");
    assertThat(receipt.before().revision()).isEqualTo(2);
    assertThat(receipt.after().revision()).isEqualTo(3);
    assertThat(receipt.after().state()).isEqualTo("DISABLED");
    assertThat(receipt.after().credentialRevision()).isEqualTo(1);
    assertThat(receipt.after().workspaceId()).isNull();
    assertThat(
            jdbc.queryForList(
                "select * from provider_credential_versions where organization_id=?", org))
        .isEqualTo(credentials);
    assertThat(count("provider_connection_events")).isEqualTo(3);
    assertThat(count("provider_command_receipts")).isEqualTo(2);
    assertThat(quota()).isEqualTo(1);
    assertThat(completion.lookupOwn(human, org, key).receipt()).isEqualTo(receipt);
  }

  @Test
  void replayUsesHistoricalBodyKeyAndReceiptBeforeQuotaAndPolicy() {
    UUID key = UUID.randomUUID();
    var command = new Rename(id, 1, "First");
    var first = coordinator.rename(human, org, key, command);
    assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(
            coordinator.rename(human, org, UUID.randomUUID(), new Rename(id, 2, "Later")).state())
        .isEqualTo(WriteState.COMMITTED);
    while (quota() < 30) admission.acquireRename(human, org, UUID.randomUUID());
    jdbc.update("update provider_deployment_policy set openai_enabled=false where id=1");
    currentVersion.set("rename-v2");
    requestedVersions.clear();
    var replay = coordinator.rename(human, org, key, command);
    assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(replay.result().replayed()).isTrue();
    assertThat(requestedVersions).containsExactly("rename-v1");
    assertThat(replay.result().receipt()).isEqualTo(first.result().receipt());
    assertThat(quota()).isEqualTo(30);
    assertThat(coordinator.rename(human, org, createKey, command).failure().code())
        .isEqualTo(Code.IDEMPOTENCY_CONFLICT);
    assertThat(coordinator.rename(human, org, key, new Rename(id, 1, "Changed")).failure().code())
        .isEqualTo(Code.IDEMPOTENCY_CONFLICT);
    historicalUnavailable.set(true);
    assertThat(coordinator.rename(human, org, key, command).state())
        .isEqualTo(WriteState.UNAVAILABLE);
    historicalUnavailable.set(false);
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        actor);
    assertThat(coordinator.rename(human, org, key, command).failure().code())
        .isEqualTo(Code.FORBIDDEN);
  }

  @Test
  void businessRejectionsConsumeOneFreshAdmissionAndPreserveRowsInPrecedenceOrder() {
    var baseline = configuration();
    var cases =
        new Rename[] {
          new Rename(UUID.randomUUID(), 0, "Original"),
          new Rename(id, 0, "Original"),
          new Rename(id, 9, "Original"),
          new Rename(id, 1, "Original")
        };
    var expected =
        new Code[] {Code.NOT_FOUND, Code.INVALID_COMMAND, Code.REVISION_CONFLICT, Code.NO_CHANGE};
    for (int index = 0; index < cases.length; index++) {
      var result = coordinator.rename(human, org, UUID.randomUUID(), cases[index]);
      assertThat(result.state()).isEqualTo(WriteState.REJECTED);
      assertThat(result.failure().code()).isEqualTo(expected[index]);
      assertThat(quota()).isEqualTo(index + 1);
      assertThat(configuration()).isEqualTo(baseline);
      assertThat(count("provider_connection_events")).isEqualTo(1);
      assertThat(count("provider_command_receipts")).isEqualTo(1);
    }
  }

  @Test
  void maximumRevisionChecksNoChangeBeforeOverflowAndConsumesFreshSlots() {
    // Trusted retained-history fixture avoids billions of mutations; preserves the CREATE event.
    jdbc.update(
        "insert into"
            + " provider_connection_events(id,organization_id,connection_id,configuration_revision,credential_revision,kind,provider,mode,name,workspace,state,actor_id,created_at)"
            + " select"
            + " ?,organization_id,connection_id,?,credential_revision,'RENAME',provider,mode,'At"
            + " maximum',workspace,state,actor_id,created_at from provider_connection_events where"
            + " organization_id=? and connection_id=? and configuration_revision=1",
        UUID.randomUUID(),
        Long.MAX_VALUE,
        org,
        id);
    jdbc.update(
        "update provider_connections set configuration_revision=?,name='At maximum' where"
            + " organization_id=? and id=?",
        Long.MAX_VALUE,
        org,
        id);
    var baseline = configuration();
    assertThat(
            coordinator
                .rename(human, org, UUID.randomUUID(), new Rename(id, Long.MAX_VALUE, "At maximum"))
                .failure()
                .code())
        .isEqualTo(Code.NO_CHANGE);
    assertThat(
            coordinator
                .rename(
                    human, org, UUID.randomUUID(), new Rename(id, Long.MAX_VALUE, "Beyond maximum"))
                .failure()
                .code())
        .isEqualTo(Code.REVISION_EXHAUSTED);
    assertThat(quota()).isEqualTo(2);
    assertThat(configuration()).isEqualTo(baseline);
    assertThat(count("provider_connection_events")).isEqualTo(2);
    assertThat(count("provider_command_receipts")).isEqualTo(1);
  }

  @Test
  void renameRemainsAvailableAtCreateCapacity() {
    for (int index = 1; index < 100; index++) {
      UUID peer = UUID.randomUUID();
      var scope = new CredentialBoundary.Scope(org, peer, CredentialBoundary.Provider.OPENAI, 1);
      var metadata =
          new CredentialBoundary.Metadata(
              scope,
              "credential-v1",
              new CredentialBoundary.Reference(scope, "rename-capacity-" + peer, "version-1"),
              Base64.getEncoder().encodeToString(new byte[32]));
      connections.create(human, org, peer, "Capacity peer " + index, null, metadata);
    }
    assertThat(count("provider_connections")).isEqualTo(100);
    assertThat(
            coordinator
                .rename(human, org, UUID.randomUUID(), new Rename(id, 1, "At capacity"))
                .state())
        .isEqualTo(WriteState.COMMITTED);
    assertThat(count("provider_connections")).isEqualTo(100);
    assertThat(quota()).isEqualTo(1);
  }

  @Test
  void committedWinnerWithAnotherKeyVersionIsReprobedOnceWithoutAnotherAdmission() {
    UUID key = UUID.randomUUID();
    var command = new Rename(id, 1, "Version race winner");
    var versions = new java.util.ArrayList<String>();
    var winnerResult =
        new java.util.concurrent.atomic.AtomicReference<ProviderCompletionBoundary.WriteOutcome>();
    var currentCalls = new java.util.concurrent.atomic.AtomicInteger();
    var probes = new java.util.concurrent.atomic.AtomicInteger();
    var winnerCommands =
        new CommandBoundary(
            new CommandBoundary.Keys() {
              public String currentVersion() {
                outside();
                assertThat(quota()).isEqualTo(2);
                return "rename-v2";
              }

              public Key key(String version) {
                outside();
                versions.add("winner:" + version);
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            });
    var winner =
        new ProviderRenameCoordinator(
            receipts, completion, winnerCommands, new ProviderWriteAdmission(jdbc.getDataSource()));
    var outerCommands =
        new CommandBoundary(
            new CommandBoundary.Keys() {
              public String currentVersion() {
                outside();
                assertThat(currentCalls.incrementAndGet()).isEqualTo(1);
                assertThat(quota()).isEqualTo(1);
                var committed = winner.rename(human, org, key, command);
                assertThat(committed.state()).isEqualTo(WriteState.COMMITTED);
                assertThat(committed.result().replayed()).isFalse();
                winnerResult.set(committed);
                return "rename-v1";
              }

              public Key key(String version) {
                outside();
                versions.add("outer:" + version);
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            });
    var probeProxy = new org.springframework.aop.framework.ProxyFactory(receipts);
    probeProxy.setProxyTargetClass(true);
    probeProxy.addAdvice(
        (org.aopalliance.intercept.MethodInterceptor)
            invocation -> {
              if (invocation.getMethod().getName().equals("probeOwn")) probes.incrementAndGet();
              return invocation.proceed();
            });
    var outer =
        new ProviderRenameCoordinator(
            (ProviderReceiptService) probeProxy.getProxy(), completion, outerCommands, admission);
    var replay = outer.rename(human, org, key, command);
    assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(replay.result().replayed()).isTrue();
    assertThat(replay.result().receipt()).isEqualTo(winnerResult.get().result().receipt());
    assertThat(probes).hasValue(2);
    assertThat(currentCalls).hasValue(1);
    assertThat(versions).containsExactly("winner:rename-v2", "outer:rename-v1", "outer:rename-v2");
    assertThat(quota()).isEqualTo(2);
    assertThat(configuration().get("name")).isEqualTo("Version race winner");
    assertThat(count("provider_connection_events")).isEqualTo(2);
    assertThat(count("provider_command_receipts")).isEqualTo(2);
  }

  @Test
  void ambientTransactionIsRejectedBeforeKeysAdmissionOrBusinessMutation() {
    var baseline = configuration();
    var credentials =
        jdbc.queryForList(
            "select * from provider_credential_versions where organization_id=?", org);
    int quotaBefore = quota();
    var keyCalls = new java.util.concurrent.atomic.AtomicInteger();
    var forbiddenKeys =
        new CommandBoundary(
            new CommandBoundary.Keys() {
              public String currentVersion() {
                keyCalls.incrementAndGet();
                throw new AssertionError("No key access in ambient transaction");
              }

              public Key key(String version) {
                keyCalls.incrementAndGet();
                throw new AssertionError("No key access in ambient transaction");
              }
            });
    var guarded = new ProviderRenameCoordinator(receipts, completion, forbiddenKeys, admission);
    UUID key = UUID.randomUUID();
    var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    var result =
        transaction.execute(
            status -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return guarded.rename(human, org, key, new Rename(id, 1, "Must stay original"));
            });
    assertThat(result).isNotNull();
    assertThat(result.state()).isEqualTo(WriteState.UNAVAILABLE);
    assertThat(result.failure().code()).isEqualTo(Code.COMMAND_UNAVAILABLE);
    assertThat(result.result()).isNull();
    assertThat(keyCalls).hasValue(0);
    assertThat(quota()).isEqualTo(quotaBefore);
    assertThat(configuration()).isEqualTo(baseline);
    assertThat(
            jdbc.queryForList(
                "select * from provider_credential_versions where organization_id=?", org))
        .isEqualTo(credentials);
    assertThat(count("provider_connection_events")).isEqualTo(1);
    assertThat(count("provider_command_receipts")).isEqualTo(1);
    assertThat(completion.lookupOwn(human, org, key).state()).isEqualTo(LookupState.ABSENT);
  }

  @Test
  void commitAcknowledgementLossReturnsUnknownAndOwnReceiptRecovers() {
    PlatformTransactionManager lostAck =
        new PlatformTransactionManager() {
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return manager.getTransaction(definition);
          }

          public void commit(TransactionStatus status) {
            manager.commit(status);
            throw new IllegalStateException("Synthetic lost acknowledgement");
          }

          public void rollback(TransactionStatus status) {
            throw new AssertionError("No rollback after commit starts");
          }
        };
    var uncertain =
        new ProviderRenameCoordinator(
            receipts, new ProviderCompletionBoundary(lostAck, jdbc, receipts), commands, admission);
    UUID key = UUID.randomUUID();
    var result = uncertain.rename(human, org, key, new Rename(id, 1, "Committed uncertain"));
    assertThat(result.state()).isEqualTo(WriteState.UNKNOWN);
    assertThat(result.result()).isNull();
    var lookup = completion.lookupOwn(human, org, key);
    assertThat(lookup.state()).isEqualTo(LookupState.FOUND);
    assertThat(lookup.receipt().after().name()).isEqualTo("Committed uncertain");
    assertThat(lookup.receipt().after().revision()).isEqualTo(2);
    assertThat(count("provider_command_receipts")).isEqualTo(2);
  }

  int quota() {
    var samples =
        jdbc.queryForList(
            "select recent_requests from provider_write_admission where organization_id=? and"
                + " actor_id=?",
            String.class,
            org,
            actor);
    return samples.isEmpty() || samples.get(0).isEmpty() ? 0 : samples.get(0).split(",", -1).length;
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  Map<String, Object> configuration() {
    return jdbc.queryForMap(
        "select * from provider_connections where organization_id=? and id=?", org, id);
  }
}

package com.agentos.providercompletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercompletion.ProviderCompletionBoundary.Code;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupState;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteState;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import com.agentos.providerreceipts.ProviderReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.security.Key;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared real JDBC/proxy tests. Decorators are supplied only to the boundary. */
abstract class ProviderCompletionFixture {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired ProviderReceiptService receipts;
  @Autowired ProviderConnectionService connections;
  UUID org, actor, key, id;
  int executeCalls, lookupCalls;
  HumanIdentity human;
  ProviderCompletionBoundary healthy;
  final Create command =
      new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Completion", null);
  Metadata prepared;
  int keyCalls, stores;
  CredentialBoundary.Metadata credential;
  static final String SENTINEL = "secret-SQL-vault-key-sensitive-918573";
  static final List<String> TABLES =
      List.of(
          "provider_connections",
          "provider_credential_versions",
          "provider_connection_events",
          "provider_command_receipts");

  @BeforeEach
  void seed() {
    org = UUID.randomUUID();
    actor = UUID.randomUUID();
    key = UUID.randomUUID();
    id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Completion',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Completion','unused')",
        actor,
        actor + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        actor,
        org);
    human = new HumanIdentity(actor, "unused@example.test", "Completion", 0);
    char[] secret = "completion-visible-ASCII-secret-918573".toCharArray();
    try {
      prepared =
          new CommandBoundary(
                  new CommandBoundary.Keys() {
                    public String currentVersion() {
                      outside();
                      keyCalls++;
                      return "key-v1";
                    }

                    public Key key(String version) {
                      outside();
                      keyCalls++;
                      return new SecretKeySpec(new byte[32], "HmacSHA256");
                    }
                  })
              .prepareCurrent(new CommandBoundary.Scope(org, actor), command, secret);
    } finally {
      Arrays.fill(secret, '\0');
    }
    char[] credentialSecret = "completion-visible-ASCII-secret-918573".toCharArray();
    try {
      credential =
          new CredentialBoundary(
                  new CredentialBoundary.Keys() {
                    public String currentVersion() {
                      outside();
                      keyCalls++;
                      return "credential-v1";
                    }

                    public SecretKey key(String version) {
                      outside();
                      keyCalls++;
                      return new SecretKeySpec(new byte[32], "HmacSHA256");
                    }
                  },
                  new CredentialBoundary.Secrets() {
                    public CredentialBoundary.Reference store(
                        CredentialBoundary.Scope scope, byte[] bytes) {
                      outside();
                      stores++;
                      return new CredentialBoundary.Reference(scope, "opaque/test", "external-v1");
                    }

                    public byte[] read(CredentialBoundary.Reference reference) {
                      throw new AssertionError("No secret read");
                    }

                    public void preparationFailed(
                        CredentialBoundary.Scope scope, CredentialBoundary.Reference reference) {
                      throw new AssertionError("No compensation");
                    }
                  })
              .prepare(
                  new CredentialBoundary.Scope(org, id, CredentialBoundary.Provider.OPENAI, 1),
                  credentialSecret);
    } finally {
      Arrays.fill(credentialSecret, '\0');
    }
    assertThat(stores).isEqualTo(1);
    assertThat(keyCalls).isPositive();
    healthy = boundary(manager);
    assertThat(AopUtils.isAopProxy(receipts)).isTrue();
    assertThat(AopUtils.isAopProxy(connections)).isTrue();
  }

  static void outside() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  ProviderCompletionBoundary boundary(PlatformTransactionManager tm) {
    var factory = new ProxyFactory(receipts);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        (MethodInterceptor)
            invocation -> {
              if (invocation.getMethod().getName().equals("execute")) executeCalls++;
              if (invocation.getMethod().getName().equals("findOwn")) lookupCalls++;
              return invocation.proceed();
            });
    return new ProviderCompletionBoundary(tm, jdbc, (ProviderReceiptService) factory.getProxy());
  }

  ProviderCompletionBoundary.WriteOutcome execute(ProviderCompletionBoundary boundary) {
    int keysBefore = keyCalls, storesBefore = stores;
    var outcome = boundary.executePrepared(human, org, key, command, prepared, id, credential);
    assertThat(keyCalls).isEqualTo(keysBefore);
    assertThat(stores).isEqualTo(storesBefore);
    return outcome;
  }

  @Test
  void createCoordinatorReplaysWithoutAnotherSecretWriteAndDeniesRevocation() {
    enableCreatePolicy();
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    var outage = new java.util.concurrent.atomic.AtomicBoolean();
    var commandKeys =
        new CommandBoundary(
            new CommandBoundary.Keys() {
              public String currentVersion() {
                outside();
                return "key-v1";
              }

              public Key key(String version) {
                outside();
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            });
    var credentialBoundary =
        new CredentialBoundary(
            new CredentialBoundary.Keys() {
              public String currentVersion() {
                outside();
                return "credential-v1";
              }

              public SecretKey key(String version) {
                outside();
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            },
            new CredentialBoundary.Secrets() {
              public CredentialBoundary.Reference store(
                  CredentialBoundary.Scope scope, byte[] value) {
                outside();
                if (outage.get()) throw new CredentialBoundary.Failure();
                writes.incrementAndGet();
                return new CredentialBoundary.Reference(
                    scope, "opaque/create-fixture", "external-v1");
              }

              public byte[] read(CredentialBoundary.Reference reference) {
                throw new AssertionError("No secret read");
              }

              public void preparationFailed(
                  CredentialBoundary.Scope scope, CredentialBoundary.Reference reference) {}
            });
    var coordinator =
        new com.agentos.providercreation.ProviderCreateCoordinator(
            receipts, healthy, commandKeys, credentialBoundary);
    var first = coordinator.create(human, org, key, createBody());
    assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(first.result().replayed()).isFalse();
    assertThat(writes.get()).isEqualTo(1);
    outage.set(true);
    var replay = coordinator.create(human, org, key, createBody());
    assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(replay.result().replayed()).isTrue();
    assertThat(replay.result().receipt()).isEqualTo(first.result().receipt());
    assertThat(writes.get()).isEqualTo(1);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    var denied = coordinator.create(human, org, key, createBody());
    assertThat(denied.state()).isEqualTo(WriteState.REJECTED);
    assertThat(denied.failure().code()).isEqualTo(Code.FORBIDDEN);
    assertThat(writes.get()).isEqualTo(1);
    rows().values().forEach(value -> assertThat(value).hasSize(1));
    assertThat(rows().get("provider_connections").get(0).get("id"))
        .isEqualTo(first.result().receipt().connectionId());
  }

  @Test
  void createCoordinatorRechecksAuthorityAfterExternalPreparation() {
    enableCreatePolicy();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var commandKeys =
        new CommandBoundary(
            new CommandBoundary.Keys() {
              public String currentVersion() {
                outside();
                return "key-v1";
              }

              public Key key(String version) {
                outside();
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            });
    var credentialBoundary =
        new CredentialBoundary(
            new CredentialBoundary.Keys() {
              public String currentVersion() {
                outside();
                return "credential-v1";
              }

              public SecretKey key(String version) {
                outside();
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            },
            new CredentialBoundary.Secrets() {
              public CredentialBoundary.Reference store(
                  CredentialBoundary.Scope scope, byte[] value) {
                outside();
                calls.incrementAndGet();
                jdbc.update(
                    "update organization_memberships set role='MEMBER' where account_id=? and"
                        + " organization_id=?",
                    actor,
                    org);
                return new CredentialBoundary.Reference(
                    scope, "opaque/revocation-fixture", "external-v1");
              }

              public byte[] read(CredentialBoundary.Reference reference) {
                throw new AssertionError("No secret read");
              }

              public void preparationFailed(
                  CredentialBoundary.Scope scope, CredentialBoundary.Reference reference) {}
            });
    var coordinator =
        new com.agentos.providercreation.ProviderCreateCoordinator(
            receipts, healthy, commandKeys, credentialBoundary);
    var decoded = createBody();
    byte[] borrowed = decoded.credential();
    var result = coordinator.create(human, org, key, decoded);
    assertThat(result.state()).isEqualTo(WriteState.REJECTED);
    assertThat(result.failure().code()).isEqualTo(Code.FORBIDDEN);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(borrowed).containsOnly((byte) 0);
    empty();
  }

  @Test
  void governedCreateCapacityCountsDisabledConnectionsAndMakesNoChanges() {
    enableCreatePolicy();
    for (int index = 0; index < 100; index++) {
      UUID connection = UUID.randomUUID();
      jdbc.update(
          "insert into provider_credential_versions"
              + "(organization_id,connection_id,provider,credential_revision,external_locator,"
              + "external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
              + " values"
              + " (?,?,'OPENAI',1,'opaque/capacity-fixture','external-v1','credential-v1','v2',?,CURRENT_TIMESTAMP)",
          org,
          connection,
          credential.digest());
      jdbc.update(
          "insert into provider_connections"
              + "(id,organization_id,provider,mode,name,state,configuration_revision,credential_revision,created_at,updated_at)"
              + " values (?,?,'OPENAI','OPENAI_API_KEY','Capacity"
              + " fixture',?,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          connection,
          org,
          index % 2 == 0 ? "DISABLED" : "ENABLED");
    }
    var before = rows();
    var denied = healthy.executeCreatePrepared(human, org, key, command, prepared, id, credential);
    assertThat(denied.state()).isEqualTo(WriteState.REJECTED);
    assertThat(denied.failure().code()).isEqualTo(Code.CONNECTION_LIMIT);
    assertThat(rows()).isEqualTo(before);
  }

  private void enableCreatePolicy() {
    jdbc.update("delete from provider_deployment_policy");
    jdbc.update(
        "insert into provider_deployment_policy"
            + "(id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)"
            + " values (1,1,true,true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
  }

  @Test
  void governedCreatePolicyDoesNotOverrideHistoricalReplay() {
    enableCreatePolicy();
    var first = healthy.executeCreatePrepared(human, org, key, command, prepared, id, credential);
    assertThat(first.state()).isEqualTo(WriteState.COMMITTED);
    jdbc.update(
        "update provider_deployment_policy set openai_enabled=false,revision=revision+1 where"
            + " id=1");
    var replay = healthy.executeCreatePrepared(human, org, key, command, prepared, id, credential);
    assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(replay.result().replayed()).isTrue();
    var before = rows();
    var denied =
        healthy.executeCreatePrepared(
            human, org, UUID.randomUUID(), command, prepared, UUID.randomUUID(), credential);
    assertThat(denied.state()).isEqualTo(WriteState.REJECTED);
    assertThat(denied.failure().code()).isEqualTo(Code.PROVIDER_DISABLED);
    assertThat(rows()).isEqualTo(before);
    jdbc.update("delete from provider_deployment_policy");
    var missing =
        healthy.executeCreatePrepared(
            human, org, UUID.randomUUID(), command, prepared, id, credential);
    assertThat(missing.state()).isEqualTo(WriteState.REJECTED);
    assertThat(missing.failure().code()).isEqualTo(Code.COMMAND_UNAVAILABLE);
    assertThat(rows()).isEqualTo(before);
  }

  private static com.agentos.providertransport.ProviderCreateBody createBody() {
    return com.agentos.providertransport.ProviderCreateBody.consume(
        ("{\"provider\":\"OPENAI\",\"credentialMode\":\"OPENAI_API_KEY\","
                + "\"name\":\"Coordinator fixture\",\"options\":{},\"apiKey\":\"synthetic-only\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  Map<String, List<Map<String, Object>>> rows() {
    Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
    for (String table : TABLES)
      result.put(
          table, jdbc.queryForList("select * from " + table + " where organization_id=?", org));
    return result;
  }

  void empty() {
    rows().values().forEach(value -> assertThat(value).isEmpty());
  }

  void one() {
    var persisted = rows();
    persisted.values().forEach(value -> assertThat(value).hasSize(1));
    var parent = persisted.get("provider_connections").get(0);
    assertThat(parent.keySet())
        .containsExactlyInAnyOrder(
            "id",
            "organization_id",
            "provider",
            "mode",
            "name",
            "workspace",
            "state",
            "configuration_revision",
            "credential_revision",
            "created_at",
            "updated_at");
    assertThat(parent)
        .containsEntry("id", id)
        .containsEntry("organization_id", org)
        .containsEntry("provider", "OPENAI")
        .containsEntry("mode", "OPENAI_API_KEY")
        .containsEntry("name", "Completion")
        .containsEntry("workspace", null)
        .containsEntry("state", "ENABLED")
        .containsEntry("configuration_revision", 1L)
        .containsEntry("credential_revision", 1L);
    var version = persisted.get("provider_credential_versions").get(0);
    assertThat(version.keySet())
        .containsExactlyInAnyOrder(
            "organization_id",
            "connection_id",
            "provider",
            "credential_revision",
            "external_locator",
            "external_key_version",
            "hmac_key_version",
            "hmac_domain_version",
            "verifier",
            "created_at");
    assertThat(version)
        .containsEntry("organization_id", org)
        .containsEntry("connection_id", id)
        .containsEntry("provider", "OPENAI")
        .containsEntry("credential_revision", 1L)
        .containsEntry("external_locator", "opaque/test")
        .containsEntry("external_key_version", "external-v1")
        .containsEntry("hmac_key_version", "credential-v1")
        .containsEntry("hmac_domain_version", "v2")
        .containsEntry("verifier", credential.digest());
    var event = persisted.get("provider_connection_events").get(0);
    assertThat(event.keySet())
        .containsExactlyInAnyOrder(
            "id",
            "organization_id",
            "connection_id",
            "configuration_revision",
            "credential_revision",
            "kind",
            "provider",
            "mode",
            "name",
            "workspace",
            "state",
            "actor_id",
            "created_at");
    assertThat(event.get("id")).isInstanceOf(UUID.class);
    assertThat(event)
        .containsEntry("organization_id", org)
        .containsEntry("connection_id", id)
        .containsEntry("actor_id", actor)
        .containsEntry("kind", "CREATE")
        .containsEntry("configuration_revision", 1L)
        .containsEntry("credential_revision", 1L)
        .containsEntry("provider", "OPENAI")
        .containsEntry("mode", "OPENAI_API_KEY")
        .containsEntry("name", "Completion")
        .containsEntry("workspace", null)
        .containsEntry("state", "ENABLED");
    assertThat(event.get("created_at"))
        .isEqualTo(parent.get("created_at"))
        .isEqualTo(parent.get("updated_at"));
    assertThat(version.get("created_at")).isEqualTo(parent.get("created_at"));
    var receipt = persisted.get("provider_command_receipts").get(0);
    assertThat(receipt.keySet())
        .containsExactlyInAnyOrder(
            "organization_id",
            "actor_id",
            "idempotency_key",
            "command_id",
            "operation",
            "connection_id",
            "before_event_id",
            "after_event_id",
            "creation_event_id",
            "digest_version",
            "key_version",
            "command_digest",
            "recorded_at");
    assertThat(receipt)
        .containsEntry("organization_id", org)
        .containsEntry("actor_id", actor)
        .containsEntry("connection_id", id)
        .containsEntry("idempotency_key", key)
        .containsEntry("operation", "CREATE")
        .containsEntry("before_event_id", null)
        .containsEntry("after_event_id", event.get("id"))
        .containsEntry("creation_event_id", event.get("id"))
        .containsEntry("digest_version", prepared.digestVersion())
        .containsEntry("key_version", prepared.keyVersion())
        .containsEntry("command_digest", prepared.digest());
    assertThat(receipt.get("command_id")).isInstanceOf(UUID.class);
    assertThat(receipt.get("recorded_at")).isNotNull();
  }

  void cleanThread() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(TransactionSynchronizationManager.hasResource(jdbc.getDataSource())).isFalse();
  }

  @AfterEach
  void cleanup() {
    cleanThread();
    for (String table :
        List.of(
            "provider_command_receipts",
            "provider_connection_events",
            "provider_connections",
            "provider_credential_versions",
            "organization_memberships"))
      jdbc.update("delete from " + table + " where organization_id=?", org);
    assertThat(jdbc.update("delete from human_accounts where id=?", actor)).isEqualTo(1);
    assertThat(jdbc.update("delete from organizations where id=?", org)).isEqualTo(1);
  }

  @Test
  void normalCommitReplayAndExplicitLookup() {
    var result = execute(healthy);
    assertThat(result.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(result.result().replayed()).isFalse();
    one();
    var before = rows();
    var replay = execute(healthy);
    assertThat(replay.state()).isEqualTo(WriteState.COMMITTED);
    assertThat(replay.result().replayed()).isTrue();
    assertThat(replay.result().receipt()).isEqualTo(result.result().receipt());
    assertThat(healthy.lookupOwn(human, org, key).receipt()).isEqualTo(result.result().receipt());
    assertThat(rows()).isEqualTo(before);
  }

  enum Fault {
    COMMIT_THEN_THROW,
    ROLLBACK_THEN_THROW,
    AFTER_COMMIT_THROW,
    MISSING_ACK,
    BAD_ACK,
    ROLLBACK_ACK_THROW,
    BEGIN_THROW,
    NORMAL_ROLLBACK,
    CONTRADICTORY_ACK,
    ROLLBACK_MISSING_ACK,
    MALFORMED_THEN_COMMITTED,
    MALFORMED_THEN_ROLLED_BACK,
    INCOMPLETE_STATUS,
    RECEIPT_FAILURE_AFTER_COMMIT
  }

  class FaultManager implements PlatformTransactionManager {
    final Fault fault;
    int begins, commits, rollbacks;
    TransactionStatus delegated;

    FaultManager(Fault fault) {
      this.fault = fault;
    }

    public TransactionStatus getTransaction(TransactionDefinition definition) {
      begins++;
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(
              definition.isReadOnly()
                  ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                  : TransactionDefinition.ISOLATION_READ_COMMITTED);
      if (fault == Fault.BEGIN_THROW) throw new IllegalStateException(SENTINEL);
      var status = manager.getTransaction(definition);
      delegated = status;
      assertThat(status.isNewTransaction()).isTrue();
      if (fault == Fault.AFTER_COMMIT_THROW)
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                throw new IllegalStateException(SENTINEL);
              }
            });
      if (fault == Fault.INCOMPLETE_STATUS)
        return (TransactionStatus)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TransactionStatus.class},
                (proxy, method, args) ->
                    method.getName().equals("isCompleted") ? false : method.invoke(status, args));
      return status;
    }

    private void replaceAcknowledgements(int... statuses) {
      var syncs = TransactionSynchronizationManager.getSynchronizations();
      TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.initSynchronization();
      for (var synchronization : syncs)
        for (int status : statuses) synchronization.afterCompletion(status);
    }

    public void commit(TransactionStatus status) {
      commits++;
      assertThat(status.isNewTransaction()).isTrue();
      switch (fault) {
        case MISSING_ACK -> replaceAcknowledgements();
        case BAD_ACK -> replaceAcknowledgements(TransactionSynchronization.STATUS_UNKNOWN);
        case CONTRADICTORY_ACK ->
            replaceAcknowledgements(
                TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_ROLLED_BACK);
        case MALFORMED_THEN_COMMITTED ->
            replaceAcknowledgements(-1, TransactionSynchronization.STATUS_COMMITTED);
        case MALFORMED_THEN_ROLLED_BACK ->
            replaceAcknowledgements(-1, TransactionSynchronization.STATUS_ROLLED_BACK);
        default -> {}
      }
      if (fault == Fault.ROLLBACK_THEN_THROW
          || fault == Fault.NORMAL_ROLLBACK
          || fault == Fault.MALFORMED_THEN_ROLLED_BACK) manager.rollback(delegated);
      else manager.commit(delegated);
      if (fault == Fault.RECEIPT_FAILURE_AFTER_COMMIT)
        throw new ProviderReceiptFailure(401, "UNAUTHORIZED");
      if (fault == Fault.COMMIT_THEN_THROW || fault == Fault.ROLLBACK_THEN_THROW)
        throw new IllegalStateException(SENTINEL);
    }

    public void rollback(TransactionStatus status) {
      rollbacks++;
      if (fault == Fault.ROLLBACK_MISSING_ACK) replaceAcknowledgements();
      if (fault == Fault.MALFORMED_THEN_ROLLED_BACK)
        replaceAcknowledgements(-1, TransactionSynchronization.STATUS_ROLLED_BACK);
      manager.rollback(delegated);
      if (fault == Fault.ROLLBACK_ACK_THROW) throw new IllegalStateException(SENTINEL);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Fault.class,
      names = {
        "COMMIT_THEN_THROW",
        "ROLLBACK_THEN_THROW",
        "AFTER_COMMIT_THROW",
        "MISSING_ACK",
        "BAD_ACK",
        "NORMAL_ROLLBACK",
        "CONTRADICTORY_ACK",
        "MALFORMED_THEN_COMMITTED",
        "MALFORMED_THEN_ROLLED_BACK",
        "INCOMPLETE_STATUS",
        "RECEIPT_FAILURE_AFTER_COMMIT"
      })
  void uncertainWriteNeverRetriesAndHealthyLookupRecovers(Fault fault) {
    var tm = new FaultManager(fault);
    var outcome = execute(boundary(tm));
    assertThat(outcome.state()).isEqualTo(WriteState.UNKNOWN);
    assertThat(outcome.result()).isNull();
    assertThat(outcome.failure().code()).isEqualTo(Code.COMMAND_OUTCOME_UNKNOWN);
    assertThat(outcome.toString() + outcome.failure()).doesNotContain(SENTINEL);
    assertThat(tm.begins).isEqualTo(1);
    assertThat(tm.commits).isEqualTo(1);
    assertThat(tm.rollbacks).isZero();
    assertThat(executeCalls).isEqualTo(1);
    assertThat(lookupCalls).isZero();
    cleanThread();
    var lookup = healthy.lookupOwn(human, org, key);
    assertThat(lookup.state())
        .isEqualTo(
            (fault == Fault.ROLLBACK_THEN_THROW
                    || fault == Fault.NORMAL_ROLLBACK
                    || fault == Fault.MALFORMED_THEN_ROLLED_BACK)
                ? LookupState.ABSENT
                : LookupState.FOUND);
    if (fault == Fault.ROLLBACK_THEN_THROW
        || fault == Fault.NORMAL_ROLLBACK
        || fault == Fault.MALFORMED_THEN_ROLLED_BACK) empty();
    else one();
    assertThat(tm.begins).isEqualTo(1);
  }

  @Test
  void knownBodyFailureAndRollbackAcknowledgementFailure() {
    var outcome = healthy.executePrepared(null, org, key, command, prepared, id, credential);
    assertThat(outcome.state()).isEqualTo(WriteState.REJECTED);
    assertThat(outcome.failure().code()).isEqualTo(Code.UNAUTHORIZED);
    var tm = new FaultManager(Fault.ROLLBACK_ACK_THROW);
    var unknown = boundary(tm).executePrepared(null, org, key, command, prepared, id, credential);
    assertThat(unknown.state()).isEqualTo(WriteState.UNKNOWN);
    assertThat(tm.rollbacks).isEqualTo(1);
    assertThat(tm.commits).isZero();
    empty();
  }

  @Test
  void readDenialsCorruptionAndFreshAuthority() {
    assertThat(healthy.lookupOwn(human, org, key).state()).isEqualTo(LookupState.ABSENT);
    assertThat(healthy.lookupOwn(null, org, key).failure().code()).isEqualTo(Code.UNAUTHORIZED);
    assertThat(healthy.lookupOwn(human, org, new UUID(0, 0)).failure().code())
        .isEqualTo(Code.INVALID_KEY);
    execute(healthy);
    var before = rows();
    jdbc.update("update human_accounts set credential_version=1 where id=?", actor);
    assertThat(healthy.lookupOwn(human, org, key).state()).isEqualTo(LookupState.REJECTED);
    jdbc.update("update human_accounts set credential_version=0 where id=?", actor);
    jdbc.update(
        "update provider_command_receipts set key_version='bad space' where organization_id=?",
        org);
    var corrupt = healthy.lookupOwn(human, org, key);
    assertThat(corrupt.state()).isEqualTo(LookupState.UNAVAILABLE);
    assertThat(corrupt.receipt()).isNull();
    assertThat(before.get("provider_connections")).isEqualTo(rows().get("provider_connections"));
  }

  @ParameterizedTest
  @EnumSource(
      value = Fault.class,
      names = {
        "COMMIT_THEN_THROW", "ROLLBACK_THEN_THROW", "AFTER_COMMIT_THROW", "MISSING_ACK",
        "BAD_ACK", "NORMAL_ROLLBACK", "CONTRADICTORY_ACK", "MALFORMED_THEN_COMMITTED",
        "MALFORMED_THEN_ROLLED_BACK", "INCOMPLETE_STATUS", "RECEIPT_FAILURE_AFTER_COMMIT"
      })
  void readCompletionDiscardsFoundAndAbsent(Fault fault) {
    for (boolean found : List.of(false, true)) {
      ProviderReceiptService.Receipt historical = null;
      if (found) {
        historical = execute(healthy).result().receipt();
      }
      var before = rows();
      int executions = executeCalls,
          lookups = lookupCalls,
          keysBefore = keyCalls,
          storesBefore = stores;
      var tm = new FaultManager(fault);
      var outcome = boundary(tm).lookupOwn(human, org, key);
      assertThat(outcome.state()).isEqualTo(LookupState.UNAVAILABLE);
      assertThat(outcome.receipt()).isNull();
      assertThat(outcome.failure().code()).isEqualTo(Code.LOOKUP_UNAVAILABLE);
      assertThat(tm.begins).isEqualTo(1);
      assertThat(tm.commits).isEqualTo(1);
      assertThat(tm.rollbacks).isZero();
      assertThat(executeCalls).isEqualTo(executions);
      assertThat(lookupCalls).isEqualTo(lookups + 1);
      assertThat(keyCalls).isEqualTo(keysBefore);
      assertThat(stores).isEqualTo(storesBefore);
      assertThat(rows()).isEqualTo(before);
      cleanThread();
      var recovery = healthy.lookupOwn(human, org, key);
      assertThat(recovery.state()).isEqualTo(found ? LookupState.FOUND : LookupState.ABSENT);
      assertThat(recovery.receipt()).isEqualTo(historical);
      assertThat(tm.begins).isEqualTo(1);
    }
  }

  @Test
  void malformedThenRollbackCannotConfirmBodyRejection() {
    var writeManager = new FaultManager(Fault.MALFORMED_THEN_ROLLED_BACK);
    var write =
        boundary(writeManager).executePrepared(null, org, key, command, prepared, id, credential);
    assertThat(write.state()).isEqualTo(WriteState.UNKNOWN);
    assertThat(write.result()).isNull();
    assertThat(write.failure().code()).isEqualTo(Code.COMMAND_OUTCOME_UNKNOWN);
    assertThat(writeManager.begins).isEqualTo(1);
    assertThat(writeManager.commits).isZero();
    assertThat(writeManager.rollbacks).isEqualTo(1);
    cleanThread();
    var readManager = new FaultManager(Fault.MALFORMED_THEN_ROLLED_BACK);
    var read = boundary(readManager).lookupOwn(null, org, key);
    assertThat(read.state()).isEqualTo(LookupState.UNAVAILABLE);
    assertThat(read.receipt()).isNull();
    assertThat(read.failure().code()).isEqualTo(Code.LOOKUP_UNAVAILABLE);
    assertThat(readManager.begins).isEqualTo(1);
    assertThat(readManager.commits).isZero();
    assertThat(readManager.rollbacks).isEqualTo(1);
    empty();
    cleanThread();
  }

  @Test
  void recorderRegistrationFailureCannotFabricateAcknowledgement() {
    try (var ignored =
        Mockito.mockStatic(
            TransactionSynchronizationManager.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("registerSynchronization")) {
                Object synchronization = invocation.getArgument(0);
                if (synchronization != null
                    && synchronization.getClass().getEnclosingClass()
                        == ProviderCompletionBoundary.class)
                  throw new IllegalStateException(SENTINEL);
              }
              return invocation.callRealMethod();
            })) {
      var writeManager = new FaultManager(Fault.MISSING_ACK);
      assertThat(execute(boundary(writeManager)).state()).isEqualTo(WriteState.UNKNOWN);
      assertThat(writeManager.begins).isEqualTo(1);
      assertThat(writeManager.commits).isZero();
      assertThat(writeManager.rollbacks).isEqualTo(1);
      cleanThread();
      var readManager = new FaultManager(Fault.MISSING_ACK);
      assertThat(boundary(readManager).lookupOwn(human, org, key).state())
          .isEqualTo(LookupState.UNAVAILABLE);
      assertThat(readManager.begins).isEqualTo(1);
      assertThat(readManager.commits).isZero();
      assertThat(readManager.rollbacks).isEqualTo(1);
      assertThat(executeCalls).isZero();
      assertThat(lookupCalls).isZero();
      empty();
      cleanThread();
    }
  }

  @Test
  void ambientTransactionsPreserveCallerMarker() {
    for (boolean readOnly : List.of(false, true)) {
      var template = new TransactionTemplate(manager);
      template.setReadOnly(readOnly);
      template.executeWithoutResult(
          status -> {
            if (!readOnly)
              jdbc.update("update organizations set name='caller-marker' where id=?", org);
            else
              assertThat(
                      jdbc.queryForObject(
                          "select name from organizations where id=?", String.class, org))
                  .isEqualTo("caller-marker");
            var tm = new FaultManager(Fault.BEGIN_THROW);
            var boundary = boundary(tm);
            assertThat(execute(boundary).state()).isEqualTo(WriteState.UNAVAILABLE);
            assertThat(boundary.lookupOwn(human, org, key).state())
                .isEqualTo(LookupState.UNAVAILABLE);
            assertThat(tm.begins).isZero();
            assertThat(status.isRollbackOnly()).isFalse();
          });
      assertThat(
              jdbc.queryForObject("select name from organizations where id=?", String.class, org))
          .isEqualTo("caller-marker");
    }
    empty();
  }

  @Test
  void synchronizationAndBoundResourceRefusedWithoutTouchingOwner() {
    var tm = new FaultManager(Fault.BEGIN_THROW);
    var boundary = boundary(tm);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(execute(boundary).state()).isEqualTo(WriteState.UNAVAILABLE);
      assertThat(boundary.lookupOwn(human, org, key).state()).isEqualTo(LookupState.UNAVAILABLE);
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    Object marker = new Object();
    TransactionSynchronizationManager.bindResource(jdbc.getDataSource(), marker);
    try {
      assertThat(execute(boundary).state()).isEqualTo(WriteState.UNAVAILABLE);
      assertThat(boundary.lookupOwn(human, org, key).state()).isEqualTo(LookupState.UNAVAILABLE);
      assertThat(TransactionSynchronizationManager.getResource(jdbc.getDataSource()))
          .isSameAs(marker);
    } finally {
      assertThat(TransactionSynchronizationManager.unbindResource(jdbc.getDataSource()))
          .isSameAs(marker);
    }
    assertThat(tm.begins).isZero();
    assertThat(executeCalls).isZero();
    assertThat(lookupCalls).isZero();
    empty();
  }

  ProviderReceiptService bodyProxy(RuntimeException failure, boolean afterBody) {
    var factory = new ProxyFactory(receipts);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        (MethodInterceptor)
            invocation -> {
              if (invocation.getMethod().getName().equals("execute")
                  || invocation.getMethod().getName().equals("findOwn")) {
                if (afterBody) {
                  invocation.proceed();
                  jdbc.update("update organizations set name='owned-marker' where id=?", org);
                }
                throw failure;
              }
              return invocation.proceed();
            });
    return (ProviderReceiptService) factory.getProxy();
  }

  @ParameterizedTest
  @EnumSource(Code.class)
  void exactBodyAllowlistAndLookupDenials(Code code) {
    var proxy = bodyProxy(new ProviderReceiptFailure(code.status(), code.name()), false);
    var boundary = new ProviderCompletionBoundary(manager, jdbc, proxy);
    var outcome = execute(boundary);
    assertThat(outcome.state()).isEqualTo(WriteState.REJECTED);
    boolean receiptCode =
        code != Code.COMMAND_UNAVAILABLE
            && code != Code.COMMAND_OUTCOME_UNKNOWN
            && code != Code.LOOKUP_UNAVAILABLE;
    assertThat(outcome.failure().code()).isEqualTo(receiptCode ? code : Code.COMMAND_UNAVAILABLE);
    var lookup = boundary.lookupOwn(human, org, key);
    boolean denied =
        code == Code.UNAUTHORIZED || code == Code.FORBIDDEN || code == Code.INVALID_KEY;
    assertThat(lookup.state()).isEqualTo(denied ? LookupState.REJECTED : LookupState.UNAVAILABLE);
    assertThat(lookup.failure().code()).isEqualTo(denied ? code : Code.LOOKUP_UNAVAILABLE);
    empty();
  }

  @Test
  void genericAndForgedFailuresRollbackAllRowsAndOwnedMarker() {
    for (RuntimeException failure :
        List.of(
            new IllegalStateException(SENTINEL),
            new ProviderReceiptFailure(401, SENTINEL),
            new ProviderReceiptFailure(500, "UNAUTHORIZED"))) {
      var before = rows();
      var boundary = new ProviderCompletionBoundary(manager, jdbc, bodyProxy(failure, true));
      var outcome = execute(boundary);
      assertThat(outcome.state()).isEqualTo(WriteState.REJECTED);
      assertThat(outcome.failure().code()).isEqualTo(Code.COMMAND_UNAVAILABLE);
      assertThat(outcome.failure().status()).isEqualTo(503);
      assertThat(outcome.toString() + outcome.failure()).doesNotContain(SENTINEL);
      assertThat(rows()).isEqualTo(before);
      assertThat(
              jdbc.queryForObject("select name from organizations where id=?", String.class, org))
          .isEqualTo("Completion");
    }
    execute(healthy);
    var committed = rows();
    var boundary =
        new ProviderCompletionBoundary(
            manager, jdbc, bodyProxy(new IllegalStateException(SENTINEL), true));
    assertThat(execute(boundary).state()).isEqualTo(WriteState.REJECTED);
    assertThat(rows()).isEqualTo(committed);
  }

  @Test
  void receiptFailureFromCompletionIsNeverBodyRejection() {
    PlatformTransactionManager tm =
        new PlatformTransactionManager() {
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return manager.getTransaction(definition);
          }

          public void commit(TransactionStatus status) {
            manager.commit(status);
            throw new ProviderReceiptFailure(401, "UNAUTHORIZED");
          }

          public void rollback(TransactionStatus status) {
            throw new AssertionError("No rollback after commit started");
          }
        };
    assertThat(execute(boundary(tm)).state()).isEqualTo(WriteState.UNKNOWN);
    one();
    assertThat(healthy.lookupOwn(human, org, key).state()).isEqualTo(LookupState.FOUND);
  }

  enum Context {
    NULL_STATUS,
    UNOWNED,
    NO_SYNC,
    WRONG_ISOLATION,
    WRONG_READ_ONLY,
    WRONG_RESOURCE,
    INACTIVE,
    INCOMPLETE
  }

  @ParameterizedTest
  @EnumSource(Context.class)
  void ownedContextValidationAndUnownedRefusal(Context context) {
    int[] calls = new int[3];
    PlatformTransactionManager tm =
        new PlatformTransactionManager() {
          TransactionStatus real;
          Object resource;

          public TransactionStatus getTransaction(TransactionDefinition definition) {
            calls[0]++;
            if (context == Context.NULL_STATUS) return null;
            if (context == Context.UNOWNED) return new SimpleTransactionStatus(false);
            real = manager.getTransaction(definition);
            switch (context) {
              case NO_SYNC -> TransactionSynchronizationManager.clearSynchronization();
              case WRONG_ISOLATION -> {
                try {
                  ((org.springframework.jdbc.datasource.ConnectionHolder)
                          TransactionSynchronizationManager.getResource(jdbc.getDataSource()))
                      .getConnection()
                      .setTransactionIsolation(TransactionDefinition.ISOLATION_SERIALIZABLE);
                } catch (java.sql.SQLException failure) {
                  throw new AssertionError(failure);
                }
              }
              case WRONG_READ_ONLY ->
                  TransactionSynchronizationManager.setCurrentTransactionReadOnly(
                      !definition.isReadOnly());
              case WRONG_RESOURCE -> {
                resource = TransactionSynchronizationManager.unbindResource(jdbc.getDataSource());
              }
              case INACTIVE -> TransactionSynchronizationManager.setActualTransactionActive(false);
              default -> {}
            }
            if (context == Context.INCOMPLETE)
              return (TransactionStatus)
                  Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {TransactionStatus.class},
                      (proxy, method, args) ->
                          method.getName().equals("isCompleted")
                              ? false
                              : method.invoke(real, args));
            return real;
          }

          void restore() {
            if (context == Context.NO_SYNC) TransactionSynchronizationManager.initSynchronization();
            if (resource != null)
              TransactionSynchronizationManager.bindResource(jdbc.getDataSource(), resource);
          }

          public void commit(TransactionStatus status) {
            calls[1]++;
            restore();
            manager.commit(real);
          }

          public void rollback(TransactionStatus status) {
            calls[2]++;
            restore();
            manager.rollback(real);
          }
        };
    var outcome = execute(boundary(tm));
    assertThat(outcome.state())
        .isEqualTo(
            context == Context.NO_SYNC || context == Context.INCOMPLETE
                ? WriteState.UNKNOWN
                : WriteState.UNAVAILABLE);
    assertThat(calls[0]).isEqualTo(1);
    assertThat(calls[1]).isEqualTo(context == Context.INCOMPLETE ? 1 : 0);
    assertThat(calls[2])
        .isEqualTo(
            context == Context.NULL_STATUS
                    || context == Context.UNOWNED
                    || context == Context.INCOMPLETE
                ? 0
                : 1);
    if (context == Context.INCOMPLETE) one();
    else empty();
  }

  @Test
  void rootSerializationInvariantsAndConfigurationAreRedacted() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var outcome = execute(healthy);
    String json = mapper.writeValueAsString(outcome);
    assertThat(json).doesNotContain("metadata", "digest", "keyVersion", "opaque/", SENTINEL);
    assertThat(mapper.writeValueAsString(healthy.lookupOwn(human, org, key)))
        .doesNotContain("metadata", "digest", "keyVersion", "opaque/", SENTINEL);
    for (WriteState state : WriteState.values()) {
      assertThatThrownBy(() -> new ProviderCompletionBoundary.WriteOutcome(state, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("COMPLETION_BOUNDARY_INVALID");
    }
    for (LookupState state : LookupState.values()) {
      if (state != LookupState.ABSENT)
        assertThatThrownBy(() -> new ProviderCompletionBoundary.LookupOutcome(state, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ProviderCompletionBoundary(null, jdbc, receipts))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("COMPLETION_BOUNDARY_INVALID");
  }

  @Test
  void malformedWriteResultsCannotPublishCommittedOrTriggerSecondCompletion() {
    for (boolean nullResult : List.of(true, false)) {
      var factory = new ProxyFactory(receipts);
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          (MethodInterceptor)
              invocation -> {
                if (invocation.getMethod().getName().equals("execute")) {
                  invocation.proceed();
                  return nullResult ? null : new ProviderReceiptService.Result(null, false);
                }
                return invocation.proceed();
              });
      var tm =
          new FaultManager(Fault.BEGIN_THROW) {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
              begins++;
              delegated = manager.getTransaction(definition);
              return delegated;
            }
          };
      var boundary =
          new ProviderCompletionBoundary(tm, jdbc, (ProviderReceiptService) factory.getProxy());
      var outcome = execute(boundary);
      assertThat(outcome.state()).isEqualTo(WriteState.UNKNOWN);
      assertThat(outcome.result()).isNull();
      assertThat(tm.begins).isEqualTo(1);
      assertThat(tm.commits).isEqualTo(1);
      assertThat(tm.rollbacks).isZero();
      one();
      assertThat(healthy.lookupOwn(human, org, key).state()).isEqualTo(LookupState.FOUND);
    }
    assertThatThrownBy(
            () ->
                new ProviderCompletionBoundary.WriteOutcome(
                    WriteState.COMMITTED, new ProviderReceiptService.Result(null, false), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roleLossOtherActorAndForeignOrganizationDoNotReleaseReceipt() {
    execute(healthy);
    var before = rows();
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Other','unused')",
        other,
        other + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        other,
        org);
    try {
      var otherHuman = new HumanIdentity(other, "other@example.test", "Other", 0);
      assertThat(healthy.lookupOwn(otherHuman, org, key).state()).isEqualTo(LookupState.ABSENT);
      assertThat(healthy.lookupOwn(human, UUID.randomUUID(), key).state())
          .isEqualTo(LookupState.REJECTED);
      jdbc.update(
          "delete from organization_memberships where account_id=? and organization_id=?",
          actor,
          org);
      assertThat(healthy.lookupOwn(human, org, key).state()).isEqualTo(LookupState.REJECTED);
      assertThat(rows()).isEqualTo(before);
    } finally {
      jdbc.update(
          "delete from organization_memberships where account_id=? and organization_id=?",
          other,
          org);
      assertThat(jdbc.update("delete from human_accounts where id=?", other)).isEqualTo(1);
    }
  }

  @Test
  void beginFailureAndMissingRollbackAcknowledgementCannotFabricateCertainty() {
    var begin = new FaultManager(Fault.BEGIN_THROW);
    assertThat(execute(boundary(begin)).state()).isEqualTo(WriteState.UNAVAILABLE);
    assertThat(begin.begins).isEqualTo(1);
    assertThat(begin.commits).isZero();
    assertThat(begin.rollbacks).isZero();
    var rollback = new FaultManager(Fault.ROLLBACK_MISSING_ACK);
    var boundary = boundary(rollback);
    assertThat(boundary.executePrepared(null, org, key, command, prepared, id, credential).state())
        .isEqualTo(WriteState.UNKNOWN);
    assertThat(rollback.rollbacks).isEqualTo(1);
    assertThat(rollback.commits).isZero();
    empty();
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void boundaryProducesNoDiagnosticLogsOrRetainedFailure(CapturedOutput output) {
    var boundary =
        new ProviderCompletionBoundary(
            manager, jdbc, bodyProxy(new IllegalStateException(SENTINEL), false));
    var write = execute(boundary);
    var read = boundary.lookupOwn(human, org, key);
    assertThat(output.getAll()).doesNotContain(SENTINEL);
    assertThat(write.toString() + write.failure() + read.toString() + read.failure())
        .doesNotContain(SENTINEL);
    assertThat(write.result()).isNull();
    assertThat(read.receipt()).isNull();
    assertThat(ProviderCompletionBoundary.Failure.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("code");
  }

  @Test
  void differentManagersResourceNeverEstablishesExpectedJdbcContext() {
    var otherDataSource =
        new org.springframework.jdbc.datasource.AbstractDataSource() {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            return jdbc.getDataSource().getConnection();
          }

          @Override
          public java.sql.Connection getConnection(String username, String password)
              throws java.sql.SQLException {
            return jdbc.getDataSource().getConnection(username, password);
          }
        };
    var otherManager =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(otherDataSource);
    var boundary = boundary(otherManager);
    assertThat(execute(boundary).state()).isEqualTo(WriteState.UNAVAILABLE);
    assertThat(boundary.lookupOwn(human, org, key).state()).isEqualTo(LookupState.UNAVAILABLE);
    assertThat(TransactionSynchronizationManager.hasResource(otherDataSource)).isFalse();
    assertThat(executeCalls).isZero();
    assertThat(lookupCalls).isZero();
    empty();
  }
}

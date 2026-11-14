package com.agentos.providerreceipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;

import com.agentos.auth.HumanIdentity;
import com.agentos.providerchecks.ProviderAccessCheckDispatch;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Command;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialCommand;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercommands.CommandBoundary.Rotate;
import com.agentos.providercommands.CommandBoundary.Scope;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared cases exercise actual transactional proxies and Flyway schemas on both databases. */
abstract class ProviderReceiptFixture {
  @org.springframework.boot.test.mock.mockito.SpyBean JdbcTemplate jdbc;
  @Autowired ProviderReceiptService service;
  @Autowired ProviderConnectionService connections;
  @Autowired PlatformTransactionManager transactions;
  UUID org, actor, id, key;
  HumanIdentity human;
  final List<UUID> extraActors = new ArrayList<>();
  int keyCalls, stores;
  static final String SECRET = "receipt-plaintext-sentinel-918573";
  final CommandBoundary boundary =
      new CommandBoundary(
          new CommandBoundary.Keys() {
            public String currentVersion() {
              outside();
              keyCalls++;
              return "command-v1";
            }

            public java.security.Key key(String version) {
              outside();
              keyCalls++;
              return new SecretKeySpec(new byte[32], "HmacSHA256");
            }
          });

  static void outside() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @BeforeEach
  void seed() {
    org = UUID.randomUUID();
    actor = UUID.randomUUID();
    id = UUID.randomUUID();
    key = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Receipts',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    human = account(actor, "ADMIN");
    assertThat(AopUtils.isAopProxy(service)).isTrue();
    assertThat(AopUtils.isAopProxy(connections)).isTrue();
  }

  HumanIdentity account(UUID account, String role) {
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Receipts','unused')",
        account,
        account + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values (?,?,?)",
        account,
        org,
        role);
    if (!account.equals(actor)) extraActors.add(account);
    return new HumanIdentity(account, "unused@example.test", "Receipts", 0);
  }

  @AfterEach
  void cleanup() {
    jdbc.update("delete from provider_access_check_events where organization_id=?", org);
    jdbc.update("delete from provider_access_check_receipts where organization_id=?", org);
    jdbc.update("delete from provider_access_check_outbox where organization_id=?", org);
    jdbc.update("delete from provider_access_checks where organization_id=?", org);
    jdbc.update("delete from provider_command_receipts where organization_id=?", org);
    jdbc.update("delete from provider_connection_events where organization_id=?", org);
    jdbc.update("delete from provider_connections where organization_id=?", org);
    jdbc.update("delete from provider_credential_versions where organization_id=?", org);
    jdbc.update("delete from organization_memberships where organization_id=?", org);
    for (UUID extra : extraActors) jdbc.update("delete from human_accounts where id=?", extra);
    jdbc.update("delete from human_accounts where id=?", actor);
    jdbc.update("delete from organizations where id=?", org);
  }

  Create createCommand(Provider provider) {
    return new Create(
        provider,
        switch (provider) {
          case OPENAI -> CredentialMode.OPENAI_API_KEY;
          case ANTHROPIC -> CredentialMode.ANTHROPIC_API_KEY;
          case GOOGLE_GEMINI -> CredentialMode.GEMINI_API_KEY;
        },
        "  Café 😀  ",
        provider == Provider.ANTHROPIC ? "wrkspc_abc123" : null);
  }

  Metadata prepare(Command command) {
    return boundary.prepareCurrent(
        new Scope(org, actor),
        command,
        command instanceof Create || command instanceof Rotate ? SECRET.toCharArray() : null);
  }

  CredentialBoundary.Metadata credential(Provider provider, long revision) {
    var b =
        new CredentialBoundary(
            new CredentialBoundary.Keys() {
              public String currentVersion() {
                outside();
                return "credential-v1";
              }

              public javax.crypto.SecretKey key(String version) {
                outside();
                return new SecretKeySpec(new byte[32], "HmacSHA256");
              }
            },
            new CredentialBoundary.Secrets() {
              public CredentialBoundary.Reference store(
                  CredentialBoundary.Scope scope, byte[] bytes) {
                outside();
                stores++;
                return new CredentialBoundary.Reference(
                    scope, "opaque/" + UUID.randomUUID(), "external-v1");
              }

              public byte[] read(CredentialBoundary.Reference reference) {
                throw new AssertionError("No secret reads");
              }

              public void preparationFailed(
                  CredentialBoundary.Scope scope, CredentialBoundary.Reference reference) {
                throw new AssertionError("Unexpected preparation failure");
              }
            });
    return b.prepare(
        new CredentialBoundary.Scope(
            org, id, CredentialBoundary.Provider.valueOf(provider.name()), revision),
        SECRET.toCharArray());
  }

  ProviderReceiptService.Result create() {
    Create c = createCommand(Provider.OPENAI);
    return service.execute(human, org, key, c, prepare(c), id, credential(Provider.OPENAI, 1));
  }

  void error(Runnable action, int status, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ProviderReceiptFailure.class,
            e -> {
              assertThat(e.status()).isEqualTo(status);
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.getCause()).isNull();
              assertThat(e.getSuppressed()).isEmpty();
              assertThat(e.getMessage()).isEqualTo(code);
            });
  }

  Map<String, List<Map<String, Object>>> snapshot() {
    Map<String, List<Map<String, Object>>> all = new TreeMap<>();
    for (String table :
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema=current_schema()"
                + " and table_type='BASE TABLE' order by table_name",
            String.class)) {
      assertThat(table).matches("[a-z_0-9]+");
      var rows = new ArrayList<>(jdbc.queryForList("select * from " + table));
      rows.forEach(
          row ->
              row.replaceAll(
                  (k, v) -> v instanceof byte[] b ? Base64.getEncoder().encodeToString(b) : v));
      rows.sort(Comparator.comparing(Object::toString));
      all.put(table, rows);
    }
    return all;
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  TransactionTemplate write() {
    var tx = new TransactionTemplate(transactions);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  TransactionTemplate read() {
    var tx = new TransactionTemplate(transactions);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setReadOnly(true);
    return tx;
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void completeRowsAllOperationsAndImmutableHistory(Provider provider) {
    Create command = createCommand(provider);
    Metadata proof = prepare(command);
    var collateral = snapshot();
    var result = service.execute(human, org, key, command, proof, id, credential(provider, 1));
    var first = result.receipt();
    assertThat(result.replayed()).isFalse();
    assertThat(first.before()).isNull();
    var a = first.after();
    assertThat(a.organizationId()).isEqualTo(org);
    assertThat(a.id()).isEqualTo(id);
    assertThat(a.provider()).isEqualTo(provider);
    assertThat(a.credentialMode()).isEqualTo(command.credentialMode());
    assertThat(a.name()).isEqualTo(command.name());
    assertThat(a.workspaceId()).isEqualTo(command.workspaceId());
    assertThat(a.state()).isEqualTo("ENABLED");
    assertThat(a.revision()).isEqualTo(1);
    assertThat(a.credentialRevision()).isEqualTo(1);
    assertThat(a.createdAt()).isEqualTo(a.updatedAt());
    assertThat(first.recordedAt()).isAfterOrEqualTo(a.updatedAt());
    assertThat(first.org()).isEqualTo(org);
    assertThat(first.actorId()).isEqualTo(actor);
    assertThat(first.connectionId()).isEqualTo(id);
    assertThat(first.operation()).isEqualTo(Operation.CREATE);
    inspectRow(key, proof, first);
    List<Command> changes =
        List.of(
            new Rename(id, 1, "Renamed"),
            new Rotate(id, 2),
            new RevisionCommand(Operation.DISABLE, id, 3),
            new RevisionCommand(Operation.ENABLE, id, 4));
    var previous = a;
    long expectedRevision = 1;
    for (Command c : changes) {
      UUID nextKey = UUID.randomUUID();
      Metadata m = prepare(c);
      var next =
          service.execute(
              human,
              org,
              nextKey,
              c,
              m,
              null,
              c instanceof Rotate ? credential(provider, 2) : null);
      assertThat(next.receipt().before()).isEqualTo(previous);
      expectedRevision++;
      OffsetDateTime eventTime =
          jdbc.queryForObject(
              "select created_at from provider_connection_events where organization_id=? and"
                  + " connection_id=? and configuration_revision=?",
              OffsetDateTime.class,
              org,
              id,
              expectedRevision);
      String expectedState = c.operation() == Operation.DISABLE ? "DISABLED" : "ENABLED";
      long expectedCredentialRevision = c.operation() == Operation.RENAME ? 1 : 2;
      var expected =
          new ProviderReceiptService.Configuration(
              org,
              id,
              provider,
              command.credentialMode(),
              "Renamed",
              command.workspaceId(),
              expectedState,
              expectedRevision,
              expectedCredentialRevision,
              a.createdAt(),
              eventTime);
      assertThat(next.receipt().after()).isEqualTo(expected);
      inspectRow(nextKey, m, next.receipt());
      previous = next.receipt().after();
    }
    assertThat(previous.name()).isEqualTo("Renamed");
    assertThat(previous.state()).isEqualTo("ENABLED");
    assertThat(previous.credentialRevision()).isEqualTo(2);
    var beforeReplay = snapshot();
    int calls = keyCalls, preparations = stores;
    assertThat(service.execute(human, org, key, command, proof, null, null))
        .isEqualTo(new ProviderReceiptService.Result(first, true));
    assertThat(service.findOwn(human, org, key).receipt()).isEqualTo(first);
    assertThat(service.probeOwn(human, org, key).metadata()).isEqualTo(proof);
    assertThat(keyCalls).isEqualTo(calls);
    assertThat(stores).isEqualTo(preparations);
    assertThat(snapshot()).isEqualTo(beforeReplay);
    assertThat(count("provider_command_receipts")).isEqualTo(5);
    assertThat(count("provider_connection_events")).isEqualTo(5);
    assertThat(count("provider_credential_versions")).isEqualTo(2);
    collateral.forEach(
        (table, rows) -> {
          if (!Set.of(
                  "provider_command_receipts",
                  "provider_connection_events",
                  "provider_connections",
                  "provider_credential_versions")
              .contains(table)) assertThat(snapshot().get(table)).as(table).isEqualTo(rows);
        });
    for (Class<?> type :
        List.of(
            ProviderReceiptService.Result.class,
            ProviderReceiptService.Receipt.class,
            ProviderReceiptService.Configuration.class,
            ProviderReceiptService.Probe.class))
      assertThat(type.isAnnotationPresent(JsonIgnoreType.class)).isTrue();
    assertThat(result.toString()).isEqualTo("Result[redacted]");
    assertThat(first.toString()).isEqualTo("Receipt[redacted]");
    assertThat(a.toString()).isEqualTo("Configuration[redacted]");
  }

  void inspectRow(UUID receiptKey, Metadata proof, ProviderReceiptService.Receipt r) {
    var row =
        jdbc.queryForMap(
            "select * from provider_command_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            org,
            actor,
            receiptKey);
    assertThat(row.keySet())
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
    assertThat(row.get("organization_id")).isEqualTo(org);
    assertThat(row.get("actor_id")).isEqualTo(actor);
    assertThat(row.get("idempotency_key")).isEqualTo(receiptKey);
    assertThat(row.get("command_id")).isEqualTo(r.commandId());
    assertThat(row.get("operation")).isEqualTo(r.operation().name());
    assertThat(row.get("connection_id")).isEqualTo(id);
    assertThat(row.get("digest_version")).isEqualTo(proof.digestVersion());
    assertThat(row.get("key_version")).isEqualTo(proof.keyVersion());
    assertThat(row.get("command_digest")).isEqualTo(proof.digest());
    OffsetDateTime storedRecordedAt =
        jdbc.queryForObject(
            "select recorded_at from provider_command_receipts where organization_id=? and"
                + " actor_id=? and idempotency_key=?",
            (rs, n) -> rs.getObject("recorded_at", OffsetDateTime.class),
            org,
            actor,
            receiptKey);
    assertThat(storedRecordedAt).isNotNull();
    assertThat(storedRecordedAt.toInstant()).isEqualTo(r.recordedAt().toInstant());
    var events =
        jdbc.queryForList(
            "select id from provider_connection_events where organization_id=? and connection_id=?"
                + " and configuration_revision=?",
            UUID.class,
            org,
            id,
            r.after().revision());
    assertThat(row.get("after_event_id")).isEqualTo(events.get(0));
    assertThat(row.get("creation_event_id"))
        .isEqualTo(
            jdbc.queryForObject(
                "select id from provider_connection_events where organization_id=? and"
                    + " connection_id=? and configuration_revision=1",
                UUID.class,
                org,
                id));
    assertThat(row.get("before_event_id"))
        .isEqualTo(
            r.before() == null
                ? null
                : jdbc.queryForObject(
                    "select id from provider_connection_events where organization_id=? and"
                        + " connection_id=? and configuration_revision=?",
                    UUID.class,
                    org,
                    id,
                    r.before().revision()));
    assertThat(row.toString()).doesNotContain(SECRET, "opaque/", "external-v1", "credential-v1");
    assertThat(service.findOwn(human, org, receiptKey).receipt()).isEqualTo(r);
  }

  @Test
  void replayPrecedesMutableGatesAndNeedsNoCredentialRead() {
    var original = create();
    var command = createCommand(Provider.OPENAI);
    var proof = prepare(command);
    jdbc.update(
        "update provider_credential_versions set verifier='malformed' where organization_id=?",
        org);
    var before = snapshot();
    assertThat(service.execute(human, org, key, command, proof, UUID.randomUUID(), null).receipt())
        .isEqualTo(original.receipt());
    assertThat(service.findOwn(human, org, key).receipt()).isEqualTo(original.receipt());
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void differentBodiesTargetsAndOperationsConflictBeforeResourceGates() {
    create();
    var before = snapshot();
    for (Command c :
        List.of(
            new Rename(UUID.randomUUID(), 0, "Other"),
            new Rotate(id, 0),
            new RevisionCommand(Operation.DISABLE, id, 0),
            new Create(Provider.OPENAI, CredentialMode.OPENAI_API_KEY, "Other", null))) {
      Metadata proof = prepare(c);
      error(
          () -> service.execute(human, org, key, c, proof, null, null),
          409,
          "IDEMPOTENCY_CONFLICT");
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void historicalKeyMismatchForcesOuterRollbackAndReprepareAfterRelease() {
    var original = create();
    var c = createCommand(Provider.OPENAI);
    Metadata current =
        boundary.prepareVersion(new Scope(org, actor), c, SECRET.toCharArray(), "command-v2");
    var before = snapshot();
    assertThatThrownBy(
            () ->
                write()
                    .executeWithoutResult(
                        status -> {
                          jdbc.update(
                              "update organizations set name='must roll back' where id=?", org);
                          error(
                              () -> service.execute(human, org, key, c, current, null, null),
                              409,
                              "PREPARATION_REQUIRED");
                        }))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThat(snapshot()).isEqualTo(before);
    var probe = service.probeOwn(human, org, key);
    Metadata historical =
        boundary.prepareVersion(
            new Scope(org, actor), c, SECRET.toCharArray(), probe.metadata().keyVersion());
    assertThat(service.execute(human, org, key, c, historical, null, null).receipt())
        .isEqualTo(original.receipt());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE", "REMOVED", "DISABLED", "VERSION"})
  void freshAuthorizationBeforeOwnReceiptAndAbsence(String guard) {
    create();
    switch (guard) {
      case "ROLE" ->
          jdbc.update(
              "update organization_memberships set role='MEMBER' where organization_id=? and"
                  + " account_id=?",
              org,
              actor);
      case "REMOVED" ->
          jdbc.update(
              "delete from organization_memberships where organization_id=? and account_id=?",
              org,
              actor);
      case "DISABLED" -> jdbc.update("update human_accounts set enabled=false where id=?", actor);
      default -> jdbc.update("update human_accounts set credential_version=1 where id=?", actor);
    }
    var c = createCommand(Provider.OPENAI);
    var m = prepare(c);
    var before = snapshot();
    error(() -> service.execute(human, org, key, c, m, null, null), 403, "FORBIDDEN");
    error(() -> service.findOwn(human, org, key), 403, "FORBIDDEN");
    error(() -> service.probeOwn(human, org, UUID.randomUUID()), 403, "FORBIDDEN");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void otherAdminHasOwnNamespaceAndForeignTenantIsForbidden() {
    create();
    HumanIdentity second = account(UUID.randomUUID(), "ADMIN");
    var before = snapshot();
    assertThat(service.findOwn(second, org, key)).isNull();
    assertThat(service.probeOwn(second, org, key)).isNull();
    error(() -> service.findOwn(human, UUID.randomUUID(), key), 403, "FORBIDDEN");
    error(() -> service.findOwn(null, org, key), 401, "UNAUTHORIZED");
    assertThat(snapshot()).isEqualTo(before);
    Command c = new Rename(id, 1, "Second admin");
    Metadata m = boundary.prepareCurrent(new Scope(org, second.id()), c, null);
    var r = service.execute(second, org, key, c, m, null, null);
    assertThat(r.receipt().actorId()).isEqualTo(second.id());
    assertThat(r.receipt().before().revision()).isEqualTo(1);
    assertThat(count("provider_command_receipts")).isEqualTo(2);
  }

  @Test
  void outerRollbackAndCaughtFailureRollBackEveryEffect() {
    var c = createCommand(Provider.OPENAI);
    var m = prepare(c);
    var credential = credential(Provider.OPENAI, 1);
    var before = snapshot();
    write()
        .executeWithoutResult(
            s -> {
              service.execute(human, org, key, c, m, id, credential);
              s.setRollbackOnly();
            });
    assertThat(snapshot()).isEqualTo(before);
    assertThatThrownBy(
            () ->
                write()
                    .executeWithoutResult(
                        s -> {
                          service.execute(human, org, key, c, m, id, credential);
                          error(
                              () ->
                                  service.execute(
                                      human, org, UUID.randomUUID(), null, m, null, null),
                              400,
                              "INVALID_COMMAND");
                        }))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void accessCheckPinsHistoricalReceiptAndRollsBackOnReceiptOrEventFailure() {
    boolean seededPolicy =
        jdbc.queryForObject("select count(*) from provider_deployment_policy", Long.class) == 0;
    if (seededPolicy)
      jdbc.update(
          """
          insert into provider_deployment_policy
            (id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)
          values (1,1,true,true,true,current_timestamp,current_timestamp)
          """);
    var policy = jdbc.queryForMap("select * from provider_deployment_policy where id=1");
    Provider provider =
        Boolean.TRUE.equals(policy.get("openai_enabled"))
            ? Provider.OPENAI
            : Boolean.TRUE.equals(policy.get("anthropic_enabled"))
                ? Provider.ANTHROPIC
                : Provider.GOOGLE_GEMINI;
    var create = createCommand(provider);
    var command = new CredentialCommand(Operation.ACCESS_CHECK, id, 1, 1);
    var proof = prepare(command);
    UUID checkKey = UUID.randomUUID();
    doReturn(Boolean.TRUE)
        .when(jdbc)
        .queryForObject(
            "select agentos_list_read.credential_available(?::uuid,?::uuid)",
            Boolean.class,
            org,
            id);
    try {
      service.execute(human, org, key, create, prepare(create), id, credential(provider, 1));
      jdbc.execute(
          "alter table provider_access_check_receipts add constraint test_reject_check"
              + " check (connection_id <> '"
              + id
              + "')");
      try {
        error(
            () -> service.acceptAccessCheck(human, org, checkKey, command, proof),
            500,
            "RECEIPT_UNAVAILABLE");
      } finally {
        jdbc.execute(
            "alter table provider_access_check_receipts drop constraint test_reject_check");
      }
      assertThat(count("provider_access_checks")).isZero();
      assertThat(count("provider_access_check_outbox")).isZero();
      assertThat(service.findOwn(human, org, checkKey)).isNull();
      assertThat(count("provider_access_check_events")).isZero();
      jdbc.execute(
          "alter table provider_access_check_events add constraint test_reject_check_event"
              + " check (connection_id <> '"
              + id
              + "')");
      try {
        error(
            () -> service.acceptAccessCheck(human, org, checkKey, command, proof),
            500,
            "RECEIPT_UNAVAILABLE");
      } finally {
        jdbc.execute(
            "alter table provider_access_check_events drop constraint test_reject_check_event");
      }
      assertThat(count("provider_access_checks")).isZero();
      assertThat(count("provider_access_check_outbox")).isZero();
      assertThat(count("provider_access_check_receipts")).isZero();
      assertThat(count("provider_access_check_events")).isZero();
      assertThat(service.findOwn(human, org, checkKey)).isNull();
      var accepted = service.acceptAccessCheck(human, org, checkKey, command, proof);
      assertThat(accepted.replayed()).isFalse();
      assertThat(accepted.receipt().checkId()).isNotNull();
      assertThat(accepted.receipt().before()).isEqualTo(accepted.receipt().after());
      assertThat(accepted.receipt().after().revision()).isEqualTo(1);
      error(
          () -> service.acceptAccessCheck(human, org, UUID.randomUUID(), command, proof),
          409,
          "CHECK_IN_PROGRESS");
      error(
          () -> service.acceptAccessCheck(human, org, key, command, proof),
          409,
          "IDEMPOTENCY_CONFLICT");
      var rename = new Rename(id, 1, "After check");
      var renameProof = prepare(rename);
      error(
          () -> service.execute(human, org, checkKey, rename, renameProof, null, null),
          409,
          "IDEMPOTENCY_CONFLICT");
      connections.rename(human, org, id, 1, "After check");
      assertThat(service.findOwn(human, org, checkKey).receipt()).isEqualTo(accepted.receipt());
      assertThat(service.acceptAccessCheck(human, org, checkKey, command, proof).replayed())
          .isTrue();
      assertThat(count("provider_access_checks")).isEqualTo(1);
      assertThat(count("provider_access_check_outbox")).isEqualTo(1);
      assertThat(count("provider_access_check_receipts")).isEqualTo(1);
      assertThat(count("provider_access_check_events")).isEqualTo(1);
      var event =
          jdbc.queryForMap(
              "select * from provider_access_check_events where check_id=?",
              accepted.receipt().checkId());
      assertThat(event)
          .containsEntry("organization_id", org)
          .containsEntry("connection_id", id)
          .containsEntry("actor_id", actor)
          .containsEntry("event_type", "ACCEPTED");

    } finally {
      if (seededPolicy) jdbc.update("delete from provider_deployment_policy where id=1");
    }
  }

  @Test
  void accessCheckDispatchClaimsOnceAndFencesAChangedActor() {
    boolean seeded =
        jdbc.queryForObject("select count(*) from provider_deployment_policy", Long.class) == 0;
    if (seeded)
      jdbc.update(
          """
          insert into provider_deployment_policy
            (id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)
          values (1,3,true,true,true,current_timestamp,current_timestamp)
          """);
    try {
      var policy = jdbc.queryForMap("select * from provider_deployment_policy where id=1");
      long policyRevision = ((Number) policy.get("revision")).longValue();
      Provider provider =
          Boolean.TRUE.equals(policy.get("openai_enabled"))
              ? Provider.OPENAI
              : Boolean.TRUE.equals(policy.get("anthropic_enabled"))
                  ? Provider.ANTHROPIC
                  : Provider.GOOGLE_GEMINI;
      var create = createCommand(provider);
      service.execute(human, org, key, create, prepare(create), id, credential(provider, 1));
      UUID check = UUID.randomUUID(), worker = UUID.randomUUID();
      var accepted = jdbc.queryForObject("select current_timestamp", OffsetDateTime.class);
      jdbc.update(
          """
          insert into provider_access_checks
            (id,organization_id,connection_id,actor_id,actor_credential_version,
             connection_revision,credential_revision,policy_revision,accepted_at,queue_expires_at)
          values (?,?,?,?,0,1,1,?,?,?)
          """,
          check,
          org,
          id,
          actor,
          policyRevision,
          accepted,
          accepted.plusSeconds(60));
      jdbc.update(
          "insert into provider_access_check_outbox values (?,?,?,1,1,?)",
          org,
          id,
          check,
          policyRevision);

      var dispatch = new ProviderAccessCheckDispatch(jdbc.getDataSource());
      var claim = dispatch.claim(org, check, worker);
      assertThat(claim).isNotNull();
      assertThat(dispatch.claim(org, check, UUID.randomUUID())).isNull();
      assertThat(dispatch.finish(claim, ProviderAccessCheckDispatch.Result.ACCESSIBLE)).isFalse();
      assertThat(dispatch.authorize(claim)).isNotNull();
      assertThat(dispatch.authorize(claim)).isNull();
      jdbc.update("update human_accounts set credential_version=1 where id=?", actor);
      assertThat(dispatch.finish(claim, ProviderAccessCheckDispatch.Result.ACCESSIBLE)).isTrue();
      assertThat(dispatch.finish(claim, ProviderAccessCheckDispatch.Result.UNKNOWN)).isFalse();
      assertThat(
              jdbc.queryForMap(
                  "select outcome,error_code from provider_access_checks where id=?", check))
          .containsEntry("outcome", "SUPERSEDED")
          .containsEntry("error_code", "AUTHORITY_LOST");
      assertThat(count("provider_access_check_outbox")).isZero();
      UUID expired = UUID.randomUUID();
      insertAccessCheck(expired, accepted.minusMinutes(2));
      jdbc.update(
          "insert into provider_access_check_outbox values (?,?,?,1,1,3)", org, id, expired);
      assertThat(dispatch.claim(org, expired, worker)).isNull();
      assertThat(
              jdbc.queryForMap(
                  "select outcome,error_code from provider_access_checks where id=?", expired))
          .containsEntry("outcome", "FAILED")
          .containsEntry("error_code", "CHECK_EXPIRED");
      assertThat(count("provider_access_check_outbox")).isZero();
    } finally {
      if (seeded) jdbc.update("delete from provider_deployment_policy where id=1");
    }
  }

  @Test
  void accessCheckMigrationBindsOutboxRevisionsAndRollsBackDuplicateDispatch() {
    create();
    UUID first = UUID.randomUUID();
    UUID duplicate = UUID.randomUUID();
    var accepted = OffsetDateTime.parse("2026-11-14T00:00:00Z");
    write()
        .executeWithoutResult(
            status -> {
              insertAccessCheck(first, accepted);
              jdbc.update(
                  "insert into provider_access_check_outbox values (?,?,?,1,1,3)", org, id, first);
            });

    assertThatThrownBy(
            () ->
                write()
                    .executeWithoutResult(
                        status -> {
                          insertAccessCheck(duplicate, accepted);
                          jdbc.update(
                              "insert into provider_access_check_outbox values (?,?,?,1,1,3)",
                              org,
                              id,
                              duplicate);
                        }))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update provider_access_check_outbox set policy_revision=4 where check_id=?",
                    first))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(count("provider_access_checks")).isEqualTo(1);
    assertThat(count("provider_access_check_outbox")).isEqualTo(1);
    assertThat(count("provider_connection_events")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select queue_expires_at from provider_access_checks where id=?",
                OffsetDateTime.class,
                first))
        .isEqualTo(accepted.plusSeconds(60));
  }

  private void insertAccessCheck(UUID check, OffsetDateTime accepted) {
    jdbc.update(
        """
        insert into provider_access_checks
          (id,organization_id,connection_id,actor_id,actor_credential_version,
           connection_revision,credential_revision,policy_revision,accepted_at,queue_expires_at)
        values (?,?,?,?,?,1,1,3,?,?)
        """,
        check,
        org,
        id,
        actor,
        human.credentialVersion(),
        accepted,
        accepted.plusSeconds(60));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void forcedReceiptInsertFailureRollsBackCreateAndRotate(boolean rotate) {
    if (rotate) create();
    Command c = rotate ? new Rotate(id, 1) : createCommand(Provider.OPENAI);
    Metadata m = prepare(c);
    var cred = credential(Provider.OPENAI, rotate ? 2 : 1);
    var before = snapshot();
    jdbc.execute(
        "alter table provider_command_receipts add constraint receipt_test_reject check"
            + " (operation<>'"
            + (rotate ? "ROTATE" : "CREATE")
            + "')");
    try {
      error(
          () -> service.execute(human, org, UUID.randomUUID(), c, m, rotate ? null : id, cred),
          500,
          "RECEIPT_UNAVAILABLE");
    } finally {
      jdbc.execute("alter table provider_command_receipts drop constraint receipt_test_reject");
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"key_version", "command_digest", "event_name", "creation", "pair", "recorded"})
  void malformedSelectedRowsFailClosed(String corruption) {
    create();
    Command c = new Rename(id, 1, "Next");
    UUID renameKey = UUID.randomUUID();
    service.execute(human, org, renameKey, c, prepare(c), null, null);
    switch (corruption) {
      case "key_version" ->
          jdbc.update(
              "update provider_command_receipts set key_version='bad space' where"
                  + " organization_id=?",
              org);
      case "command_digest" ->
          jdbc.update(
              "update provider_command_receipts set command_digest='bad' where organization_id=?",
              org);
      case "event_name" ->
          jdbc.update(
              "update provider_connection_events set name=' bad ' where organization_id=? and"
                  + " configuration_revision=2",
              org);
      case "creation" ->
          jdbc.update(
              "update provider_connection_events set name=' bad ' where organization_id=? and"
                  + " configuration_revision=1",
              org);
      case "pair" ->
          jdbc.update(
              "update provider_connection_events set name=? where organization_id=? and"
                  + " configuration_revision=2",
              createCommand(Provider.OPENAI).name(),
              org);
      default ->
          jdbc.update(
              "update provider_command_receipts set recorded_at=? where organization_id=?",
              OffsetDateTime.parse("2000-01-01T00:00:00Z"),
              org);
    }
    var before = snapshot();
    Metadata m = prepare(c);
    error(() -> service.findOwn(human, org, renameKey), 500, "RECEIPT_UNAVAILABLE");
    error(() -> service.probeOwn(human, org, renameKey), 500, "RECEIPT_UNAVAILABLE");
    error(
        () -> service.execute(human, org, renameKey, c, m, null, null), 500, "RECEIPT_UNAVAILABLE");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void incompatibleJoinedContextsAndMissingProxyFailWithoutWrites() {
    var c = createCommand(Provider.OPENAI);
    var m = prepare(c);
    var cred = credential(Provider.OPENAI, 1);
    var before = snapshot();
    assertThatThrownBy(
            () ->
                read()
                    .executeWithoutResult(
                        s ->
                            error(
                                () -> service.execute(human, org, key, c, m, id, cred),
                                500,
                                "RECEIPT_UNAVAILABLE")))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThatThrownBy(
            () ->
                write()
                    .executeWithoutResult(
                        s ->
                            error(
                                () -> service.findOwn(human, org, key),
                                500,
                                "RECEIPT_UNAVAILABLE")))
        .isInstanceOf(UnexpectedRollbackException.class);
    error(
        () -> new ProviderReceiptService(jdbc, connections).findOwn(human, org, key),
        500,
        "RECEIPT_UNAVAILABLE");
    var wrong = write();
    wrong.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    assertThatThrownBy(
            () ->
                wrong.executeWithoutResult(
                    s ->
                        error(
                            () -> service.execute(human, org, key, c, m, id, cred),
                            500,
                            "RECEIPT_UNAVAILABLE")))
        .isInstanceOf(UnexpectedRollbackException.class);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void probeRootSerializationCannotExposePreparedMetadata() throws Exception {
    create();
    var probe = service.probeOwn(human, org, key);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    String json = mapper.writeValueAsString(probe);
    assertThat(json)
        .doesNotContain(
            "metadata",
            "command-v1",
            probe.metadata().digest(),
            "opaque/",
            "external-v1",
            "credential-v1");
    assertThat(mapper.writeValueAsString(probe.result()))
        .doesNotContain("digest", "keyVersion", "metadata", "health", "mask");
    assertThat(probe.toString()).isEqualTo("Probe[redacted]");
  }

  @Test
  void replayAndReadNeverQueryCurrentParentOrCredentialOrWrite() {
    var created = create();
    var command = createCommand(Provider.OPENAI);
    var proof = prepare(command);
    clearInvocations(jdbc);
    assertThat(service.execute(human, org, key, command, proof, null, null).receipt())
        .isEqualTo(created.receipt());
    assertThat(service.findOwn(human, org, key).receipt()).isEqualTo(created.receipt());
    assertThat(service.probeOwn(human, org, key).result().receipt()).isEqualTo(created.receipt());
    var sql =
        mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String)
            .map(i -> ((String) i.getArguments()[0]).toLowerCase(Locale.ROOT))
            .toList();
    assertThat(sql).isNotEmpty();
    assertThat(sql)
        .allSatisfy(
            statement -> {
              assertThat(statement)
                  .doesNotContain("provider_connections", "provider_credential_versions");
              assertThat(statement).startsWith("select ");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"name", "state", "credential", "time", "missing"})
  void lockedParentAndEventDisagreementFailsBeforeMutation(String field) {
    create();
    switch (field) {
      case "name" ->
          jdbc.update(
              "update provider_connections set name='Mismatch' where organization_id=?", org);
      case "state" ->
          jdbc.update(
              "update provider_connections set state='DISABLED' where organization_id=?", org);
      case "credential" ->
          jdbc.update(
              "update provider_connection_events set credential_revision=1,configuration_revision=2"
                  + " where organization_id=?",
              org);
      case "time" ->
          jdbc.update(
              "update provider_connections set updated_at=? where organization_id=?",
              OffsetDateTime.parse("2099-01-01T00:00:00Z"),
              org);
      default ->
          jdbc.update(
              "update provider_connections set configuration_revision=2 where organization_id=?",
              org);
    }
    var before = snapshot();
    var c = new Rename(id, 1, "New");
    var m = prepare(c);
    error(
        () -> service.execute(human, org, UUID.randomUUID(), c, m, null, null),
        500,
        "RECEIPT_UNAVAILABLE");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void freshStructuralAndUnsupportedInputsHaveNoEffects() {
    var c = createCommand(Provider.OPENAI);
    var m = prepare(c);
    var before = snapshot();
    error(() -> service.execute(human, org, key, null, m, null, null), 400, "INVALID_COMMAND");
    for (Operation op : List.of(Operation.ACCESS_CHECK, Operation.MODEL_DISCOVERY)) {
      Command unsupported = new CredentialCommand(op, id, 0, 0);
      error(
          () -> service.execute(human, org, key, unsupported, prepare(unsupported), null, null),
          400,
          "INVALID_COMMAND");
    }
    error(() -> service.execute(human, org, new UUID(0, 0), c, m, null, null), 400, "INVALID_KEY");
    error(() -> service.execute(human, org, key, c, null, null, null), 400, "INVALID_PREPARATION");
    error(() -> service.execute(human, org, key, c, m, null, null), 400, "INVALID_PREPARATION");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void scopedReferencesNamespaceAndEventUniquenessAreNamedConstraints() {
    create();
    Command rename = new Rename(id, 1, "Next");
    UUID renameKey = UUID.randomUUID();
    service.execute(human, org, renameKey, rename, prepare(rename), null, null);
    UUID original = id;
    id = UUID.randomUUID();
    connections.create(human, org, id, "Other", null, credential(Provider.OPENAI, 1));
    UUID foreign =
        jdbc.queryForObject(
            "select id from provider_connection_events where organization_id=? and connection_id=?",
            UUID.class,
            org,
            id);
    id = original;
    for (String reference : List.of("before", "after", "creation")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update provider_command_receipts set "
                          + reference
                          + "_event_id=? where organization_id=? and idempotency_key=?",
                      foreign,
                      org,
                      renameKey))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
          .satisfies(
              e ->
                  assertThat(e.getMessage().toLowerCase(Locale.ROOT))
                      .contains("provider_command_receipts_" + reference + "_fk"));
    }
    UUID createCommandId =
        jdbc.queryForObject(
            "select command_id from provider_command_receipts where organization_id=? and"
                + " idempotency_key=?",
            UUID.class,
            org,
            key);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update provider_command_receipts set command_id=? where organization_id=? and"
                        + " idempotency_key=?",
                    createCommandId,
                    org,
                    renameKey))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .satisfies(
            e ->
                assertThat(e.getMessage().toLowerCase(Locale.ROOT))
                    .contains("provider_command_receipts_command_unique"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update provider_command_receipts set idempotency_key=? where organization_id=?"
                        + " and idempotency_key=?",
                    key,
                    org,
                    renameKey))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .satisfies(
            e -> assertThat(e.getMessage().toLowerCase(Locale.ROOT)).contains("idempotency_key"));
    assertThat(
            jdbc
                .queryForList(
                    "select constraint_name from information_schema.table_constraints where"
                        + " table_schema=current_schema() and"
                        + " table_name='provider_command_receipts'",
                    String.class)
                .stream()
                .map(v -> v.toLowerCase(Locale.ROOT))
                .toList())
        .contains("provider_command_receipts_namespace_pk");
    UUID createEvent =
        jdbc.queryForObject(
            "select after_event_id from provider_command_receipts where organization_id=? and"
                + " idempotency_key=?",
            UUID.class,
            org,
            key);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update provider_command_receipts set after_event_id=? where organization_id=?"
                        + " and idempotency_key=?",
                    createEvent,
                    org,
                    renameKey))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .satisfies(
            e ->
                assertThat(e.getMessage().toLowerCase(Locale.ROOT))
                    .contains("provider_command_receipts_after_unique"));
    jdbc.update(
        "update provider_command_receipts set creation_event_id=after_event_id where"
            + " organization_id=? and idempotency_key=?",
        org,
        renameKey);
    error(() -> service.findOwn(human, org, renameKey), 500, "RECEIPT_UNAVAILABLE");
  }

  @Test
  void freshRevisionNoChangeAndOverflowPrecedenceIsPreserved() {
    var created = create();
    for (Command c :
        List.of(
            new Rename(id, 2, created.receipt().after().name()),
            new RevisionCommand(Operation.ENABLE, id, 2))) {
      var m = prepare(c);
      error(
          () -> service.execute(human, org, UUID.randomUUID(), c, m, null, null),
          409,
          "REVISION_CONFLICT");
    }
    for (Command c :
        List.of(
            new Rename(id, 1, created.receipt().after().name()),
            new RevisionCommand(Operation.ENABLE, id, 1))) {
      var m = prepare(c);
      error(
          () -> service.execute(human, org, UUID.randomUUID(), c, m, null, null), 409, "NO_CHANGE");
    }
    var before = snapshot();
    var invalid = new Rename(id, 0, "Changed");
    var invalidProof = prepare(invalid);
    error(
        () -> service.execute(human, org, UUID.randomUUID(), invalid, invalidProof, null, null),
        400,
        "INVALID_COMMAND");
    assertThat(snapshot()).isEqualTo(before);
    jdbc.update(
        "insert into"
            + " provider_connection_events(id,organization_id,connection_id,configuration_revision,credential_revision,kind,provider,mode,name,workspace,state,actor_id,created_at)"
            + " select"
            + " ?,organization_id,connection_id,?,credential_revision,'RENAME',provider,mode,'At"
            + " maximum',workspace,state,actor_id,created_at from provider_connection_events where"
            + " organization_id=? and configuration_revision=1",
        UUID.randomUUID(),
        Long.MAX_VALUE,
        org);
    jdbc.update(
        "update provider_connections set configuration_revision=?,name='At maximum' where"
            + " organization_id=?",
        Long.MAX_VALUE,
        org);
    before = snapshot();
    var noop = new Rename(id, Long.MAX_VALUE, "At maximum");
    var noopProof = prepare(noop);
    error(
        () -> service.execute(human, org, UUID.randomUUID(), noop, noopProof, null, null),
        409,
        "NO_CHANGE");
    var exhausted = new Rename(id, Long.MAX_VALUE, "Next");
    var exhaustedProof = prepare(exhausted);
    error(
        () -> service.execute(human, org, UUID.randomUUID(), exhausted, exhaustedProof, null, null),
        409,
        "REVISION_EXHAUSTED");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void migratedReceiptColumnsHaveExactTypesNullabilityDefaultsAndLengths() {
    List<String> names =
        List.of(
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
    Map<String, Integer> lengths =
        Map.of("operation", 8, "digest_version", 32, "key_version", 256, "command_digest", 44);
    var columns =
        jdbc.queryForList(
            "select column_name,data_type,is_nullable,column_default,character_maximum_length from"
                + " information_schema.columns where table_schema=current_schema() and"
                + " table_name='provider_command_receipts' order by ordinal_position");
    assertThat(columns).hasSize(13);
    for (int index = 0; index < names.size(); index++) {
      var column = columns.get(index);
      String name = names.get(index);
      assertThat(column.get("column_name")).isEqualTo(name);
      String type =
          ((String) column.get("data_type"))
              .toLowerCase(Locale.ROOT)
              .replaceAll("\\s+", " ")
              .trim();
      assertThat(type)
          .as(name)
          .isEqualTo(
              lengths.containsKey(name)
                  ? "character varying"
                  : name.equals("recorded_at") ? "timestamp with time zone" : "uuid");
      assertThat(column.get("is_nullable"))
          .as(name)
          .isEqualTo(name.equals("before_event_id") ? "YES" : "NO");
      assertThat(column.get("column_default")).as(name).isNull();
      if (lengths.containsKey(name))
        assertThat(((Number) column.get("character_maximum_length")).intValue())
            .as(name)
            .isEqualTo(lengths.get(name));
      else assertThat(column.get("character_maximum_length")).as(name).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"creation", "before", "after"})
  void scopedEventForeignKeysRejectWrongTenantIndependently(String reference) {
    create();
    Command rename = new Rename(id, 1, "Next");
    UUID renameKey = UUID.randomUUID();
    service.execute(human, org, renameKey, rename, prepare(rename), null, null);
    UUID localOrg = org,
        localId = id,
        foreignOrg = UUID.randomUUID(),
        foreignConnection = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Foreign"
            + " receipts',?,'ACTIVE',current_timestamp,current_timestamp)",
        foreignOrg,
        foreignOrg.toString());
    try {
      jdbc.update(
          "insert into organization_memberships(account_id,organization_id,role) values"
              + " (?,?,'ADMIN')",
          actor,
          foreignOrg);
      CredentialBoundary.Metadata foreignCredential;
      try {
        org = foreignOrg;
        id = foreignConnection;
        foreignCredential = credential(Provider.OPENAI, 1);
      } finally {
        org = localOrg;
        id = localId;
      }
      connections.create(human, foreignOrg, foreignConnection, "Foreign", null, foreignCredential);
      UUID foreignEvent =
          jdbc.queryForObject(
              "select id from provider_connection_events where organization_id=? and"
                  + " connection_id=?",
              UUID.class,
              foreignOrg,
              foreignConnection);
      var before = snapshot();
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update provider_command_receipts set "
                          + reference
                          + "_event_id=? where organization_id=? and actor_id=? and"
                          + " idempotency_key=?",
                      foreignEvent,
                      localOrg,
                      actor,
                      renameKey))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
          .satisfies(
              ex ->
                  assertThat(ex.getMessage().toLowerCase(Locale.ROOT))
                      .contains("provider_command_receipts_" + reference + "_fk"));
      assertThat(snapshot()).isEqualTo(before);
      assertThat(service.findOwn(human, localOrg, renameKey).receipt().after().revision())
          .isEqualTo(2);
    } finally {
      org = localOrg;
      id = localId;
      jdbc.update("delete from provider_connection_events where organization_id=?", foreignOrg);
      jdbc.update("delete from provider_connections where organization_id=?", foreignOrg);
      jdbc.update("delete from provider_credential_versions where organization_id=?", foreignOrg);
      jdbc.update("delete from organization_memberships where organization_id=?", foreignOrg);
      jdbc.update("delete from organizations where id=?", foreignOrg);
    }
  }
}

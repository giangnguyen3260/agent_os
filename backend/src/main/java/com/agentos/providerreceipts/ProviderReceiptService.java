package com.agentos.providerreceipts;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary;
import com.agentos.providercommands.CommandBoundary.Command;
import com.agentos.providercommands.CommandBoundary.Comparison;
import com.agentos.providercommands.CommandBoundary.Create;
import com.agentos.providercommands.CommandBoundary.CredentialCommand;
import com.agentos.providercommands.CommandBoundary.CredentialMode;
import com.agentos.providercommands.CommandBoundary.Metadata;
import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providercommands.CommandBoundary.Rename;
import com.agentos.providercommands.CommandBoundary.RevisionCommand;
import com.agentos.providercommands.CommandBoundary.Rotate;
import com.agentos.providerconnections.ProviderConnectionFailure;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal prepared equality and event-pinned history. All write results await outer commit. */
@Service
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
public class ProviderReceiptService {
  @JsonIgnoreType
  public record Configuration(
      UUID organizationId,
      UUID id,
      Provider provider,
      CredentialMode credentialMode,
      String name,
      String workspaceId,
      String state,
      long revision,
      long credentialRevision,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  @JsonIgnoreType
  public record Receipt(
      UUID org,
      UUID commandId,
      Operation operation,
      UUID connectionId,
      UUID actorId,
      OffsetDateTime recordedAt,
      Configuration before,
      Configuration after,
      UUID checkId) {
    public Receipt(
        UUID org,
        UUID commandId,
        Operation operation,
        UUID connectionId,
        UUID actorId,
        OffsetDateTime recordedAt,
        Configuration before,
        Configuration after) {
      this(org, commandId, operation, connectionId, actorId, recordedAt, before, after, null);
    }

    @Override
    public String toString() {
      return "Receipt[redacted]";
    }
  }

  @JsonIgnoreType
  public record Result(Receipt receipt, boolean replayed) {
    @Override
    public String toString() {
      return "Result[redacted]";
    }
  }

  @JsonIgnoreType
  public record Probe(
      Result result, @com.fasterxml.jackson.annotation.JsonIgnore Metadata metadata) {
    @Override
    public String toString() {
      return "Probe[redacted]";
    }
  }

  @JsonIgnoreType
  private record Event(
      UUID id,
      UUID org,
      UUID connection,
      UUID actor,
      Operation kind,
      Provider provider,
      CredentialMode mode,
      String name,
      String workspace,
      String state,
      long revision,
      long credentialRevision,
      OffsetDateTime time) {
    @Override
    public String toString() {
      return "Event[redacted]";
    }
  }

  private final JdbcTemplate jdbc;
  private final ProviderConnectionService connections;

  public ProviderReceiptService(JdbcTemplate jdbc, ProviderConnectionService connections) {
    this.jdbc = jdbc;
    this.connections = connections;
  }

  public Result execute(
      HumanIdentity actor,
      UUID org,
      UUID key,
      Command command,
      Metadata prepared,
      UUID createId,
      CredentialBoundary.Metadata credential) {
    return executeInternal(actor, org, key, command, prepared, createId, credential, null);
  }

  public Result executeCreateGoverned(
      HumanIdentity actor,
      UUID org,
      UUID key,
      Command command,
      Metadata prepared,
      UUID createId,
      CredentialBoundary.Metadata credential) {
    return executeInternal(
        actor, org, key, command, prepared, createId, credential, Operation.CREATE);
  }

  public Result executeRenameGoverned(
      HumanIdentity actor, UUID org, UUID key, Rename command, Metadata prepared) {
    return executeInternal(actor, org, key, command, prepared, null, null, Operation.RENAME);
  }

  public Result executeDisableGoverned(
      HumanIdentity actor, UUID org, UUID key, RevisionCommand command, Metadata prepared) {
    return executeInternal(actor, org, key, command, prepared, null, null, Operation.DISABLE);
  }

  public Result executeEnableGoverned(
      HumanIdentity actor, UUID org, UUID key, RevisionCommand command, Metadata prepared) {
    return executeInternal(actor, org, key, command, prepared, null, null, Operation.ENABLE);
  }

  public Result executeRotateGoverned(
      HumanIdentity actor,
      UUID org,
      UUID key,
      Rotate command,
      Metadata prepared,
      CredentialBoundary.Metadata credential) {
    return executeInternal(actor, org, key, command, prepared, null, credential, Operation.ROTATE);
  }

  public Result acceptAccessCheck(
      HumanIdentity actor, UUID org, UUID key, CredentialCommand command, Metadata prepared) {
    return safe(
        () -> {
          var policy = authority(actor, org, true, true);
          if (command == null || command.operation() != Operation.ACCESS_CHECK)
            throw fail(400, "INVALID_COMMAND");
          validKey(key);
          if (prepared == null) throw fail(400, "INVALID_PREPARATION");
          var historical = own(org, actor.id(), key);
          if (historical != null) {
            if (historical.result().receipt().operation() != Operation.ACCESS_CHECK
                || !command.target().equals(historical.result().receipt().connectionId()))
              throw fail(409, "IDEMPOTENCY_CONFLICT");
            if (!historical.metadata().keyVersion().equals(prepared.keyVersion()))
              throw fail(409, "PREPARATION_REQUIRED");
            if (CommandBoundary.compare(prepared, historical.metadata()) != Comparison.MATCH)
              throw fail(409, "IDEMPOTENCY_CONFLICT");
            return historical.result();
          }
          if (policy.size() != 1) throw fail(503, "COMMAND_UNAVAILABLE");
          UUID target = command.target();
          var rows =
              jdbc.query(
                  "select * from provider_connections where organization_id=? and id=? for update",
                  (rs, n) -> parent(rs),
                  org,
                  target);
          if (rows.isEmpty()) throw fail(404, "NOT_FOUND");
          require(rows.size() == 1);
          var parent = rows.get(0);
          if (parent.revision() != command.expectedRevision()
              || parent.credentialRevision() != command.expectedCredentialRevision())
            throw fail(409, "REVISION_CONFLICT");
          if (!"ENABLED".equals(parent.state())) throw fail(409, "CONNECTION_DISABLED");
          requireProviderEnabled(policy.get(0), parent.provider());
          requireCredentialAvailable(parent);
          long policyRevision = ((Number) policy.get(0).get("revision")).longValue();
          var current = eventAt(org, target, parent.revision());
          var initial = creation(org, target);
          var snapshot = configuration(current, initial);
          require(parent.equals(snapshot));
          if (!jdbc.queryForList(
                  "select check_id from provider_access_check_outbox where organization_id=? and"
                      + " connection_id=?",
                  org,
                  target)
              .isEmpty()) throw fail(409, "CHECK_IN_PROGRESS");
          var accepted = now();
          checkQuota(org, actor.id(), accepted);
          UUID check = UUID.randomUUID(), commandId = UUID.randomUUID();
          require(
              jdbc.update(
                      """
insert into provider_access_checks
  (id,organization_id,connection_id,actor_id,actor_credential_version,
   connection_revision,credential_revision,policy_revision,accepted_at,queue_expires_at)
values (?,?,?,?,?,?,?,?,?,?)
""",
                      check,
                      org,
                      target,
                      actor.id(),
                      actor.credentialVersion(),
                      parent.revision(),
                      parent.credentialRevision(),
                      policyRevision,
                      accepted,
                      accepted.plusSeconds(60))
                  == 1);
          require(
              jdbc.update(
                      """
insert into provider_access_check_outbox
  (organization_id,connection_id,check_id,connection_revision,credential_revision,policy_revision)
values (?,?,?,?,?,?)
""",
                      org,
                      target,
                      check,
                      parent.revision(),
                      parent.credentialRevision(),
                      policyRevision)
                  == 1);
          require(
              jdbc.update(
                      """
insert into provider_access_check_receipts
  (organization_id,connection_id,actor_id,idempotency_key,command_id,check_id,
   snapshot_event_id,creation_event_id,digest_version,key_version,command_digest)
values (?,?,?,?,?,?,?,?,?,?,?)
""",
                      org,
                      target,
                      actor.id(),
                      key,
                      commandId,
                      check,
                      current.id(),
                      initial.id(),
                      prepared.digestVersion(),
                      prepared.keyVersion(),
                      prepared.digest())
                  == 1);
          require(
              jdbc.update(
                      """
                      insert into provider_access_check_events
                        (check_id,organization_id,connection_id,actor_id,event_type,occurred_at)
                      values (?,?,?,?,'ACCEPTED',?)
                      """,
                      check,
                      org,
                      target,
                      actor.id(),
                      accepted)
                  == 1);
          return new Result(
              new Receipt(
                  org,
                  commandId,
                  Operation.ACCESS_CHECK,
                  target,
                  actor.id(),
                  accepted,
                  snapshot,
                  snapshot,
                  check),
              false);
        });
  }

  private void checkQuota(UUID org, UUID actor, OffsetDateTime accepted) {
    var recent =
        jdbc.query(
            """
            select actor_id, accepted_at from provider_access_checks
            where organization_id=? and accepted_at>?
            order by accepted_at desc limit 12
            """,
            (rs, n) ->
                new CheckQuotaEntry(
                    id(rs, "actor_id"), rs.getObject("accepted_at", OffsetDateTime.class)),
            org,
            accepted.minusSeconds(60));
    var actorTimes =
        recent.stream()
            .filter(row -> actor.equals(row.actor()))
            .map(CheckQuotaEntry::accepted)
            .toList();
    var eligible = accepted;
    if (recent.size() == 12) eligible = recent.get(11).accepted().plusSeconds(60);
    if (actorTimes.size() >= 6 && actorTimes.get(5).plusSeconds(60).isAfter(eligible))
      eligible = actorTimes.get(5).plusSeconds(60);
    if (eligible.isAfter(accepted)) {
      long nanos = java.time.Duration.between(accepted, eligible).toNanos();
      throw new ProviderReceiptFailure(
          429,
          "RATE_LIMITED",
          (int) Math.max(1, Math.min(60, (nanos + 999_999_999) / 1_000_000_000)));
    }
  }

  private record CheckQuotaEntry(UUID actor, OffsetDateTime accepted) {}

  private Result executeInternal(
      HumanIdentity actor,
      UUID org,
      UUID key,
      Command command,
      Metadata prepared,
      UUID createId,
      CredentialBoundary.Metadata credential,
      Operation governance) {
    return safe(
        () -> {
          var policy = authority(actor, org, true, governance != null);
          if (command == null
              || command instanceof CredentialCommand
              || (requiresMatchingOperation(governance) && command.operation() != governance))
            throw fail(400, "INVALID_COMMAND");
          validKey(key);
          if (prepared == null) throw fail(400, "INVALID_PREPARATION");
          Probe stored = own(org, actor.id(), key);
          if (stored != null) {
            if (stored.result().receipt().operation() == Operation.ACCESS_CHECK
                || (requiresMatchingOperation(governance)
                    && stored.result().receipt().operation() != governance))
              throw fail(409, "IDEMPOTENCY_CONFLICT");
            if (!stored.metadata().keyVersion().equals(prepared.keyVersion()))
              throw fail(409, "PREPARATION_REQUIRED");
            if (CommandBoundary.compare(prepared, stored.metadata()) != Comparison.MATCH)
              throw fail(409, "IDEMPOTENCY_CONFLICT");
            return stored.result();
          }
          if (governance == Operation.CREATE && !(command instanceof Create))
            throw fail(400, "INVALID_COMMAND");
          if (governance != null && policy.size() != 1) throw fail(503, "COMMAND_UNAVAILABLE");
          if (governance == Operation.CREATE) {
            requireProviderEnabled(policy.get(0), ((Create) command).provider());
            Long count =
                jdbc.queryForObject(
                    "select count(*) from provider_connections where organization_id=?",
                    Long.class,
                    org);
            if (count == null || count < 0) throw fail(503, "COMMAND_UNAVAILABLE");
            if (count >= 100) throw fail(409, "CONNECTION_LIMIT");
          }
          boolean creating = command instanceof Create;
          if (creating) {
            if (!uuid(createId) || credential == null) throw fail(400, "INVALID_PREPARATION");
            Create c = (Create) command;
            credential(credential, org, createId, c.provider(), 1);
          } else {
            if (createId != null || (credential != null && !(command instanceof Rotate)))
              throw fail(400, "INVALID_PREPARATION");
            if (command instanceof Rotate && credential == null)
              throw fail(400, "INVALID_PREPARATION");
            if (credential != null
                && (!credential.scope().organization().equals(org)
                    || !credential.scope().connection().equals(command.target())))
              throw fail(400, "INVALID_PREPARATION");
          }
          UUID target = creating ? createId : command.target();
          Event before = null;
          if (!creating) {
            var parents =
                jdbc.query(
                    "select * from provider_connections where organization_id=? and id=? for"
                        + " update",
                    (rs, n) -> parent(rs),
                    org,
                    target);
            if (parents.isEmpty()) throw fail(404, "NOT_FOUND");
            require(parents.size() == 1);
            Configuration parent = parents.get(0);
            before = eventAt(org, target, parent.revision());
            Configuration historical = configuration(before, creation(org, target));
            require(
                org.equals(parent.organizationId())
                    && target.equals(parent.id())
                    && historical.provider() == parent.provider()
                    && historical.credentialMode() == parent.credentialMode()
                    && historical.name().equals(parent.name())
                    && Objects.equals(historical.workspaceId(), parent.workspaceId())
                    && historical.state().equals(parent.state())
                    && historical.revision() == parent.revision()
                    && historical.credentialRevision() == parent.credentialRevision()
                    && historical.createdAt().toInstant().equals(parent.createdAt().toInstant())
                    && historical.updatedAt().toInstant().equals(parent.updatedAt().toInstant()));
            if (governance == Operation.ROTATE) {
              var scope = rotationScope(org, parent, (Rotate) command);
              credential(credential, org, target, parent.provider(), scope.credentialRevision());
            }
            if (governance == Operation.ENABLE)
              checkEnable(policy.get(0), parent, (RevisionCommand) command);
          }
          ProviderConnectionService.Connection changed;
          if (command instanceof Create c) {
            changed = connections.create(actor, org, target, c.name(), c.workspaceId(), credential);
          } else if (command instanceof Rename c) {
            changed = connections.rename(actor, org, target, c.expectedRevision(), c.name());
          } else if (command instanceof Rotate c) {
            changed = connections.rotate(actor, org, target, c.expectedRevision(), credential);
          } else if (command instanceof RevisionCommand c) {
            changed =
                connections.setEnabled(
                    actor, org, target, c.expectedRevision(), c.operation() == Operation.ENABLE);
          } else throw fail(400, "INVALID_COMMAND");
          Event after = eventAt(org, target, changed.revision());
          Event creation = creation(org, target);
          Configuration authoritative = configuration(after, creation);
          require(
              authoritative.organizationId().equals(changed.organization())
                  && authoritative.id().equals(changed.id())
                  && authoritative.provider().name().equals(changed.provider().name())
                  && authoritative.credentialMode().name().equals(changed.mode())
                  && authoritative.name().equals(changed.name())
                  && Objects.equals(authoritative.workspaceId(), changed.workspace())
                  && authoritative.state().equals(changed.state())
                  && authoritative.revision() == changed.revision()
                  && authoritative.credentialRevision() == changed.credentialRevision()
                  && authoritative.createdAt().toInstant().equals(changed.createdAt().toInstant())
                  && authoritative.updatedAt().toInstant().equals(changed.updatedAt().toInstant()));
          OffsetDateTime recorded = now();
          UUID commandId = UUID.randomUUID();
          Receipt receipt =
              receipt(
                  org,
                  commandId,
                  command.operation(),
                  target,
                  actor.id(),
                  recorded,
                  before,
                  after,
                  creation);
          if (jdbc.update(
                  "insert into"
                      + " provider_command_receipts(organization_id,actor_id,idempotency_key,command_id,operation,connection_id,before_event_id,after_event_id,creation_event_id,digest_version,key_version,command_digest,recorded_at)"
                      + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  org,
                  actor.id(),
                  key,
                  commandId,
                  command.operation().name(),
                  target,
                  before == null ? null : before.id(),
                  after.id(),
                  creation.id(),
                  prepared.digestVersion(),
                  prepared.keyVersion(),
                  prepared.digest(),
                  recorded)
              != 1) throw unavailable();
          return new Result(receipt, false);
        });
  }

  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ)
  public Result findOwn(HumanIdentity actor, UUID org, UUID key) {
    return safe(
        () -> {
          authority(actor, org, false);
          validKey(key);
          Probe p = own(org, actor.id(), key);
          return p == null ? null : p.result();
        });
  }

  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ)
  public Probe probeOwn(HumanIdentity actor, UUID org, UUID key) {
    return safe(
        () -> {
          authority(actor, org, false);
          validKey(key);
          return own(org, actor.id(), key);
        });
  }

  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ)
  public CredentialBoundary.Scope prepareRotateScope(
      HumanIdentity actor, UUID org, Rotate command) {
    return safe(
        () -> {
          authority(actor, org, false);
          if (command == null) throw fail(400, "INVALID_COMMAND");
          var rows =
              jdbc.query(
                  "select * from provider_connections where organization_id=? and id=?",
                  (rs, n) -> parent(rs),
                  org,
                  command.target());
          if (rows.isEmpty()) throw fail(404, "NOT_FOUND");
          require(rows.size() == 1);
          return rotationScope(org, rows.get(0), command);
        });
  }

  private static CredentialBoundary.Scope rotationScope(
      UUID org, Configuration parent, Rotate command) {
    require(org.equals(parent.organizationId()) && command.target().equals(parent.id()));
    if (command.expectedRevision() < 1) throw fail(400, "INVALID_COMMAND");
    if (parent.revision() != command.expectedRevision()) throw fail(409, "REVISION_CONFLICT");
    if (parent.revision() == Long.MAX_VALUE || parent.credentialRevision() == Long.MAX_VALUE)
      throw fail(409, "REVISION_EXHAUSTED");
    return new CredentialBoundary.Scope(
        org,
        parent.id(),
        CredentialBoundary.Provider.valueOf(parent.provider().name()),
        parent.credentialRevision() + 1);
  }

  private Probe own(UUID org, UUID actor, UUID key) {
    var rows =
        jdbc.query(
            "select * from provider_command_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            (rs, n) -> {
              require(
                  org.equals(id(rs, "organization_id"))
                      && actor.equals(id(rs, "actor_id"))
                      && key.equals(id(rs, "idempotency_key")));
              UUID commandId = id(rs, "command_id"), target = id(rs, "connection_id");
              Operation operation = operation(rs.getString("operation"));
              UUID beforeId = rs.getObject("before_event_id", UUID.class);
              if (beforeId != null) require(uuid(beforeId));
              UUID afterId = id(rs, "after_event_id");
              Metadata metadata =
                  new Metadata(
                      rs.getString("digest_version"),
                      rs.getString("key_version"),
                      rs.getString("command_digest"));
              Event after = eventId(org, target, afterId);
              Event before = beforeId == null ? null : eventId(org, target, beforeId);
              Receipt r =
                  receipt(
                      org,
                      commandId,
                      operation,
                      target,
                      actor,
                      rs.getObject("recorded_at", OffsetDateTime.class),
                      before,
                      after,
                      eventId(org, target, id(rs, "creation_event_id")));
              return new Probe(new Result(r, true), metadata);
            },
            org,
            actor,
            key);
    require(rows.size() <= 1);
    var checks =
        jdbc.query(
            """
select r.*, c.accepted_at, c.connection_revision, c.credential_revision
from provider_access_check_receipts r
join provider_access_checks c on c.id=r.check_id and c.organization_id=r.organization_id
  and c.connection_id=r.connection_id and c.actor_id=r.actor_id
where r.organization_id=? and r.actor_id=? and r.idempotency_key=?
""",
            (rs, n) -> {
              UUID target = id(rs, "connection_id");
              var snapshot =
                  configuration(
                      eventId(org, target, id(rs, "snapshot_event_id")),
                      eventId(org, target, id(rs, "creation_event_id")));
              var accepted = rs.getObject("accepted_at", OffsetDateTime.class);
              require(
                  "ENABLED".equals(snapshot.state())
                      && snapshot.revision() == rs.getLong("connection_revision")
                      && snapshot.credentialRevision() == rs.getLong("credential_revision")
                      && accepted != null
                      && !accepted.isBefore(snapshot.updatedAt()));
              var receipt =
                  new Receipt(
                      org,
                      id(rs, "command_id"),
                      Operation.ACCESS_CHECK,
                      target,
                      actor,
                      accepted,
                      snapshot,
                      snapshot,
                      id(rs, "check_id"));
              return new Probe(
                  new Result(receipt, true),
                  new Metadata(
                      rs.getString("digest_version"),
                      rs.getString("key_version"),
                      rs.getString("command_digest")));
            },
            org,
            actor,
            key);
    require(rows.size() + checks.size() <= 1);
    if (!checks.isEmpty()) return checks.get(0);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static boolean requiresMatchingOperation(Operation operation) {
    return operation == Operation.DISABLE
        || operation == Operation.ENABLE
        || operation == Operation.ROTATE;
  }

  private static void requireProviderEnabled(Map<String, Object> policy, Provider provider) {
    String enabled =
        switch (provider) {
          case OPENAI -> "openai_enabled";
          case ANTHROPIC -> "anthropic_enabled";
          case GOOGLE_GEMINI -> "gemini_enabled";
        };
    if (!Boolean.TRUE.equals(policy.get(enabled))) throw fail(409, "PROVIDER_DISABLED");
  }

  private void checkEnable(
      Map<String, Object> policy, Configuration parent, RevisionCommand command) {
    if (command.expectedRevision() < 1) throw fail(400, "INVALID_COMMAND");
    if (parent.revision() != command.expectedRevision()) throw fail(409, "REVISION_CONFLICT");
    if ("ENABLED".equals(parent.state())) throw fail(409, "NO_CHANGE");
    if (parent.revision() == Long.MAX_VALUE) throw fail(409, "REVISION_EXHAUSTED");
    requireProviderEnabled(policy, parent.provider());
    requireCredentialAvailable(parent);
  }

  private void requireCredentialAvailable(Configuration parent) {
    // Publication locks the parent before changing credential evidence. Read the same
    // scoped evidence as List in a fresh statement after taking that lock.
    Boolean available =
        jdbc.queryForObject(
            "select agentos_list_read.credential_available(?::uuid,?::uuid)",
            Boolean.class,
            parent.organizationId(),
            parent.id());
    if (available == null) throw unavailable();
    if (!available) throw fail(409, "CREDENTIAL_UNAVAILABLE");
  }

  private Receipt receipt(
      UUID org,
      UUID commandId,
      Operation op,
      UUID target,
      UUID actor,
      OffsetDateTime recorded,
      Event before,
      Event after,
      Event creation) {
    require(uuid(org) && uuid(commandId) && uuid(target) && uuid(actor));
    require(
        after.org().equals(org)
            && after.connection().equals(target)
            && after.actor().equals(actor)
            && after.kind() == op);
    Configuration a = configuration(after, creation);
    Configuration b = before == null ? null : configuration(before, creation);
    require(recorded != null && !recorded.isBefore(a.updatedAt()));
    if (op == Operation.CREATE) {
      require(before == null && after.equals(creation));
    } else {
      require(
          b != null
              && b.revision() < Long.MAX_VALUE
              && a.revision() == b.revision() + 1
              && !a.updatedAt().isBefore(b.updatedAt()));
      boolean sameName = a.name().equals(b.name()), sameState = a.state().equals(b.state());
      boolean sameCredential = a.credentialRevision() == b.credentialRevision();
      switch (op) {
        case RENAME -> require(!sameName && sameState && sameCredential);
        case ROTATE ->
            require(
                sameName
                    && sameState
                    && b.credentialRevision() < Long.MAX_VALUE
                    && a.credentialRevision() == b.credentialRevision() + 1);
        case ENABLE, DISABLE ->
            require(
                sameName
                    && !sameState
                    && sameCredential
                    && a.state().equals(op == Operation.ENABLE ? "ENABLED" : "DISABLED"));
        default -> throw unavailable();
      }
    }
    return new Receipt(org, commandId, op, target, actor, recorded, b, a);
  }

  private Configuration configuration(Event e, Event creation) {
    require(
        creation.kind() == Operation.CREATE
            && creation.revision() == 1
            && creation.credentialRevision() == 1
            && creation.state().equals("ENABLED"));
    require(
        e.org().equals(creation.org())
            && e.connection().equals(creation.connection())
            && e.provider() == creation.provider()
            && e.mode() == creation.mode()
            && Objects.equals(e.workspace(), creation.workspace())
            && !e.time().isBefore(creation.time()));
    require(e.revision() == 1 ? e.equals(creation) : e.kind() != Operation.CREATE);
    if (e.kind() == Operation.ENABLE) require(e.state().equals("ENABLED"));
    if (e.kind() == Operation.DISABLE) require(e.state().equals("DISABLED"));
    return new Configuration(
        e.org(),
        e.connection(),
        e.provider(),
        e.mode(),
        e.name(),
        e.workspace(),
        e.state(),
        e.revision(),
        e.credentialRevision(),
        creation.time(),
        e.time());
  }

  private Configuration parent(ResultSet rs) throws SQLException {
    return new Configuration(
        id(rs, "organization_id"),
        id(rs, "id"),
        Provider.valueOf(rs.getString("provider")),
        CredentialMode.valueOf(rs.getString("mode")),
        rs.getString("name"),
        rs.getString("workspace"),
        rs.getString("state"),
        rs.getLong("configuration_revision"),
        rs.getLong("credential_revision"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  private Event creation(UUID org, UUID target) {
    return eventAt(org, target, 1);
  }

  private Event eventAt(UUID org, UUID target, long revision) {
    var rows =
        jdbc.query(
            "select * from provider_connection_events where organization_id=? and connection_id=?"
                + " and configuration_revision=?",
            (rs, n) -> event(rs),
            org,
            target,
            revision);
    require(rows.size() == 1);
    Event e = rows.get(0);
    require(e.org().equals(org) && e.connection().equals(target) && e.revision() == revision);
    return e;
  }

  private Event eventId(UUID org, UUID target, UUID eventId) {
    var rows =
        jdbc.query(
            "select * from provider_connection_events where organization_id=? and connection_id=?"
                + " and id=?",
            (rs, n) -> event(rs),
            org,
            target,
            eventId);
    require(rows.size() == 1);
    Event e = rows.get(0);
    require(e.org().equals(org) && e.connection().equals(target) && e.id().equals(eventId));
    return e;
  }

  private Event event(ResultSet rs) throws SQLException {
    Provider provider = Provider.valueOf(rs.getString("provider"));
    CredentialMode mode = CredentialMode.valueOf(rs.getString("mode"));
    String name = rs.getString("name"),
        workspace = rs.getString("workspace"),
        state = rs.getString("state");
    Create validated = new Create(provider, mode, name, workspace);
    require(validated.name().equals(name));
    require("ENABLED".equals(state) || "DISABLED".equals(state));
    long revision = rs.getLong("configuration_revision"),
        credentialRevision = rs.getLong("credential_revision");
    require(revision > 0 && credentialRevision > 0 && credentialRevision <= revision);
    OffsetDateTime time = rs.getObject("created_at", OffsetDateTime.class);
    require(time != null);
    return new Event(
        id(rs, "id"),
        id(rs, "organization_id"),
        id(rs, "connection_id"),
        id(rs, "actor_id"),
        operation(rs.getString("kind")),
        provider,
        mode,
        name,
        workspace,
        state,
        revision,
        credentialRevision,
        time);
  }

  private void authority(HumanIdentity actor, UUID org, boolean write) {
    authority(actor, org, write, false);
  }

  private List<Map<String, Object>> authority(
      HumanIdentity actor, UUID org, boolean write, boolean governed) {
    if (actor == null || actor.id() == null) throw fail(401, "UNAUTHORIZED");
    if (!uuid(org) || !uuid(actor.id())) throw fail(403, "FORBIDDEN");
    transaction(write);
    String lock = write ? " for update" : "";
    if (jdbc.queryForList("select id from organizations where id=?" + lock, org).size() != 1)
      throw fail(403, "FORBIDDEN");
    var policy =
        governed
            ? jdbc.queryForList("select * from provider_deployment_policy where id=1 for update")
            : List.<Map<String, Object>>of();
    var accounts =
        jdbc.queryForList(
            "select enabled,credential_version from human_accounts where id=?" + lock, actor.id());
    var members =
        jdbc.queryForList(
            "select role from organization_memberships where organization_id=? and account_id=?"
                + lock,
            org,
            actor.id());
    if (accounts.size() != 1
        || members.size() != 1
        || !Boolean.TRUE.equals(accounts.get(0).get("enabled"))
        || ((Number) accounts.get(0).get("credential_version")).longValue()
            != actor.credentialVersion()
        || !"ADMIN".equals(members.get(0).get("role"))) throw fail(403, "FORBIDDEN");
    return policy;
  }

  private void transaction(boolean write) {
    require(
        TransactionSynchronizationManager.isActualTransactionActive()
            && jdbc.getDataSource() != null
            && TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
            && TransactionSynchronizationManager.isCurrentTransactionReadOnly() != write);
    Integer isolation =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Integer>)
                java.sql.Connection::getTransactionIsolation);
    require(
        isolation != null
            && isolation
                == (write
                    ? java.sql.Connection.TRANSACTION_READ_COMMITTED
                    : java.sql.Connection.TRANSACTION_REPEATABLE_READ));
  }

  private static void credential(
      CredentialBoundary.Metadata m, UUID org, UUID target, Provider provider, long revision) {
    if (!m.scope().organization().equals(org)
        || !m.scope().connection().equals(target)
        || !m.scope().provider().name().equals(provider.name())
        || m.scope().credentialRevision() != revision
        || !m.reference().scope().equals(m.scope())) throw fail(400, "INVALID_PREPARATION");
  }

  private OffsetDateTime now() {
    String product =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<String>)
                c -> c.getMetaData().getDatabaseProductName());
    return jdbc.queryForObject(
        "PostgreSQL".equals(product) ? "select clock_timestamp()" : "select current_timestamp",
        OffsetDateTime.class);
  }

  private static UUID id(ResultSet rs, String column) throws SQLException {
    UUID id = rs.getObject(column, UUID.class);
    require(uuid(id));
    return id;
  }

  private static boolean uuid(UUID id) {
    return id != null && id.variant() == 2 && id.version() >= 1 && id.version() <= 5;
  }

  private static void validKey(UUID key) {
    if (!uuid(key)) throw fail(400, "INVALID_KEY");
  }

  private static Operation operation(String value) {
    Operation op = Operation.valueOf(value);
    require(op != Operation.ACCESS_CHECK && op != Operation.MODEL_DISCOVERY);
    return op;
  }

  private static void require(boolean valid) {
    if (!valid) throw unavailable();
  }

  private static ProviderReceiptFailure unavailable() {
    return fail(500, "RECEIPT_UNAVAILABLE");
  }

  private static ProviderReceiptFailure fail(int status, String code) {
    return new ProviderReceiptFailure(status, code);
  }

  private static <T> T safe(Supplier<T> action) {
    try {
      return action.get();
    } catch (RuntimeException ex) {
      try {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      } catch (RuntimeException ignored) {
        /* Missing proxy is itself rejected by context checks. */
      }
      if (ex instanceof ProviderReceiptFailure failure) throw failure;
      if (ex instanceof ProviderConnectionFailure failure) {
        String code = failure.code();
        if (code.equals("INVALID_REVISION")) throw fail(400, "INVALID_COMMAND");
        if (java.util.Set.of(
                "UNAUTHORIZED",
                "FORBIDDEN",
                "NOT_FOUND",
                "REVISION_CONFLICT",
                "NO_CHANGE",
                "REVISION_EXHAUSTED",
                "CONNECTION_CONFLICT")
            .contains(code)) throw fail(failure.status(), code);
        if (java.util.Set.of(
                "INVALID_METADATA",
                "INVALID_REVISION",
                "INVALID_INPUT",
                "INVALID_NAME",
                "INVALID_OPTIONS")
            .contains(code)) throw fail(400, "INVALID_PREPARATION");
      }
      throw unavailable();
    }
  }
}

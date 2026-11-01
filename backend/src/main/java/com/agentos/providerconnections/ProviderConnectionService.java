package com.agentos.providerconnections;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercredentials.CredentialBoundary.Metadata;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal prepared-metadata persistence. No secret manager or transport dependency. */
@Service
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
public class ProviderConnectionService {
  @JsonIgnoreType
  public record Connection(
      UUID organization,
      UUID id,
      Provider provider,
      String mode,
      String name,
      String workspace,
      String state,
      long revision,
      long credentialRevision,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      @com.fasterxml.jackson.annotation.JsonIgnore Metadata metadata) {
    @Override
    public String toString() {
      return "Connection[redacted]";
    }
  }

  @JsonIgnoreType
  public record Page(List<Connection> items, long total, int page) {
    public Page {
      items = List.copyOf(items);
    }

    @Override
    public String toString() {
      return "Page[redacted]";
    }
  }

  private static final String SELECT_CURRENT =
      "select c.*,v.external_locator,v.external_key_version,v.hmac_key_version,"
          + "v.hmac_domain_version,v.verifier from provider_connections c left join "
          + "provider_credential_versions v on v.organization_id=c.organization_id "
          + "and v.connection_id=c.id and v.provider=c.provider "
          + "and v.credential_revision=c.credential_revision ";

  private final JdbcTemplate jdbc;

  public ProviderConnectionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Connection create(
      HumanIdentity actor, UUID org, UUID id, String name, String workspace, Metadata metadata) {
    return safe(
        () -> {
          authority(actor, org, true);
          if (id == null) throw failure(400, "INVALID_INPUT");
          prepared(metadata, org, id, metadata == null ? null : metadata.scope().provider(), 1);
          String normalized = name(name);
          options(metadata.scope().provider(), workspace);
          OffsetDateTime now = now();
          insertVersion(metadata, now);
          jdbc.update(
              "insert into"
                  + " provider_connections(id,organization_id,provider,mode,name,workspace,state,configuration_revision,credential_revision,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,'ENABLED',1,1,?,?)",
              id,
              org,
              metadata.scope().provider().name(),
              mode(metadata.scope().provider()),
              normalized,
              workspace,
              now,
              now);
          Connection result = selected(org, id, false);
          event(actor, result, "CREATE");
          return result;
        },
        true);
  }

  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ)
  public Connection get(HumanIdentity actor, UUID org, UUID id) {
    return safe(
        () -> {
          authority(actor, org, false);
          return selected(org, id, false);
        });
  }

  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ)
  public Page list(HumanIdentity actor, UUID org, int page) {
    return safe(
        () -> {
          authority(actor, org, false);
          if (page < 0 || page > 100000) throw failure(400, "INVALID_PAGE");
          long total =
              jdbc.queryForObject(
                  "select count(*) from provider_connections where organization_id=?",
                  Long.class,
                  org);
          List<Connection> rows =
              jdbc.query(
                  SELECT_CURRENT
                      + "where c.organization_id=? order by c.created_at desc,c.id desc limit 20"
                      + " offset ?",
                  (rs, n) -> connection(rs),
                  org,
                  page * 20);
          return new Page(rows, total, page);
        });
  }

  /** Independently scoped historical lookup; never confers permission to dispatch. */
  @Transactional(
      readOnly = true,
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ)
  public Metadata credentialVersion(HumanIdentity actor, UUID org, UUID id, long revision) {
    return safe(
        () -> {
          authority(actor, org, false);
          Connection parent = selected(org, id, false);
          if (revision < 1 || revision > parent.credentialRevision())
            throw failure(404, "NOT_FOUND");
          return version(org, id, parent.provider(), revision);
        });
  }

  public Connection rename(
      HumanIdentity actor, UUID org, UUID id, long expectedRevision, String name) {
    return change(actor, org, id, expectedRevision, "RENAME", name, null, null);
  }

  public Connection rotate(
      HumanIdentity actor, UUID org, UUID id, long expectedRevision, Metadata metadata) {
    return change(actor, org, id, expectedRevision, "ROTATE", null, null, metadata);
  }

  public Connection setEnabled(
      HumanIdentity actor, UUID org, UUID id, long expectedRevision, boolean enabled) {
    return change(
        actor, org, id, expectedRevision, enabled ? "ENABLE" : "DISABLE", null, enabled, null);
  }

  private Connection change(
      HumanIdentity actor,
      UUID org,
      UUID id,
      long expected,
      String kind,
      String requestedName,
      Boolean enabled,
      Metadata metadata) {
    return safe(
        () -> {
          authority(actor, org, true);
          Connection old = selected(org, id, true);
          if (expected < 1) throw failure(400, "INVALID_REVISION");
          if (old.revision() != expected) throw failure(409, "REVISION_CONFLICT");
          long credentialRevision = old.credentialRevision();
          String newName = old.name(), state = old.state();
          if (kind.equals("RENAME")) {
            newName = name(requestedName);
            if (newName.equals(old.name())) throw failure(409, "NO_CHANGE");
          } else if (kind.equals("ROTATE")) {
            credentialRevision = next(credentialRevision);
            prepared(metadata, org, id, old.provider(), credentialRevision);
          } else {
            state = Boolean.TRUE.equals(enabled) ? "ENABLED" : "DISABLED";
            if (state.equals(old.state())) throw failure(409, "NO_CHANGE");
          }
          long revision = next(old.revision());
          OffsetDateTime now = now();
          if (kind.equals("ROTATE")) insertVersion(metadata, now);
          int changed =
              jdbc.update(
                  "update provider_connections set"
                      + " name=?,state=?,configuration_revision=?,credential_revision=?,updated_at=?"
                      + " where organization_id=? and id=? and configuration_revision=?",
                  newName,
                  state,
                  revision,
                  credentialRevision,
                  now,
                  org,
                  id,
                  expected);
          if (changed != 1) throw failure(409, "REVISION_CONFLICT");
          Connection result = selected(org, id, false);
          event(actor, result, kind);
          return result;
        });
  }

  private void authority(HumanIdentity actor, UUID org, boolean lock) {
    if (actor == null || actor.id() == null) throw failure(401, "UNAUTHORIZED");
    if (org == null) throw failure(403, "FORBIDDEN");
    transaction(lock);
    String suffix = lock ? " for update" : "";
    if (jdbc.queryForList("select id from organizations where id=?" + suffix, org).isEmpty())
      throw failure(403, "FORBIDDEN");
    var accounts =
        jdbc.queryForList(
            "select enabled,credential_version from human_accounts where id=?" + suffix,
            actor.id());
    var memberships =
        jdbc.queryForList(
            "select role from organization_memberships where organization_id=? and account_id=?"
                + suffix,
            org,
            actor.id());
    if (accounts.size() != 1
        || memberships.size() != 1
        || !Boolean.TRUE.equals(accounts.get(0).get("enabled"))
        || ((Number) accounts.get(0).get("credential_version")).longValue()
            != actor.credentialVersion()
        || !"ADMIN".equals(memberships.get(0).get("role"))) throw failure(403, "FORBIDDEN");
  }

  /** Joining requires the existing application transaction manager and JDBC resource. */
  private void transaction(boolean write) {
    Integer isolation =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Integer>)
                java.sql.Connection::getTransactionIsolation);
    boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
        || isolation == null
        || isolation
            != (write
                ? java.sql.Connection.TRANSACTION_READ_COMMITTED
                : java.sql.Connection.TRANSACTION_REPEATABLE_READ)
        || readOnly == write) throw failure(500, "PERSISTENCE_UNAVAILABLE");
  }

  private Connection selected(UUID org, UUID id, boolean lock) {
    if (lock) {
      jdbc.queryForList(
          "select id from provider_connections where organization_id=? and id=? for update",
          org,
          id);
    }
    var rows =
        jdbc.query(
            SELECT_CURRENT + "where c.organization_id=? and c.id=?",
            (rs, n) -> connection(rs),
            org,
            id);
    if (rows.isEmpty()) throw failure(404, "NOT_FOUND");
    return rows.get(0);
  }

  private Connection connection(ResultSet rs) throws SQLException {
    try {
      return validatedConnection(rs);
    } catch (RuntimeException ex) {
      throw failure(500, "PERSISTENCE_UNAVAILABLE");
    }
  }

  private Connection validatedConnection(ResultSet rs) throws SQLException {
    UUID org = rs.getObject("organization_id", UUID.class), id = rs.getObject("id", UUID.class);
    Provider provider = Provider.valueOf(rs.getString("provider"));
    String storedName = rs.getString("name"), workspace = rs.getString("workspace");
    String state = rs.getString("state"), storedMode = rs.getString("mode");
    long revision = rs.getLong("configuration_revision"),
        credentialRevision = rs.getLong("credential_revision");
    if (!name(storedName).equals(storedName)
        || !mode(provider).equals(storedMode)
        || !("ENABLED".equals(state) || "DISABLED".equals(state))
        || revision < 1
        || credentialRevision < 1
        || credentialRevision > revision) throw failure(500, "PERSISTENCE_UNAVAILABLE");
    options(provider, workspace);
    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class),
        updated = rs.getObject("updated_at", OffsetDateTime.class);
    if (created == null || updated == null || updated.isBefore(created))
      throw failure(500, "PERSISTENCE_UNAVAILABLE");
    return new Connection(
        org,
        id,
        provider,
        storedMode,
        storedName,
        workspace,
        state,
        revision,
        credentialRevision,
        created,
        updated,
        storedMetadata(rs, new Scope(org, id, provider, credentialRevision)));
  }

  private Metadata version(UUID org, UUID id, Provider provider, long revision) {
    var rows =
        jdbc.query(
            "select * from provider_credential_versions where organization_id=? and connection_id=?"
                + " and provider=? and credential_revision=?",
            (rs, n) -> storedMetadata(rs, new Scope(org, id, provider, revision)),
            org,
            id,
            provider.name(),
            revision);
    if (rows.size() != 1) throw failure(500, "PERSISTENCE_UNAVAILABLE");
    return rows.get(0);
  }

  private static Metadata storedMetadata(ResultSet rs, Scope scope) throws SQLException {
    if (!"v2".equals(rs.getString("hmac_domain_version")))
      throw failure(500, "PERSISTENCE_UNAVAILABLE");
    return new Metadata(
        scope,
        rs.getString("hmac_key_version"),
        new Reference(
            scope, rs.getString("external_locator"), rs.getString("external_key_version")),
        rs.getString("verifier"));
  }

  private void insertVersion(Metadata m, OffsetDateTime now) {
    jdbc.update(
        "insert into"
            + " provider_credential_versions(organization_id,connection_id,provider,credential_revision,external_locator,external_key_version,hmac_key_version,hmac_domain_version,verifier,created_at)"
            + " values (?,?,?,?,?,?,?,'v2',?,?)",
        m.scope().organization(),
        m.scope().connection(),
        m.scope().provider().name(),
        m.scope().credentialRevision(),
        m.reference().locator(),
        m.reference().keyVersion(),
        m.macVersion(),
        m.digest(),
        now);
  }

  private void event(HumanIdentity actor, Connection c, String kind) {
    jdbc.update(
        "insert into"
            + " provider_connection_events(id,organization_id,connection_id,configuration_revision,credential_revision,kind,provider,mode,name,workspace,state,actor_id,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        c.organization(),
        c.id(),
        c.revision(),
        c.credentialRevision(),
        kind,
        c.provider().name(),
        c.mode(),
        c.name(),
        c.workspace(),
        c.state(),
        actor.id(),
        c.updatedAt());
  }

  private static void prepared(Metadata m, UUID org, UUID id, Provider provider, long revision) {
    if (m == null
        || !m.scope().equals(new Scope(org, id, provider, revision))
        || !m.scope().equals(m.reference().scope())) throw failure(400, "INVALID_METADATA");
  }

  private static String mode(Provider provider) {
    return switch (provider) {
      case OPENAI -> "OPENAI_API_KEY";
      case ANTHROPIC -> "ANTHROPIC_API_KEY";
      case GOOGLE_GEMINI -> "GEMINI_API_KEY";
    };
  }

  private static void options(Provider provider, String workspace) {
    if (workspace != null
        && (provider != Provider.ANTHROPIC || !workspace.matches("wrkspc_[A-Za-z0-9]{1,128}")))
      throw failure(400, "INVALID_OPTIONS");
  }

  private static String name(String value) {
    if (value == null) throw failure(400, "INVALID_NAME");
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw failure(400, "INVALID_NAME");
      } else if (Character.isLowSurrogate(ch)) throw failure(400, "INVALID_NAME");
    }
    if (value
        .codePoints()
        .anyMatch(
            cp ->
                Character.isISOControl(cp)
                    || Character.getType(cp) == Character.LINE_SEPARATOR
                    || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR))
      throw failure(400, "INVALID_NAME");
    String normalized = value;
    int start = 0, end = normalized.length();
    while (start < end && whitespace(normalized.codePointAt(start)))
      start += Character.charCount(normalized.codePointAt(start));
    while (end > start && whitespace(normalized.codePointBefore(end)))
      end -= Character.charCount(normalized.codePointBefore(end));
    normalized = normalized.substring(start, end);
    int length = normalized.codePointCount(0, normalized.length());
    if (normalized.isBlank() || length > 100) throw failure(400, "INVALID_NAME");
    return normalized;
  }

  private static boolean whitespace(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xfeff;
  }

  private OffsetDateTime now() {
    // PostgreSQL current_timestamp is transaction start; sample wall clock after locks instead.
    String product;
    try {
      product =
          jdbc.execute(
              (org.springframework.jdbc.core.ConnectionCallback<String>)
                  connection -> connection.getMetaData().getDatabaseProductName());
    } catch (DataAccessException ex) {
      throw failure(500, "PERSISTENCE_UNAVAILABLE");
    }
    return jdbc.queryForObject(
        product.equals("PostgreSQL") ? "select clock_timestamp()" : "select current_timestamp",
        OffsetDateTime.class);
  }

  private static long next(long revision) {
    if (revision == Long.MAX_VALUE) throw failure(409, "REVISION_EXHAUSTED");
    return revision + 1;
  }

  private static ProviderConnectionFailure failure(int status, String code) {
    return new ProviderConnectionFailure(status, code);
  }

  private static <T> T safe(Supplier<T> action) {
    return safe(action, false);
  }

  private static <T> T safe(Supplier<T> action, boolean creating) {
    try {
      return action.get();
    } catch (ProviderConnectionFailure ex) {
      throw ex;
    } catch (DuplicateKeyException ex) {
      throw creating
          ? failure(409, "CONNECTION_CONFLICT")
          : failure(500, "PERSISTENCE_UNAVAILABLE");
    } catch (DataAccessException
        | IllegalArgumentException
        | NullPointerException
        | com.agentos.providercredentials.CredentialBoundary.Failure ex) {
      throw failure(500, "PERSISTENCE_UNAVAILABLE");
    }
  }
}

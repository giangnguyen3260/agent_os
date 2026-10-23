package com.agentos.governance.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Command;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Context;
import com.agentos.governance.api.ApprovalGrantManagementResponse.History;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Item;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Recipient;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Result;
import com.agentos.governance.api.ApprovalGrantManagementResponse.RevocationContext;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Selection;
import com.agentos.governance.api.MemberAccessResponse.Grant;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApprovalGrantManagementService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public ApprovalGrantManagementService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private void authorize(UUID org, HumanIdentity h) {
    if (h == null
        || jdbc.queryForObject(
                "select count(*) from human_accounts h join organization_memberships m on"
                    + " m.account_id=h.id where m.organization_id=? and h.id=? and h.enabled=true"
                    + " and h.credential_version=? and m.role='ADMIN'",
                Long.class,
                org,
                h.id(),
                h.credentialVersion())
            != 1) throw new AccessDeniedException("Request denied");
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    if (rows.isEmpty()) throw new EntityNotFoundException("Scoped resource not found");
    return rows.get(0);
  }

  private static Instant instant(Object v) {
    if (v == null) return null;
    if (v instanceof OffsetDateTime d) return d.toInstant();
    if (v instanceof Timestamp t) return t.toInstant();
    return (Instant) v;
  }

  private static OffsetDateTime timestamp(Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static void conflict() {
    throw new ResponseStatusException(HttpStatus.CONFLICT);
  }

  private int revision(UUID org, UUID account) {
    var rows =
        jdbc.queryForList(
            "select revision from approval_grant_ledgers where organization_id=? and account_id=?",
            org,
            account);
    return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("revision")).intValue();
  }

  private Recipient recipient(UUID org, UUID account, boolean required) {
    var a = one("select * from human_accounts where id=?", account);
    boolean present =
        jdbc.queryForObject(
                "select count(*) from organization_memberships where organization_id=? and"
                    + " account_id=?",
                Long.class,
                org,
                account)
            == 1;
    if (required && !present) throw new EntityNotFoundException("Member not found");
    return new Recipient(
        account, (String) a.get("display_name"), (Boolean) a.get("enabled"), present);
  }

  private Map<String, Object> team(UUID org, UUID id, boolean lock) {
    return one(
        "select * from teams where organization_id=? and id=?" + (lock ? " for update" : ""),
        org,
        id);
  }

  private Map<String, Object> grant(UUID org, UUID account, UUID id, boolean lock) {
    if (lock)
      one(
          "select id from approval_grants where organization_id=? and account_id=? and id=? for"
              + " update",
          org,
          account,
          id);
    return one(
        "select g.*,t.name as team_name from approval_grants g join teams t on"
            + " t.organization_id=g.organization_id and t.id=g.team_id where g.organization_id=?"
            + " and g.account_id=? and g.id=?",
        org,
        account,
        id);
  }

  private Grant dto(Map<String, Object> g, Instant at) {
    Instant expiry = instant(g.get("expires_at")), revoked = instant(g.get("revoked_at"));
    return new Grant(
        (UUID) g.get("id"),
        (UUID) g.get("organization_id"),
        (UUID) g.get("account_id"),
        (UUID) g.get("team_id"),
        (String) g.get("team_name"),
        (String) g.get("action_type"),
        (String) g.get("risk_level"),
        expiry,
        revoked,
        revoked != null ? "REVOKED" : !expiry.isAfter(at) ? "EXPIRED" : "UNEXPIRED");
  }

  private long count(
      UUID org, UUID account, UUID team, String action, String risk, Instant at, UUID except) {
    return jdbc.queryForObject(
        "select count(*) from approval_grants where organization_id=? and account_id=? and"
            + " team_id=? and action_type=? and risk_level=? and revoked_at is null and"
            + " expires_at>? and id<>?",
        Long.class,
        org,
        account,
        team,
        action,
        risk,
        timestamp(at),
        except);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID account, HumanIdentity h, UUID teamId, String risk) {
    authorize(org, h);
    if ((teamId == null) != (risk == null)
        || (risk != null && !List.of("LOW", "MEDIUM", "HIGH").contains(risk)))
      throw new IllegalArgumentException("Invalid selection");
    Recipient r = recipient(org, account, true);
    Map<String, Object> t = teamId == null ? null : team(org, teamId, false);
    Instant at = now();
    var blockers = new ArrayList<String>();
    if (account.equals(h.id())) blockers.add("SELF_ISSUANCE");
    if (!r.enabled()) blockers.add("RECIPIENT_DISABLED");
    Selection selection = null;
    if (t != null) {
      long count = count(org, account, teamId, "PUBLISH_PREVIEW", risk, at, new UUID(0, 0));
      selection =
          new Selection(
              teamId,
              (String) t.get("name"),
              (String) t.get("status"),
              "PUBLISH_PREVIEW",
              risk,
              count);
      if ("ARCHIVED".equals(t.get("status"))) blockers.add("TEAM_ARCHIVED");
      if (count > 0) blockers.add("DUPLICATE_SCOPE");
    }
    return new Context(
        org, account, at, revision(org, account), r, selection, blockers.isEmpty(), blockers);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RevocationContext revocationContext(UUID org, UUID account, UUID id, HumanIdentity h) {
    authorize(org, h);
    var g = grant(org, account, id, false);
    var r = recipient(org, account, false);
    Instant at = now();
    Grant d = dto(g, at);
    var blockers = d.revokedAt() == null ? List.<String>of() : List.of("ALREADY_REVOKED");
    return new RevocationContext(
        org,
        account,
        at,
        revision(org, account),
        r,
        d,
        grantHash(d),
        count(org, account, d.teamId(), d.actionType(), d.riskLevel(), at, id),
        blockers.isEmpty(),
        blockers);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public History history(UUID org, UUID account, UUID id, HumanIdentity h, int page) {
    authorize(org, h);
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    grant(org, account, id, false);
    var items =
        jdbc.query(
            "select * from approval_grant_management_events where organization_id=? and"
                + " account_id=? and grant_id=? order by recorded_at desc,id desc limit 20 offset"
                + " ?",
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getString("operation"),
                    rs.getObject("actor_id", UUID.class),
                    rs.getString("actor_name"),
                    rs.getString("reason"),
                    rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("revision")),
            org,
            account,
            id,
            page * 20);
    long total =
        jdbc.queryForObject(
            "select count(*) from approval_grant_management_events where organization_id=? and"
                + " account_id=? and grant_id=?",
            Long.class,
            org,
            account,
            id);
    return new History(org, account, id, now(), items, page, 20, total);
  }

  private List<Map<String, Object>> receipts(UUID org, HumanIdentity h, UUID key) {
    return jdbc.queryForList(
        "select e.*,r.idempotency_key,r.fingerprint from approval_grant_management_receipts r join"
            + " approval_grant_management_events e on e.id=r.event_id and"
            + " e.organization_id=r.organization_id and e.actor_id=r.actor_id where"
            + " r.organization_id=? and r.actor_id=? and r.idempotency_key=?",
        org,
        h.id(),
        key);
  }

  private Result result(Map<String, Object> e) {
    return new Result(
        (String) e.get("operation"),
        (UUID) e.get("organization_id"),
        (UUID) e.get("account_id"),
        (UUID) e.get("actor_id"),
        (String) e.get("actor_name"),
        (UUID) e.get("idempotency_key"),
        (UUID) e.get("grant_id"),
        (UUID) e.get("id"),
        ((Number) e.get("revision")).intValue(),
        instant(e.get("recorded_at")),
        instant(e.get("expires_at")),
        instant(e.get("revoked_at")));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Result receipt(UUID org, UUID account, HumanIdentity h, UUID key, String operation) {
    authorize(org, h);
    if (!List.of("ISSUE", "REVOKE").contains(operation))
      throw new IllegalArgumentException("Invalid operation");
    var rows = receipts(org, h, key);
    if (rows.isEmpty()) return null;
    Result r = result(rows.get(0));
    return r.accountId().equals(account) && r.operation().equals(operation) ? r : null;
  }

  private String hash(Map<String, Object> fields) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      json.writeValueAsString(new TreeMap<>(fields))
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash command", ex);
    }
  }

  public String commandHash(UUID org, UUID account, UUID id, Command c) {
    var m = new TreeMap<String, Object>();
    m.put("domain", "APPROVAL_GRANT_MANAGEMENT");
    m.put("version", 1);
    m.put("operation", id == null ? "ISSUE" : "REVOKE");
    m.put("organizationId", org.toString());
    m.put("accountId", account.toString());
    m.put("expectedRevision", c.expectedRevision());
    m.put("reason", c.reason());
    m.put("acknowledgement", true);
    if (id == null) {
      m.put("teamId", c.teamId().toString());
      m.put("actionType", c.actionType());
      m.put("riskLevel", c.riskLevel());
      m.put("durationHours", c.durationHours());
    } else {
      m.put("grantId", id.toString());
      m.put("grantFingerprint", c.grantFingerprint());
    }
    return hash(m);
  }

  private static String canonical(Instant t) {
    return t == null
        ? null
        : DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(t);
  }

  public String grantHash(Grant g) {
    var m = new TreeMap<String, Object>();
    m.put("id", g.id().toString());
    m.put("organizationId", g.organizationId().toString());
    m.put("accountId", g.accountId().toString());
    m.put("teamId", g.teamId().toString());
    m.put("actionType", g.actionType());
    m.put("riskLevel", g.riskLevel());
    m.put("expiresAt", canonical(g.expiresAt()));
    m.put("revokedAt", canonical(g.revokedAt()));
    return hash(m);
  }

  private static Command validate(Command c, boolean revoke) {
    if (c == null || c.expectedRevision() < 0 || !c.acknowledgement() || c.reason() == null)
      throw new IllegalArgumentException("Invalid command");
    String reason = c.reason().strip();
    int length = reason.codePointCount(0, reason.length());
    if (length < 1
        || length > 1000
        || reason.codePoints().anyMatch(x -> (x < 32 && x != 9 && x != 10 && x != 13) || x == 127))
      throw new IllegalArgumentException("Invalid reason");
    if (revoke) {
      if (c.grantFingerprint() == null
          || !c.grantFingerprint().matches("[0-9a-f]{64}")
          || c.teamId() != null
          || c.actionType() != null
          || c.riskLevel() != null
          || c.durationHours() != null) throw new IllegalArgumentException("Invalid command");
    } else if (c.grantFingerprint() != null
        || c.teamId() == null
        || !"PUBLISH_PREVIEW".equals(c.actionType())
        || c.riskLevel() == null
        || !List.of("LOW", "MEDIUM", "HIGH").contains(c.riskLevel())
        || c.durationHours() == null
        || c.durationHours() < 1
        || c.durationHours() > 168) throw new IllegalArgumentException("Invalid command");
    return new Command(
        c.expectedRevision(),
        c.teamId(),
        c.actionType(),
        c.riskLevel(),
        c.durationHours(),
        c.grantFingerprint(),
        reason,
        true);
  }

  public record Mutation(Result result, boolean replay) {}

  @Transactional
  public Mutation mutate(UUID org, UUID account, UUID id, HumanIdentity h, UUID key, Command c) {
    c = validate(c, id != null);
    // Never acquire a global account lock before the organization mutex and scope proof.
    if (h == null
        || jdbc.queryForList("select id from organizations where id=? for update", org).size() != 1)
      throw new AccessDeniedException("Request denied");
    authorize(org, h);
    boolean revoke = id != null;
    if (revoke != (c.grantFingerprint() != null))
      throw new IllegalArgumentException("Invalid operation");
    String fingerprint = commandHash(org, account, id, c);
    var existing = receipts(org, h, key);
    if (!existing.isEmpty()) {
      // Historical replay must never fall through into a new write or target lookup.
      one("select id from human_accounts where id=? for update", h.id());
      jdbc.queryForList(
          "select account_id from organization_memberships where organization_id=? and account_id=?"
              + " for update",
          org,
          h.id());
      authorize(org, h);
      if (!Objects.equals(fingerprint, existing.get(0).get("fingerprint"))) conflict();
      return new Mutation(result(existing.get(0)), true);
    }
    if (revoke) grant(org, account, id, false);
    else recipient(org, account, true);
    // UUID lexical ordering matches PostgreSQL UUID ordering (not Java's signed compareTo).
    var accounts = new ArrayList<>(List.of(h.id(), account));
    accounts.stream()
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(a -> one("select id from human_accounts where id=? for update", a));
    accounts.stream()
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .forEach(
            a ->
                jdbc.queryForList(
                    "select account_id from organization_memberships where organization_id=? and"
                        + " account_id=? for update",
                    org,
                    a));
    authorize(org, h);
    Recipient recipient = recipient(org, account, !revoke);
    int rev = revision(org, account);
    if (rev != c.expectedRevision() || rev == Integer.MAX_VALUE) conflict();
    Map<String, Object> g = null;
    Instant expiry;
    UUID grantId = id;
    if (revoke) {
      g = grant(org, account, id, true);
      if (g.get("revoked_at") != null || !grantHash(dto(g, now())).equals(c.grantFingerprint()))
        conflict();
      expiry = instant(g.get("expires_at"));
    } else {
      var t = team(org, c.teamId(), true);
      if (account.equals(h.id()) || !recipient.enabled())
        throw new IllegalArgumentException("Recipient ineligible");
      if ("ARCHIVED".equals(t.get("status"))) throw new IllegalArgumentException("Team archived");
      expiry = null;
      grantId = UUID.randomUUID();
    }
    Instant at = now();
    if (!revoke
        && count(org, account, c.teamId(), c.actionType(), c.riskLevel(), at, new UUID(0, 0)) > 0)
      conflict();
    // All validation precedes ledger creation; any downstream failure rolls back everything.
    if (jdbc.queryForList(
            "select revision from approval_grant_ledgers where organization_id=? and account_id=?"
                + " for update",
            org,
            account)
        .isEmpty())
      jdbc.update(
          "insert into approval_grant_ledgers(organization_id,account_id,revision) values (?,?,0)",
          org,
          account);
    jdbc.update(
        "update approval_grant_ledgers set revision=? where organization_id=? and account_id=?",
        rev + 1,
        org,
        account);
    if (revoke)
      jdbc.update(
          "update approval_grants set revoked_at=? where organization_id=? and account_id=? and"
              + " id=?",
          timestamp(at),
          org,
          account,
          id);
    else {
      expiry = at.plus(c.durationHours(), ChronoUnit.HOURS);
      jdbc.update(
          "insert into"
              + " approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at)"
              + " values (?,?,?,?,?,?,?)",
          grantId,
          org,
          account,
          c.teamId(),
          c.actionType(),
          c.riskLevel(),
          timestamp(expiry));
    }
    String actorName =
        (String)
            one("select display_name from human_accounts where id=?", h.id()).get("display_name");
    UUID event = UUID.randomUUID();
    String operation = revoke ? "REVOKE" : "ISSUE";
    jdbc.update(
        "insert into"
            + " approval_grant_management_events(id,organization_id,account_id,grant_id,actor_id,actor_name,operation,reason,revision,recorded_at,expires_at,revoked_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
        event,
        org,
        account,
        grantId,
        h.id(),
        actorName,
        operation,
        c.reason(),
        rev + 1,
        timestamp(at),
        timestamp(expiry),
        revoke ? timestamp(at) : null);
    jdbc.update(
        "insert into"
            + " approval_grant_management_receipts(organization_id,actor_id,idempotency_key,fingerprint,event_id)"
            + " values (?,?,?,?,?)",
        org,
        h.id(),
        key,
        fingerprint,
        event);
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'HUMAN',?,'APPROVAL_GRANT_MANAGED',?,?)",
        UUID.randomUUID(),
        org,
        h.id(),
        "{\"grantId\":\"" + grantId + "\",\"eventId\":\"" + event + "\"}",
        timestamp(at));
    return new Mutation(
        new Result(
            operation,
            org,
            account,
            h.id(),
            actorName,
            key,
            grantId,
            event,
            rev + 1,
            at,
            expiry,
            revoke ? at : null),
        false);
  }
}

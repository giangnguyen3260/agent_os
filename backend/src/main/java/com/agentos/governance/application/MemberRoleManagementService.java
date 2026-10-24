package com.agentos.governance.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.MemberRoleManagementCommandDeserializer;
import com.agentos.governance.api.MemberRoleManagementResponse.Command;
import com.agentos.governance.api.MemberRoleManagementResponse.Context;
import com.agentos.governance.api.MemberRoleManagementResponse.History;
import com.agentos.governance.api.MemberRoleManagementResponse.Item;
import com.agentos.governance.api.MemberRoleManagementResponse.Member;
import com.agentos.governance.api.MemberRoleManagementResponse.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
public class MemberRoleManagementService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public MemberRoleManagementService(JdbcTemplate jdbc, ObjectMapper json) {
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
            "select revision from member_role_ledgers where organization_id=? and account_id=?",
            org,
            account);
    return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("revision")).intValue();
  }

  private void scope(UUID org, UUID account) {
    one(
        "select account_id from organization_memberships where organization_id=? and account_id=?",
        org,
        account);
  }

  private Member member(UUID org, UUID account, HumanIdentity h) {
    scope(org, account);
    var row =
        one(
            "select h.display_name,h.enabled,m.role from organization_memberships m join"
                + " human_accounts h on h.id=m.account_id where m.organization_id=? and"
                + " m.account_id=?",
            org,
            account);
    return new Member(
        account,
        (String) row.get("display_name"),
        (Boolean) row.get("enabled"),
        (String) row.get("role"),
        account.equals(h.id()));
  }

  private long adminCount(UUID org) {
    return jdbc.queryForObject(
        "select count(*) from organization_memberships m join human_accounts h on h.id=m.account_id"
            + " where m.organization_id=? and m.role='ADMIN' and h.enabled=true",
        Long.class,
        org);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID account, HumanIdentity h, String selectedRole) {
    if (selectedRole != null) MemberRoleManagementCommandDeserializer.role(selectedRole);
    authorize(org, h);
    var m = member(org, account, h);
    long count = adminCount(org);
    var blockers = new ArrayList<String>();
    if (m.isSelf()) blockers.add("SELF_CHANGE");
    if (!m.enabled()) blockers.add("TARGET_DISABLED");
    if (m.currentRole().equals(selectedRole)) blockers.add("NO_CHANGE");
    if ("ADMIN".equals(m.currentRole()) && "MEMBER".equals(selectedRole) && count <= 1)
      blockers.add("LAST_ENABLED_ADMIN");
    return new Context(
        org,
        account,
        now(),
        revision(org, account),
        m,
        selectedRole,
        count,
        selectedRole != null && blockers.isEmpty(),
        blockers);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public History history(UUID org, UUID account, HumanIdentity h, int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid page");
    authorize(org, h);
    long total =
        jdbc.queryForObject(
            "select count(*) from member_role_events where organization_id=? and account_id=?",
            Long.class,
            org,
            account);
    if (total == 0) scope(org, account);
    var items =
        jdbc.query(
            "select * from member_role_events where organization_id=? and account_id=? order by"
                + " recorded_at desc,id desc limit 20 offset ?",
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("actor_id", UUID.class),
                    rs.getString("actor_name"),
                    rs.getString("before_role"),
                    rs.getString("after_role"),
                    rs.getString("reason"),
                    rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("revision")),
            org,
            account,
            page * 20);
    return new History(org, account, now(), items, page, 20, total);
  }

  private List<Map<String, Object>> receipts(UUID org, HumanIdentity h, UUID key) {
    return jdbc.queryForList(
        "select e.*,r.idempotency_key,r.fingerprint from member_role_receipts r join"
            + " member_role_events e on e.organization_id=r.organization_id and"
            + " e.actor_id=r.actor_id and e.id=r.event_id where r.organization_id=? and"
            + " r.actor_id=? and r.idempotency_key=?",
        org,
        h.id(),
        key);
  }

  private Result result(Map<String, Object> e) {
    return new Result(
        "CHANGE_ROLE",
        (UUID) e.get("organization_id"),
        (UUID) e.get("account_id"),
        (UUID) e.get("actor_id"),
        (String) e.get("actor_name"),
        (UUID) e.get("idempotency_key"),
        (UUID) e.get("id"),
        ((Number) e.get("revision")).intValue(),
        instant(e.get("recorded_at")),
        (String) e.get("before_role"),
        (String) e.get("after_role"));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Result receipt(UUID org, UUID account, HumanIdentity h, UUID key) {
    authorize(org, h);
    var rows = receipts(org, h, key);
    if (rows.isEmpty()) return null;
    var r = result(rows.getFirst());
    return account.equals(r.accountId()) ? r : null;
  }

  private static Command validate(Command c) {
    if (c == null || c.expectedRevision() < 0 || !c.acknowledgement() || c.reason() == null)
      throw new IllegalArgumentException("Invalid command");
    MemberRoleManagementCommandDeserializer.role(c.expectedCurrentRole());
    MemberRoleManagementCommandDeserializer.role(c.newRole());
    String reason = c.reason().strip();
    int length = reason.codePointCount(0, reason.length());
    if (length < 1
        || length > 1000
        || reason
            .codePoints()
            .anyMatch(
                x ->
                    (x < 32 && x != 9 && x != 10 && x != 13)
                        || x == 127
                        || (x >= 0xD800 && x <= 0xDFFF)))
      throw new IllegalArgumentException("Invalid reason");
    return new Command(c.expectedRevision(), c.expectedCurrentRole(), c.newRole(), reason, true);
  }

  public String commandHash(UUID org, UUID account, Command command) {
    Command c = validate(command);
    var m = new TreeMap<String, Object>();
    m.put("domain", "MEMBER_ROLE_MANAGEMENT");
    m.put("version", 1);
    m.put("operation", "CHANGE_ROLE");
    m.put("organizationId", org.toString());
    m.put("accountId", account.toString());
    m.put("expectedRevision", c.expectedRevision());
    m.put("expectedCurrentRole", c.expectedCurrentRole());
    m.put("newRole", c.newRole());
    m.put("reason", c.reason());
    m.put("acknowledgement", true);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json.writeValueAsString(m).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash command", ex);
    }
  }

  public record Mutation(Result result, boolean replay) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Mutation mutate(UUID org, UUID account, HumanIdentity h, UUID key, Command command) {
    Command c = validate(command);
    if (key == null) throw new IllegalArgumentException("Invalid identity");
    if (h == null
        || jdbc.queryForList("select id from organizations where id=? for update", org).size() != 1)
      throw new AccessDeniedException("Request denied");
    authorize(org, h);
    String fingerprint = commandHash(org, account, c);
    var existing = receipts(org, h, key);
    if (!existing.isEmpty()) {
      one("select id from human_accounts where id=? for update", h.id());
      jdbc.queryForList(
          "select account_id from organization_memberships where organization_id=? and account_id=?"
              + " for update",
          org,
          h.id());
      authorize(org, h);
      if (!Objects.equals(fingerprint, existing.getFirst().get("fingerprint"))) conflict();
      return new Mutation(result(existing.getFirst()), true);
    }
    // Prove organization scope before global profile access. Never lock actor first.
    scope(org, account);
    var accounts =
        List.of(h.id(), account).stream()
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    for (UUID a : accounts) one("select id from human_accounts where id=? for update", a);
    for (UUID a : accounts)
      jdbc.queryForList(
          "select account_id from organization_memberships where organization_id=? and account_id=?"
              + " for update",
          org,
          a);
    jdbc.queryForList(
        "select revision from member_role_ledgers where organization_id=? and account_id=? for"
            + " update",
        org,
        account);
    // All authority, state and time are evaluated after the last lock wait.
    authorize(org, h);
    Member target = member(org, account, h);
    int rev = revision(org, account);
    if (rev != c.expectedRevision()
        || !target.currentRole().equals(c.expectedCurrentRole())
        || rev == Integer.MAX_VALUE) conflict();
    if (target.isSelf() || !target.enabled() || c.newRole().equals(target.currentRole()))
      throw new IllegalArgumentException("Invalid transition");
    if ("ADMIN".equals(target.currentRole()) && adminCount(org) <= 1) conflict();
    Instant at = now();
    jdbc.update(
        "insert into member_role_ledgers(organization_id,account_id,revision) select ?,?,0 where"
            + " not exists (select 1 from member_role_ledgers where organization_id=? and"
            + " account_id=?)",
        org,
        account,
        org,
        account);
    jdbc.update(
        "update member_role_ledgers set revision=? where organization_id=? and account_id=?",
        rev + 1,
        org,
        account);
    jdbc.update(
        "update organization_memberships set role=? where organization_id=? and account_id=?",
        c.newRole(),
        org,
        account);
    String actorName =
        (String)
            one("select display_name from human_accounts where id=?", h.id()).get("display_name");
    UUID event = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " member_role_events(id,organization_id,account_id,actor_id,actor_name,before_role,after_role,reason,revision,recorded_at)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        event,
        org,
        account,
        h.id(),
        actorName,
        target.currentRole(),
        c.newRole(),
        c.reason(),
        rev + 1,
        timestamp(at));
    jdbc.update(
        "insert into"
            + " member_role_receipts(organization_id,actor_id,idempotency_key,fingerprint,event_id)"
            + " values (?,?,?,?,?)",
        org,
        h.id(),
        key,
        fingerprint,
        event);
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'HUMAN',?,'MEMBER_ROLE_CHANGED',?,?)",
        UUID.randomUUID(),
        org,
        h.id(),
        "{\"accountId\":\"" + account + "\",\"eventId\":\"" + event + "\"}",
        timestamp(at));
    return new Mutation(
        new Result(
            "CHANGE_ROLE",
            org,
            account,
            h.id(),
            actorName,
            key,
            event,
            rev + 1,
            at,
            target.currentRole(),
            c.newRole()),
        false);
  }
}

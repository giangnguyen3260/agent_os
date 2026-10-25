package com.agentos.invitation;

import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
class InvitationStore {
  final JdbcTemplate db;

  InvitationStore(JdbcTemplate db) {
    this.db = db;
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new EntityNotFoundException("Scoped resource not found");
    return rows.getFirst();
  }

  void orgLock(UUID org) {
    one("select id from organizations where id=? for update", org);
  }

  void humanLocks(UUID org, List<UUID> ids) {
    var sorted =
        ids.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    for (UUID id : sorted) one("select id from human_accounts where id=? for update", id);
    for (UUID id : sorted)
      db.queryForList(
          "select account_id from organization_memberships where organization_id=? and account_id=?"
              + " for update",
          org,
          id);
  }

  void authorize(UUID org, HumanIdentity h) {
    if (h == null || !issuer(org, h.id(), h.credentialVersion()))
      throw new AccessDeniedException("Request denied");
  }

  boolean issuer(UUID org, UUID id, long version) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "select count(*)>0 from human_accounts h join organization_memberships m on"
                + " m.account_id=h.id where h.id=? and h.enabled=true and h.credential_version=?"
                + " and m.organization_id=? and m.role='ADMIN'",
            Boolean.class,
            id,
            version,
            org));
  }

  Map<String, Object> invitation(UUID org, UUID id, boolean lock) {
    return one(
        "select * from organization_invitations where organization_id=? and id=?"
            + (lock ? " for update" : ""),
        org,
        id);
  }

  static Instant time(Object o) {
    if (o == null) return null;
    return o instanceof OffsetDateTime t
        ? t.toInstant()
        : o instanceof Timestamp t ? t.toInstant() : (Instant) o;
  }

  static Timestamp ts(Instant t) {
    return Timestamp.from(t);
  }

  static int number(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).intValue();
  }

  static String state(Map<String, Object> r, Instant at) {
    return "PENDING".equals(r.get("status")) && !time(r.get("expires_at")).isAfter(at)
        ? "EXPIRED"
        : (String) r.get("status");
  }

  InvitationDtos.Item item(Map<String, Object> r, Instant at) {
    String delivery =
        db.queryForObject(
            "select state from invitation_mail_jobs where invitation_id=? and generation=?",
            String.class,
            r.get("id"),
            r.get("generation"));
    return new InvitationDtos.Item(
        (UUID) r.get("id"),
        (String) r.get("email"),
        "MEMBER",
        state(r, at),
        number(r, "revision"),
        time(r.get("created_at")),
        time(r.get("expires_at")),
        time(r.get("accepted_at")),
        time(r.get("revoked_at")),
        delivery,
        time(r.get("last_requested_at")));
  }

  void cancel(UUID id) {
    db.update(
        "update invitation_mail_jobs set state='CANCELLED' where invitation_id=? and"
            + " state='QUEUED'",
        id);
  }

  void queue(UUID id, int generation, Instant at) {
    cancel(id);
    db.update(
        "insert into invitation_mail_jobs(id,invitation_id,generation,state,available_at) values"
            + " (?,?,?,'QUEUED',?)",
        UUID.randomUUID(),
        id,
        generation,
        ts(at));
  }

  UUID event(
      UUID org,
      UUID id,
      UUID actor,
      String name,
      String operation,
      int revision,
      Instant at,
      String email,
      Instant expiry,
      Boolean joined,
      Integer roleRevision) {
    UUID event = UUID.randomUUID();
    db.update(
        "insert into"
            + " invitation_events(id,organization_id,invitation_id,actor_id,actor_name,operation,revision,recorded_at,email,expires_at,membership_created,role_revision)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
        event,
        org,
        id,
        actor,
        name,
        operation,
        revision,
        ts(at),
        email,
        ts(expiry),
        joined,
        roleRevision);
    db.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'HUMAN',?,?,?,?)",
        UUID.randomUUID(),
        org,
        actor,
        operation,
        "{\"invitationId\":\"" + id + "\",\"eventId\":\"" + event + "\"}",
        ts(at));
    return event;
  }
}

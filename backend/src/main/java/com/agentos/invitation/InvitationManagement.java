package com.agentos.invitation;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationManagement {
  private final InvitationStore s;
  private final InvitationSettings settings;
  private final ObjectMapper json;

  InvitationManagement(InvitationStore s, InvitationSettings settings, ObjectMapper json) {
    this.s = s;
    this.settings = settings;
    this.json = json;
  }

  private void authorize(UUID org, HumanIdentity h) {
    s.authorize(org, h);
    settings.require();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public InvitationDtos.Page list(UUID org, HumanIdentity h, String status, int page) {
    authorize(org, h);
    if (page < 0
        || page > 100000
        || !java.util.Set.of("ALL", "PENDING", "ACCEPTED", "REVOKED", "EXPIRED").contains(status))
      InvitationWire.bad();
    Instant at = InvitationWire.now();
    String filter =
        "ALL".equals(status)
            ? ""
            : " and (case when status='PENDING' and expires_at<=? then 'EXPIRED' else status"
                + " end)=?";
    Object[] args =
        filter.isEmpty() ? new Object[] {org} : new Object[] {org, InvitationStore.ts(at), status};
    long total =
        s.db.queryForObject(
            "select count(*) from organization_invitations where organization_id=?" + filter,
            Long.class,
            args);
    var params = new java.util.ArrayList<Object>(java.util.Arrays.asList(args));
    params.add(page * 20);
    var items =
        s
            .db
            .queryForList(
                "select * from organization_invitations where organization_id=?"
                    + filter
                    + " order by created_at desc,id desc limit 20 offset ?",
                params.toArray())
            .stream()
            .map(r -> s.item(r, at))
            .toList();
    return new InvitationDtos.Page(org, at, items, page, 20, total);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public InvitationDtos.Context context(UUID org, UUID id, HumanIdentity h) {
    authorize(org, h);
    var r = s.invitation(org, id, false);
    Instant at = InvitationWire.now();
    boolean pending = "PENDING".equals(InvitationStore.state(r, at));
    return new InvitationDtos.Context(
        org,
        at,
        s.item(r, at),
        pending && !InvitationStore.time(r.get("last_requested_at")).plusSeconds(60).isAfter(at),
        pending);
  }

  private java.util.List<Map<String, Object>> receipts(UUID org, HumanIdentity h, UUID key) {
    return s.db.queryForList(
        "select e.*,r.idempotency_key,r.fingerprint from invitation_receipts r join"
            + " invitation_events e on e.id=r.event_id and e.organization_id=r.organization_id and"
            + " e.actor_id=r.actor_id where r.organization_id=? and r.actor_id=? and"
            + " r.idempotency_key=?",
        org,
        h.id(),
        key);
  }

  private InvitationDtos.Receipt receipt(Map<String, Object> r) {
    return new InvitationDtos.Receipt(
        (String) r.get("operation"),
        (UUID) r.get("organization_id"),
        (UUID) r.get("invitation_id"),
        (UUID) r.get("idempotency_key"),
        InvitationStore.number(r, "revision"),
        (UUID) r.get("id"),
        (UUID) r.get("actor_id"),
        (String) r.get("actor_name"),
        InvitationStore.time(r.get("recorded_at")),
        (String) r.get("email"),
        "MEMBER",
        InvitationStore.time(r.get("expires_at")));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public InvitationDtos.Receipt receipt(UUID org, HumanIdentity h, UUID key) {
    authorize(org, h);
    var rows = receipts(org, h, key);
    return rows.isEmpty() ? null : receipt(rows.getFirst());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InvitationDtos.Mutation mutate(
      UUID org,
      UUID target,
      HumanIdentity h,
      UUID key,
      String operation,
      String email,
      Integer expected) {
    if (h == null)
      throw new org.springframework.security.access.AccessDeniedException("Request denied");
    if (key == null
        || !java.util.Set.of("CREATE_INVITATION", "RESEND_INVITATION", "REVOKE_INVITATION")
            .contains(operation)) InvitationWire.bad();
    if ("CREATE_INVITATION".equals(operation)) {
      email = InvitationWire.email(email);
      if (target != null || expected != null) InvitationWire.bad();
    } else if (target == null || expected == null || expected < 1 || email != null)
      InvitationWire.bad();
    s.orgLock(org);
    s.humanLocks(org, java.util.List.of(h.id()));
    authorize(org, h);
    String hash = InvitationFingerprint.hash(json, org, h.id(), target, operation, email, expected);
    var prior = receipts(org, h, key);
    if (!prior.isEmpty()) {
      if (!hash.equals(prior.getFirst().get("fingerprint"))) throw InvitationFailure.conflict();
      return new InvitationDtos.Mutation(receipt(prior.getFirst()), true);
    }
    Instant at = InvitationWire.now();
    UUID id = target;
    int revision = 1;
    Instant expiry;
    if ("CREATE_INVITATION".equals(operation)) {
      if (!s.db
          .queryForList(
              "select id from organization_invitations where organization_id=? and email=? and"
                  + " status='PENDING' and expires_at>?",
              org,
              email,
              InvitationStore.ts(at))
          .isEmpty()) throw InvitationFailure.conflict();
      id = UUID.randomUUID();
      expiry = at.plusSeconds(72 * 3600);
      s.db.update(
          "insert into"
              + " organization_invitations(id,organization_id,email,status,revision,generation,issuer_id,issuer_version,created_at,expires_at,last_requested_at)"
              + " values (?,?,?,'PENDING',1,1,?,?,?,?,?)",
          id,
          org,
          email,
          h.id(),
          h.credentialVersion(),
          InvitationStore.ts(at),
          InvitationStore.ts(expiry),
          InvitationStore.ts(at));
      s.queue(id, 1, at);
    } else {
      var r = s.invitation(org, id, true);
      authorize(org, h);
      at = InvitationWire.now();
      int old = InvitationStore.number(r, "revision");
      if (expected == null
          || expected != old
          || old == Integer.MAX_VALUE
          || !"PENDING".equals(InvitationStore.state(r, at))) throw InvitationFailure.conflict();
      revision = old + 1;
      email = (String) r.get("email");
      expiry = InvitationStore.time(r.get("expires_at"));
      if ("RESEND_INVITATION".equals(operation)) {
        if (InvitationStore.time(r.get("last_requested_at")).plusSeconds(60).isAfter(at))
          throw new InvitationFailure(429, "TOO_MANY_REQUESTS");
        int generation = InvitationStore.number(r, "generation");
        if (generation == Integer.MAX_VALUE) throw InvitationFailure.conflict();
        s.db.update(
            "update organization_invitations set"
                + " revision=?,generation=?,issuer_id=?,issuer_version=?,last_requested_at=? where"
                + " id=?",
            revision,
            generation + 1,
            h.id(),
            h.credentialVersion(),
            InvitationStore.ts(at),
            id);
        s.queue(id, generation + 1, at);
      } else {
        s.db.update(
            "update organization_invitations set"
                + " revision=?,status='REVOKED',revoked_at=?,token_digest=null where id=?",
            revision,
            InvitationStore.ts(at),
            id);
        s.cancel(id);
      }
    }
    String name =
        (String)
            s.one("select display_name from human_accounts where id=?", h.id()).get("display_name");
    UUID event = s.event(org, id, h.id(), name, operation, revision, at, email, expiry, null, null);
    s.db.update(
        "insert into"
            + " invitation_receipts(organization_id,actor_id,idempotency_key,fingerprint,event_id)"
            + " values (?,?,?,?,?)",
        org,
        h.id(),
        key,
        hash,
        event);
    return new InvitationDtos.Mutation(
        new InvitationDtos.Receipt(
            operation, org, id, key, revision, event, h.id(), name, at, email, "MEMBER", expiry),
        false);
  }
}

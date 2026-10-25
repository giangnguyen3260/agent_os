package com.agentos.invitation;

import com.agentos.auth.HumanIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationRedemption {
  private final InvitationStore s;
  private final InvitationSettings settings;
  private final PasswordEncoder encoder;

  InvitationRedemption(InvitationStore s, InvitationSettings settings, PasswordEncoder encoder) {
    this.s = s;
    this.settings = settings;
    this.encoder = encoder;
  }

  private Map<String, Object> candidate(String token) {
    settings.require();
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw InvitationFailure.invalid();
    var rows =
        s.db.queryForList(
            "select * from organization_invitations where token_digest=?",
            InvitationWire.digest(token));
    if (rows.isEmpty()) throw InvitationFailure.invalid();
    return rows.getFirst();
  }

  private void valid(Map<String, Object> r, String token, Instant at) {
    if (!InvitationWire.digest(token).equals(r.get("token_digest"))
        || !"PENDING".equals(InvitationStore.state(r, at))
        || !s.issuer(
            (UUID) r.get("organization_id"),
            (UUID) r.get("issuer_id"),
            ((Number) r.get("issuer_version")).longValue())) throw InvitationFailure.invalid();
  }

  private Map<String, Object> recipient(String email) {
    var rows = s.db.queryForList("select * from human_accounts where email=?", email);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private String state(Map<String, Object> account, HumanIdentity h, String email) {
    if (account != null && !Boolean.TRUE.equals(account.get("enabled")))
      throw InvitationFailure.invalid();
    if (h != null) {
      if (!email.equals(h.email())) return "MISMATCH";
      if (account == null
          || !h.id().equals(account.get("id"))
          || h.credentialVersion() != ((Number) account.get("credential_version")).longValue())
        throw InvitationFailure.invalid();
      return "READY";
    }
    return account == null ? "NEW_ACCOUNT" : "SIGN_IN";
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public InvitationDtos.Inspection inspect(String token, HumanIdentity h) {
    var r = candidate(token);
    valid(r, token, InvitationWire.now());
    String email = (String) r.get("email");
    String state = state(recipient(email), h, email);
    String orgName =
        (String)
            s.one("select name from organizations where id=?", r.get("organization_id"))
                .get("name");
    return new InvitationDtos.Inspection(
        state,
        (UUID) r.get("organization_id"),
        orgName,
        email.substring(0, 1) + "***@" + email.substring(email.indexOf('@') + 1),
        "MEMBER",
        InvitationStore.time(r.get("expires_at")));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InvitationDtos.Acceptance accept(
      String token, String displayName, String password, HumanIdentity h) {
    var initial = candidate(token);
    UUID org = (UUID) initial.get("organization_id");
    UUID id = (UUID) initial.get("id");
    String email = (String) initial.get("email");
    s.orgLock(org);
    // Supported resends are stabilized by this organization mutex.
    var current = s.invitation(org, id, false);
    String emailDigest = InvitationWire.digest(email);
    s.db.update(
        "insert into invitation_email_locks(email_digest) values (?) on conflict do nothing",
        emailDigest);
    s.one(
        "select email_digest from invitation_email_locks where email_digest=? for update",
        emailDigest);
    var account = recipient(email);
    var ids = new ArrayList<UUID>();
    ids.add((UUID) current.get("issuer_id"));
    if (account != null) ids.add((UUID) account.get("id"));
    if (h != null) ids.add(h.id());
    s.humanLocks(org, ids);
    var r = s.invitation(org, id, true);
    account = recipient(email);
    // Retained ledger is the final existing row lock; evaluate capability time after it.
    if (account != null)
      s.db.queryForList(
          "select revision from member_role_ledgers where organization_id=? and account_id=? for"
              + " update",
          org,
          account.get("id"));
    Instant at = InvitationWire.now();
    valid(r, token, at);
    String state = state(account, h, email);
    if ("MISMATCH".equals(state)) throw new InvitationFailure(403, "INVITATION_ACCOUNT_MISMATCH");
    if ("SIGN_IN".equals(state)) throw new InvitationFailure(401, "UNAUTHORIZED");
    boolean created = "NEW_ACCOUNT".equals(state);
    UUID accountId;
    if (created) {
      String name = InvitationWire.name(displayName);
      String secret = InvitationWire.password(password);
      accountId = UUID.randomUUID();
      try {
        s.db.update(
            "insert into"
                + " human_accounts(id,email,display_name,password_hash,enabled,credential_version)"
                + " values (?,?,?,?,true,0)",
            accountId,
            email,
            name,
            encoder.encode(secret));
      } catch (DuplicateKeyException e) {
        throw InvitationFailure.conflict();
      }
    } else {
      if (displayName != null || password != null) InvitationWire.bad();
      accountId = h.id();
    }
    int old = InvitationStore.number(r, "revision");
    if (old == Integer.MAX_VALUE) throw InvitationFailure.conflict();
    var memberships =
        s.db.queryForList(
            "select role from organization_memberships where organization_id=? and account_id=?",
            org,
            accountId);
    boolean already = !memberships.isEmpty();
    String role = already ? (String) memberships.getFirst().get("role") : "MEMBER";
    Integer roleRevision = null;
    if (!already) {
      var ledgers =
          s.db.queryForList(
              "select revision from member_role_ledgers where organization_id=? and account_id=?"
                  + " for update",
              org,
              accountId);
      int previous = ledgers.isEmpty() ? 0 : InvitationStore.number(ledgers.getFirst(), "revision");
      if (previous == Integer.MAX_VALUE) throw InvitationFailure.conflict();
      roleRevision = previous + 1;
      if (ledgers.isEmpty())
        s.db.update(
            "insert into member_role_ledgers(organization_id,account_id,revision) values (?,?,?)",
            org,
            accountId,
            roleRevision);
      else
        s.db.update(
            "update member_role_ledgers set revision=? where organization_id=? and account_id=?",
            roleRevision,
            org,
            accountId);
      s.db.update(
          "insert into organization_memberships(account_id,organization_id,role) values"
              + " (?,?,'MEMBER')",
          accountId,
          org);
    }
    // Final check follows every row/unique-key wait, including account creation and ledger join.
    // Any earlier new human or membership writes roll back together on expiry or lost authority.
    at = InvitationWire.now();
    valid(r, token, at);
    var finalRecipient = recipient(email);
    if (created) {
      if (finalRecipient == null
          || !accountId.equals(finalRecipient.get("id"))
          || !Boolean.TRUE.equals(finalRecipient.get("enabled"))
          || ((Number) finalRecipient.get("credential_version")).longValue() != 0L)
        throw InvitationFailure.invalid();
    } else if (!"READY".equals(state(finalRecipient, h, email))) {
      throw InvitationFailure.invalid();
    }
    s.db.update(
        "update organization_invitations set"
            + " status='ACCEPTED',accepted_at=?,token_digest=null,revision=? where id=?",
        InvitationStore.ts(at),
        old + 1,
        id);
    s.cancel(id);
    String name =
        (String)
            s.one("select display_name from human_accounts where id=?", accountId)
                .get("display_name");
    s.event(
        org,
        id,
        accountId,
        name,
        "ACCEPT_INVITATION",
        old + 1,
        at,
        email,
        InvitationStore.time(r.get("expires_at")),
        !already,
        roleRevision);
    return new InvitationDtos.Acceptance(org, role, created, already);
  }
}

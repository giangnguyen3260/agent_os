package com.agentos.invitation;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvitationDispatch {
  private final InvitationStore s;
  private final InvitationSettings settings;
  private final JavaMailSender mail;
  private final TransactionTemplate tx;
  private final SecureRandom random = new SecureRandom();

  InvitationDispatch(
      InvitationStore s,
      InvitationSettings settings,
      JavaMailSender mail,
      PlatformTransactionManager manager) {
    this.s = s;
    this.settings = settings;
    this.mail = mail;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(30);
  }

  @Scheduled(fixedDelayString = "${agentos.invitations.dispatch-delay-ms:1000}")
  public void dispatch() {
    if (!settings.enabled) return;
    // Candidate selection holds no row lock. The owning organization is always locked first.
    var candidates =
        s.db.queryForList(
            "select j.id,j.invitation_id,i.organization_id from invitation_mail_jobs j join"
                + " organization_invitations i on i.id=j.invitation_id where j.state='QUEUED' and"
                + " j.available_at<=current_timestamp order by j.available_at,j.id limit 1");
    if (candidates.isEmpty()) return;
    var candidate = candidates.getFirst();
    tx.executeWithoutResult(
        status -> {
          UUID org = (UUID) candidate.get("organization_id");
          UUID id = (UUID) candidate.get("invitation_id");
          UUID jobId = (UUID) candidate.get("id");
          s.orgLock(org);
          var initial = s.invitation(org, id, false);
          s.humanLocks(org, List.of((UUID) initial.get("issuer_id")));
          var r = s.invitation(org, id, true);
          var job = s.one("select * from invitation_mail_jobs where id=? for update", jobId);
          Instant at = InvitationWire.now();
          if (!"QUEUED".equals(job.get("state"))
              || InvitationStore.time(job.get("available_at")).isAfter(at)) return;
          if (InvitationStore.number(job, "generation") != InvitationStore.number(r, "generation")
              || !"PENDING".equals(InvitationStore.state(r, at))
              || !s.issuer(
                  org, (UUID) r.get("issuer_id"), ((Number) r.get("issuer_version")).longValue())) {
            s.db.update("update invitation_mail_jobs set state='CANCELLED' where id=?", jobId);
            return;
          }
          byte[] bytes = new byte[32];
          random.nextBytes(bytes);
          String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
          SimpleMailMessage message = new SimpleMailMessage();
          message.setFrom(settings.from);
          message.setTo((String) r.get("email"));
          message.setSubject("Workspace invitation");
          message.setText(
              "Review your invitation before "
                  + InvitationStore.time(r.get("expires_at"))
                  + ": "
                  + settings.frontend.replaceAll("/+$", "")
                  + "/invitation#token="
                  + token);
          int attempts = InvitationStore.number(job, "attempts") + 1;
          try {
            mail.send(message);
          } catch (MailException e) {
            s.db.update(
                "update invitation_mail_jobs set attempts=?,state=?,available_at=? where id=?",
                attempts,
                attempts >= 5 ? "FAILED" : "QUEUED",
                InvitationStore.ts(InvitationWire.now().plusSeconds(settings.retrySeconds)),
                jobId);
            return;
          }
          // SMTP ACK precedes installing the digest; a failed replacement leaves the old digest
          // intact.
          s.db.update(
              "update organization_invitations set token_digest=? where id=?",
              InvitationWire.digest(token),
              id);
          s.db.update(
              "update invitation_mail_jobs set attempts=?,state='SENT' where id=?",
              attempts,
              jobId);
        });
  }
}

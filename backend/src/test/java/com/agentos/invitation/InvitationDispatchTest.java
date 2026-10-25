package com.agentos.invitation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class InvitationDispatchTest {
  JdbcTemplate db;
  InvitationStore store;
  JavaMailSender mail;
  InvitationDispatch worker;
  UUID org, id, issuer, job;

  @BeforeEach
  void setup() {
    org = UUID.randomUUID();
    id = UUID.randomUUID();
    issuer = UUID.randomUUID();
    job = UUID.randomUUID();
    db = mock(JdbcTemplate.class);
    store = spy(new InvitationStore(db));
    mail = mock(JavaMailSender.class);
    var env =
        new MockEnvironment()
            .withProperty("agentos.invitations.enabled", "true")
            .withProperty("agentos.invitations.frontend-base-url", "http://localhost:3000")
            .withProperty("agentos.invitations.from", "invites@example.test");
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    worker = new InvitationDispatch(store, new InvitationSettings(env), mail, manager);
    when(db.queryForList(anyString()))
        .thenReturn(List.of(Map.of("id", job, "invitation_id", id, "organization_id", org)));
    doNothing().when(store).orgLock(org);
    doNothing().when(store).humanLocks(org, List.of(issuer));
    var invitation =
        Map.<String, Object>of(
            "issuer_id",
            issuer,
            "issuer_version",
            0L,
            "generation",
            1,
            "status",
            "PENDING",
            "expires_at",
            Timestamp.from(Instant.now().plusSeconds(3600)),
            "email",
            "recipient@example.test");
    doReturn(invitation).when(store).invitation(org, id, false);
    doReturn(invitation).when(store).invitation(org, id, true);
    doReturn(
            Map.of(
                "state",
                "QUEUED",
                "available_at",
                Timestamp.from(Instant.EPOCH),
                "generation",
                1,
                "attempts",
                0))
        .when(store)
        .one(anyString(), any());
    doReturn(true).when(store).issuer(org, issuer, 0L);
  }

  @Test
  void failedSmtpDoesNotInstallReplacementDigest() {
    doThrow(new MailSendException("unit failure")).when(mail).send(any(SimpleMailMessage.class));
    worker.dispatch();
    verify(db, never())
        .update(startsWith("update organization_invitations set token_digest="), any(), any());
    verify(db)
        .update(
            startsWith("update invitation_mail_jobs set attempts="),
            eq(1),
            eq("QUEUED"),
            any(),
            eq(job));
  }

  @Test
  void acknowledgedSmtpInstallsDigestAfterSend() {
    worker.dispatch();
    var order = inOrder(mail, db);
    order.verify(mail).send(any(SimpleMailMessage.class));
    order
        .verify(db)
        .update(
            startsWith("update organization_invitations set token_digest="),
            matches("[a-f0-9]{64}"),
            eq(id));
  }

  @Test
  void lostIssuerCancelsWithoutSmtp() {
    doReturn(false).when(store).issuer(org, issuer, 0L);
    worker.dispatch();
    verify(mail, never()).send(any(SimpleMailMessage.class));
    verify(db).update("update invitation_mail_jobs set state='CANCELLED' where id=?", job);
  }
}

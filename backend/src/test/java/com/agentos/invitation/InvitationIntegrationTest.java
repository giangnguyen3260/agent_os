package com.agentos.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:invitation_integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
      "agentos.invitations.enabled=true",
      "agentos.invitations.frontend-base-url=http://localhost:3000",
      "agentos.invitations.from=invites@example.test",
      "agentos.invitations.dispatch-delay-ms=60000",
      "management.health.mail.enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationIntegrationTest {
  @org.springframework.boot.test.mock.mockito.MockBean InvitationDispatch disabledWorker;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired InvitationManagement management;
  @Autowired InvitationRedemption redemption;
  UUID org, admin, member;
  String email;

  @BeforeEach
  void setup() {
    // Isolated database, and no queued SMTP work survives a test.
    db.update("update invitation_mail_jobs set state='CANCELLED' where state='QUEUED'");
    org = UUID.randomUUID();
    db.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Invitations',?,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    admin = account("ADMIN");
    member = account("MEMBER");
    email = UUID.randomUUID() + "@example.test";
  }

  UUID account(String role) {
    UUID id = UUID.randomUUID();
    db.update(
        "insert into human_accounts(id,email,display_name,password_hash,enabled) values"
            + " (?,?,'Original name','original-hash',true)",
        id,
        id + "@example.test");
    db.update(
        "insert into organization_memberships(account_id,organization_id,role) values (?,?,?)",
        id,
        org,
        role);
    return id;
  }

  HumanIdentity human(UUID id) {
    return new HumanIdentity(id, id + "@example.test", "Untrusted name");
  }

  RequestPostProcessor actor(UUID id) {
    return authentication(new UsernamePasswordAuthenticationToken(human(id), null, List.of()));
  }

  String root() {
    return "/api/v1/organizations/" + org + "/invitations";
  }

  InvitationDtos.Mutation create(UUID key) {
    return management.mutate(org, null, human(admin), key, "CREATE_INVITATION", email, null);
  }

  UUID invitation() {
    return create(UUID.randomUUID()).receipt().invitationId();
  }

  String capability(UUID id) {
    String token =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                UUID.randomUUID()
                    .toString()
                    .substring(0, 32)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    db.update(
        "update organization_invitations set token_digest=? where id=?",
        InvitationWire.digest(token),
        id);
    db.update("update invitation_mail_jobs set state='SENT' where invitation_id=?", id);
    return token;
  }

  @Test
  void anonymousAdminRouteIs401() throws Exception {
    mvc.perform(get(root())).andExpect(status().isUnauthorized());
  }

  @Test
  void memberCannotList() throws Exception {
    mvc.perform(get(root()).with(actor(member))).andExpect(status().isForbidden());
  }

  @Test
  void publicInspectRequiresCsrf() throws Exception {
    mvc.perform(
            post("/api/v1/auth/invitations/inspect")
                .contentType("application/json")
                .content("{\"token\":\"x\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidPublicCapabilityHasSafeCode() throws Exception {
    mvc.perform(
            post("/api/v1/auth/invitations/inspect")
                .with(csrf())
                .contentType("application/json")
                .content("{\"token\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INVITATION"));
  }

  @Test
  void unknownQueryRejected() throws Exception {
    mvc.perform(get(root() + "?size=100").with(actor(admin))).andExpect(status().isBadRequest());
  }

  @Test
  void duplicateQueryRejected() throws Exception {
    mvc.perform(get(root() + "?page=0&page=1").with(actor(admin)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownBodyRejected() throws Exception {
    mvc.perform(
            post(root())
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(
                    "{\"email\":\"a@example.test\",\"acknowledgement\":true,\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void duplicateBodyRejected() throws Exception {
    mvc.perform(
            post(root())
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(
                    "{\"email\":\"a@example.test\",\"email\":\"b@example.test\",\"acknowledgement\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void coercedAcknowledgementRejected() throws Exception {
    mvc.perform(
            post(root())
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"email\":\"a@example.test\",\"acknowledgement\":\"true\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void exactReplayIsImmutable() {
    UUID key = UUID.randomUUID();
    var first = create(key);
    assertThat(create(key).replay()).isTrue();
    assertThat(management.receipt(org, human(admin), key)).isEqualTo(first.receipt());
  }

  @Test
  void changedReplayConflicts() {
    UUID key = UUID.randomUUID();
    create(key);
    email = "other@example.test";
    assertThatThrownBy(() -> create(key)).isInstanceOf(InvitationFailure.class);
  }

  @Test
  void liveDuplicateConflicts() {
    invitation();
    assertThatThrownBy(() -> invitation()).isInstanceOf(InvitationFailure.class);
  }

  @Test
  void absentReceiptIsNull() {
    assertThat(management.receipt(org, human(admin), UUID.randomUUID())).isNull();
  }

  @Test
  void actorSnapshotComesFromDatabase() {
    assertThat(create(UUID.randomUUID()).receipt().actorName()).isEqualTo("Original name");
  }

  @Test
  void contextDoesNotLeakForeignInvitation() {
    UUID id = invitation();
    UUID other = UUID.randomUUID();
    assertThatThrownBy(() -> management.context(other, id, human(admin)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void immediateResendIs429() {
    UUID id = invitation();
    assertThatThrownBy(
            () ->
                management.mutate(
                    org, id, human(admin), UUID.randomUUID(), "RESEND_INVITATION", null, 1))
        .isInstanceOfSatisfying(InvitationFailure.class, e -> assertThat(e.status).isEqualTo(429));
  }

  @Test
  void revokeConsumesCapability() {
    UUID id = invitation();
    String token = capability(id);
    management.mutate(org, id, human(admin), UUID.randomUUID(), "REVOKE_INVITATION", null, 1);
    assertThatThrownBy(() -> redemption.inspect(token, null)).isInstanceOf(InvitationFailure.class);
  }

  @Test
  void historicalCreateReplayAfterRevoke() {
    UUID key = UUID.randomUUID();
    var first = create(key);
    management.mutate(
        org,
        first.receipt().invitationId(),
        human(admin),
        UUID.randomUUID(),
        "REVOKE_INVITATION",
        null,
        1);
    assertThat(create(key).receipt()).isEqualTo(first.receipt());
  }

  @Test
  void issuerLossInvalidatesInspection() {
    UUID id = invitation();
    String token = capability(id);
    db.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        admin);
    assertThatThrownBy(() -> redemption.inspect(token, null)).isInstanceOf(InvitationFailure.class);
  }

  @Test
  void expiredTokenIsInvalid() {
    UUID id = invitation();
    String token = capability(id);
    db.update(
        "update organization_invitations set created_at=?,expires_at=? where id=?",
        Timestamp.from(Instant.now().minusSeconds(100)),
        Timestamp.from(Instant.now().minusSeconds(1)),
        id);
    assertThatThrownBy(() -> redemption.inspect(token, null)).isInstanceOf(InvitationFailure.class);
  }

  @Test
  void newAccountAcceptanceIsSingleUseAndNoSession() {
    UUID id = invitation();
    String token = capability(id);
    var result = redemption.accept(token, " Invited human ", "long-new-password", null);
    assertThat(result.requiresLogin()).isTrue();
    assertThat(result.role()).isEqualTo("MEMBER");
    assertThatThrownBy(() -> redemption.accept(token, "Invited human", "long-new-password", null))
        .isInstanceOf(InvitationFailure.class);
    assertThat(
            db.queryForObject(
                "select count(*) from human_accounts where email=?", Long.class, email))
        .isEqualTo(1);
  }

  @Test
  void existingAnonymousMustSignIn() {
    email = member + "@example.test";
    String token = capability(invitation());
    assertThat(redemption.inspect(token, null).state()).isEqualTo("SIGN_IN");
    assertThatThrownBy(() -> redemption.accept(token, "Replacement", "long-new-password", null))
        .isInstanceOfSatisfying(InvitationFailure.class, e -> assertThat(e.status).isEqualTo(401));
  }

  @Test
  void mismatchCannotCreateAccount() {
    String token = capability(invitation());
    assertThat(redemption.inspect(token, human(member)).state()).isEqualTo("MISMATCH");
    assertThatThrownBy(
            () -> redemption.accept(token, "Replacement", "long-new-password", human(member)))
        .isInstanceOfSatisfying(
            InvitationFailure.class,
            e -> assertThat(e.code).isEqualTo("INVITATION_ACCOUNT_MISMATCH"));
  }

  @Test
  void existingAdminMembershipAndCredentialsPreserved() {
    email = admin + "@example.test";
    String token = capability(invitation());
    var result = redemption.accept(token, null, null, human(admin));
    assertThat(result.alreadyMember()).isTrue();
    assertThat(result.role()).isEqualTo("ADMIN");
    assertThat(result.requiresLogin()).isFalse();
    assertThat(
            db.queryForObject(
                "select password_hash from human_accounts where id=?", String.class, admin))
        .isEqualTo("original-hash");
    assertThat(
            db.queryForObject(
                "select count(*) from member_role_ledgers where organization_id=? and account_id=?",
                Long.class,
                org,
                admin))
        .isZero();
  }

  @Test
  void disabledRecipientGenericInvalid() {
    email = member + "@example.test";
    String token = capability(invitation());
    db.update("update human_accounts set enabled=false where id=?", member);
    assertThatThrownBy(() -> redemption.inspect(token, null))
        .isInstanceOfSatisfying(
            InvitationFailure.class, e -> assertThat(e.code).isEqualTo("INVALID_INVITATION"));
  }

  @Test
  void rejoinAdvancesRetainedLedger() {
    email = member + "@example.test";
    db.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    db.update(
        "insert into member_role_ledgers(organization_id,account_id,revision) values (?,?,8)",
        org,
        member);
    redemption.accept(capability(invitation()), null, null, human(member));
    assertThat(
            db.queryForObject(
                "select revision from member_role_ledgers where organization_id=? and account_id=?",
                Integer.class,
                org,
                member))
        .isEqualTo(9);
  }

  @Test
  void rejoinOverflowRollsBackConsumption() {
    email = member + "@example.test";
    db.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    db.update(
        "insert into member_role_ledgers(organization_id,account_id,revision) values"
            + " (?,?,2147483647)",
        org,
        member);
    String token = capability(invitation());
    assertThatThrownBy(() -> redemption.accept(token, null, null, human(member)))
        .isInstanceOf(InvitationFailure.class);
    assertThat(redemption.inspect(token, human(member)).state()).isEqualTo("READY");
  }

  @Test
  void failedCreateAuditRollsBackInvitationAndReceipt() {
    String constraint = "invite_create_" + org.toString().replace("-", "");
    db.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "' or event_type <> 'CREATE_INVITATION')");
    UUID key = UUID.randomUUID();
    try {
      assertThatThrownBy(() -> create(key))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(management.receipt(org, human(admin), key)).isNull();
      assertThat(
              db.queryForObject(
                  "select count(*) from organization_invitations where organization_id=?",
                  Long.class,
                  org))
          .isZero();
    } finally {
      db.execute("alter table audit_events drop constraint " + constraint);
    }
  }

  @Test
  void failedAcceptanceAuditRollsBackNewHumanMembershipAndConsumption() {
    String token = capability(invitation());
    String constraint = "invite_accept_" + org.toString().replace("-", "");
    db.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (organization_id <> '"
            + org
            + "' or event_type <> 'ACCEPT_INVITATION')");
    try {
      assertThatThrownBy(() -> redemption.accept(token, "Invited human", "long-new-password", null))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(
              db.queryForObject(
                  "select count(*) from human_accounts where email=?", Long.class, email))
          .isZero();
      assertThat(redemption.inspect(token, null).state()).isEqualTo("NEW_ACCOUNT");
    } finally {
      db.execute("alter table audit_events drop constraint " + constraint);
    }
  }

  @Test
  void concurrentAcceptanceHasOneWinner() throws Exception {
    String token = capability(invitation());
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.Callable<Integer> attempt =
        () -> {
          start.await();
          try {
            redemption.accept(token, "Invited human", "long-new-password", null);
            return 200;
          } catch (InvitationFailure e) {
            return e.status;
          }
        };
    try {
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      start.countDown();
      assertThat(
              List.of(
                  first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 400);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void authorityIsRecheckedAfterOrganizationLockWait() throws Exception {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    var waiting = new java.util.concurrent.CountDownLatch(1);
    try {
      var future =
          tx.execute(
              status -> {
                db.queryForList("select id from organizations where id=? for update", org);
                var pending =
                    pool.submit(
                        () -> {
                          waiting.countDown();
                          return create(UUID.randomUUID());
                        });
                try {
                  assertThat(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                db.update(
                    "update organization_memberships set role='MEMBER' where organization_id=? and"
                        + " account_id=?",
                    org,
                    admin);
                return pending;
              });
      assertThatThrownBy(() -> future.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(
              db.queryForObject(
                  "select count(*) from organization_invitations where organization_id=?",
                  Long.class,
                  org))
          .isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void resendRebindsIssuerButKeepsDeliveredDigestAndFixedExpiry() {
    UUID id = invitation();
    String token = capability(id);
    Instant expiry = management.context(org, id, human(admin)).invitation().expiresAt();
    UUID second = account("ADMIN");
    db.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        admin);
    db.update(
        "update organization_invitations set last_requested_at=? where id=?",
        Timestamp.from(Instant.now().minusSeconds(61)),
        id);
    management.mutate(org, id, human(second), UUID.randomUUID(), "RESEND_INVITATION", null, 1);
    assertThat(redemption.inspect(token, null).expiresAt()).isEqualTo(expiry);
    assertThat(management.context(org, id, human(second)).invitation().deliveryStatus())
        .isEqualTo("QUEUED");
  }

  @Test
  void invitationRevisionOverflowDoesNotCreateHuman() {
    UUID id = invitation();
    String token = capability(id);
    db.update("update organization_invitations set revision=2147483647 where id=?", id);
    assertThatThrownBy(() -> redemption.accept(token, "Invited human", "long-new-password", null))
        .isInstanceOfSatisfying(InvitationFailure.class, e -> assertThat(e.status).isEqualTo(409));
    assertThat(
            db.queryForObject(
                "select count(*) from human_accounts where email=?", Long.class, email))
        .isZero();
    assertThat(redemption.inspect(token, null).state()).isEqualTo("NEW_ACCOUNT");
  }

  @Test
  void expiryDuringRetainedLedgerWaitCannotConsumeOrRejoin() throws Exception {
    email = member + "@example.test";
    db.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    db.update(
        "insert into member_role_ledgers(organization_id,account_id,revision) values (?,?,8)",
        org,
        member);
    UUID id = invitation();
    String token = capability(id);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    try {
      var holder =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        db.queryForList(
                            "select revision from member_role_ledgers where organization_id=? and"
                                + " account_id=? for update",
                            org,
                            member);
                        locked.countDown();
                        try {
                          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Lock release timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                      }));
      assertThat(locked.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      db.update(
          "update organization_invitations set expires_at=? where id=?",
          Timestamp.from(Instant.now().plusMillis(1000)),
          id);
      var acceptance = pool.submit(() -> redemption.accept(token, null, null, human(member)));
      // Keep the ledger unavailable until the capability has expired.
      assertThat(release.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
      release.countDown();
      holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThatThrownBy(() -> acceptance.get(5, java.util.concurrent.TimeUnit.SECONDS))
          .hasCauseInstanceOf(InvitationFailure.class);
      assertThat(
              db.queryForObject(
                  "select revision from member_role_ledgers where organization_id=? and"
                      + " account_id=?",
                  Integer.class,
                  org,
                  member))
          .isEqualTo(8);
      assertThat(
              db.queryForObject(
                  "select count(*) from organization_memberships where organization_id=? and"
                      + " account_id=?",
                  Long.class,
                  org,
                  member))
          .isZero();
      assertThat(
              db.queryForObject(
                  "select status from organization_invitations where id=?", String.class, id))
          .isEqualTo("PENDING");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void sameEmailAcrossOrganizationsCreatesOnlyOneGlobalHuman() throws Exception {
    UUID secondOrg = UUID.randomUUID();
    db.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Second"
            + " invitation scope',?,'ACTIVE',current_timestamp,current_timestamp)",
        secondOrg,
        secondOrg.toString());
    db.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        admin,
        secondOrg);
    String firstToken = capability(invitation());
    UUID secondId =
        management
            .mutate(
                secondOrg, null, human(admin), UUID.randomUUID(), "CREATE_INVITATION", email, null)
            .receipt()
            .invitationId();
    String secondToken = capability(secondId);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      java.util.concurrent.Callable<Integer> first =
          () -> {
            start.await();
            try {
              redemption.accept(firstToken, "Invited human", "long-new-password", null);
              return 200;
            } catch (InvitationFailure e) {
              return e.status;
            }
          };
      java.util.concurrent.Callable<Integer> second =
          () -> {
            start.await();
            try {
              redemption.accept(secondToken, "Invited human", "long-new-password", null);
              return 200;
            } catch (InvitationFailure e) {
              return e.status;
            }
          };
      var a = pool.submit(first);
      var b = pool.submit(second);
      start.countDown();
      assertThat(
              List.of(
                  a.get(15, java.util.concurrent.TimeUnit.SECONDS),
                  b.get(15, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 401);
      assertThat(
              db.queryForObject(
                  "select count(*) from human_accounts where email=?", Long.class, email))
          .isEqualTo(1L);
      assertThat(
              db.queryForObject(
                  "select count(*) from organization_invitations where email=? and"
                      + " status='ACCEPTED'",
                  Long.class,
                  email))
          .isEqualTo(1L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void authorizedMissingInvitationReturns404() throws Exception {
    mvc.perform(get(root() + "/" + UUID.randomUUID() + "/context").with(actor(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void oversizedPublicBodyReturnsSafe400WithoutEchoingInput() throws Exception {
    String marker = "PRIVATE_OVERSIZE_TOKEN";
    var response =
        mvc.perform(
                post("/api/v1/auth/invitations/inspect")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"token\":\"" + marker + "x".repeat(8192) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("Invalid request"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(marker);
  }
}

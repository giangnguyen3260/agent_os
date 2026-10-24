package com.agentos.invitation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

class InvitationLockOrderTest {
  static class StopAfterLocks extends RuntimeException {}

  @Test
  void acceptanceDerivesIssuerFromScopedRereadAfterOrganizationWait() {
    UUID org = UUID.randomUUID(),
        id = UUID.randomUUID(),
        oldIssuer = UUID.randomUUID(),
        newIssuer = UUID.randomUUID();
    String email = "invited@example.test";
    JdbcTemplate db = mock(JdbcTemplate.class);
    InvitationStore store = spy(new InvitationStore(db));
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    when(db.queryForList(
            "select * from organization_invitations where token_digest=?",
            InvitationWire.digest("a".repeat(43))))
        .thenReturn(
            List.of(
                Map.of("id", id, "organization_id", org, "email", email, "issuer_id", oldIssuer)));
    doNothing().when(store).orgLock(org);
    doReturn(Map.of("issuer_id", newIssuer)).when(store).invitation(org, id, false);
    doReturn(Map.of("email_digest", InvitationWire.digest(email)))
        .when(store)
        .one(anyString(), any());
    doThrow(new StopAfterLocks()).when(store).humanLocks(org, List.of(newIssuer));
    var env =
        new MockEnvironment()
            .withProperty("agentos.invitations.enabled", "true")
            .withProperty("agentos.invitations.frontend-base-url", "http://localhost:3000")
            .withProperty("agentos.invitations.from", "invites@example.test");
    var service =
        new InvitationRedemption(store, new InvitationSettings(env), mock(PasswordEncoder.class));
    assertThatThrownBy(
            () -> service.accept("a".repeat(43), "Invited human", "long-new-password", null))
        .isInstanceOf(StopAfterLocks.class);
    var order = inOrder(store);
    order.verify(store).orgLock(org);
    order.verify(store).invitation(org, id, false);
    order
        .verify(store)
        .one(
            "select email_digest from invitation_email_locks where email_digest=? for update",
            InvitationWire.digest(email));
    order.verify(store).humanLocks(org, List.of(newIssuer));
  }

  @Test
  void humansAreDistinctAndLexicallySortedBeforeMatchingMemberships() {
    UUID org = UUID.randomUUID();
    UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
    UUID last = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");
    JdbcTemplate db = mock(JdbcTemplate.class);
    InvitationStore store = spy(new InvitationStore(db));
    doReturn(Map.of("id", first)).when(store).one(anyString(), any());
    store.humanLocks(org, List.of(last, first, last));
    var order = inOrder(store, db);
    order.verify(store).one("select id from human_accounts where id=? for update", first);
    order.verify(store).one("select id from human_accounts where id=? for update", last);
    String membershipSql =
        "select account_id from organization_memberships where organization_id=? and account_id=?"
            + " for update";
    order.verify(db).queryForList(membershipSql, org, first);
    order.verify(db).queryForList(membershipSql, org, last);
    order.verifyNoMoreInteractions();
  }
}

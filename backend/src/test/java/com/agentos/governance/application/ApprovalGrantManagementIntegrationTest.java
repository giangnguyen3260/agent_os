package com.agentos.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Command;
import com.agentos.governance.api.ApprovalGrantManagementResponse.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalGrantManagementIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired ApprovalGrantManagementService service;
  UUID org, foreign, team, foreignTeam, admin, member, outsider;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org);
    foreignTeam = team(foreign);
    admin = account(org, "ADMIN");
    member = account(org, "MEMBER");
    outsider = account(foreign, "ADMIN");
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations(id,name,slug,status,created_at,updated_at) values"
            + " (?,'Management',?,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID team(UUID scope) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','DRAFT',current_timestamp,current_timestamp)",
        id,
        scope);
    return id;
  }

  UUID account(UUID scope, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash,enabled) values (?,?,'DB"
            + " actor','unused',true)",
        id,
        id + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values (?,?,?)",
        id,
        scope,
        role);
    return id;
  }

  HumanIdentity human(UUID id) {
    return new HumanIdentity(id, "unused@example.test", "UNTRUSTED NAME");
  }

  RequestPostProcessor actor(UUID id) {
    return authentication(new UsernamePasswordAuthenticationToken(human(id), null, List.of()));
  }

  Command issue(int revision, String reason) {
    return new Command(revision, team, "PUBLISH_PREVIEW", "HIGH", 168, null, reason, true);
  }

  Command revoke(int revision, String hash) {
    return new Command(revision, null, null, null, null, hash, "revoke reason", true);
  }

  String root(UUID target) {
    return "/api/v1/organizations/" + org + "/members/" + target + "/approval-grants";
  }

  UUID legacy(UUID target, String expiry, String revoked, String action, String risk) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at,revoked_at)"
            + " values (?,?,?,?,?,?,?,?)",
        id,
        org,
        target,
        team,
        action,
        risk,
        OffsetDateTime.parse(expiry),
        revoked == null ? null : OffsetDateTime.parse(revoked));
    return id;
  }

  Result write(UUID key) {
    return service
        .mutate(org, member, null, human(admin), key, issue(0, "  reason\t\n\r 😀  "))
        .result();
  }

  @Test
  void issueRevokeHistoricalReplayAndExactSnapshots() throws Exception {
    UUID key = UUID.randomUUID();
    Result r = write(key);
    assertThat(r.actorName()).isEqualTo("DB actor");
    assertThat(r.revision()).isEqualTo(1);
    assertThat(r.expiresAt()).isEqualTo(r.recordedAt().plus(168, ChronoUnit.HOURS));
    assertThat(r.recordedAt().getNano() % 1000).isZero();
    var context = service.revocationContext(org, member, r.grantId(), human(admin));
    assertThat(context.grantFingerprint()).matches("[0-9a-f]{64}");
    var revoked =
        service
            .mutate(
                org,
                member,
                r.grantId(),
                human(admin),
                UUID.randomUUID(),
                revoke(1, context.grantFingerprint()))
            .result();
    assertThat(revoked.revokedAt()).isEqualTo(revoked.recordedAt());
    assertThat(revoked.expiresAt()).isEqualTo(r.expiresAt());
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    jdbc.update("update human_accounts set enabled=false where id=?", member);
    var replay = service.mutate(org, member, null, human(admin), key, issue(0, "reason\t\n\r 😀"));
    assertThat(replay.replay()).isTrue();
    assertThat(replay.result()).isEqualTo(r);
    assertThat(service.receipt(org, member, human(admin), key, "ISSUE")).isEqualTo(r);
    assertThat(service.receipt(org, admin, human(admin), key, "ISSUE")).isNull();
    assertThat(service.receipt(org, member, human(admin), key, "REVOKE")).isNull();
    var history = service.history(org, member, r.grantId(), human(admin), 0);
    assertThat(history.total()).isEqualTo(2);
    assertThat(history.items().get(1).reason()).isEqualTo("reason\t\n\r 😀");
    String audit =
        jdbc.queryForObject(
            "select payload from audit_events where organization_id=? order by created_at limit 1",
            String.class,
            org);
    assertThat(json.readTree(audit).size()).isEqualTo(2);
    assertThat(audit).doesNotContain("reason", "😀");
    assertThatThrownBy(
            () -> service.mutate(org, member, null, human(admin), key, issue(0, "changed")))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(service.revocationContext(org, member, r.grantId(), human(admin)).blockers())
        .containsExactly("ALREADY_REVOKED");
  }

  @Test
  void orderedBlockersAndNoReadSeeds() {
    jdbc.update("update human_accounts set enabled=false where id=?", member);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    legacy(member, "2099-01-01T00:00:00Z", null, "PUBLISH_PREVIEW", "HIGH");
    assertThat(service.context(org, member, human(admin), team, "HIGH").blockers())
        .containsExactly("RECIPIENT_DISABLED", "TEAM_ARCHIVED", "DUPLICATE_SCOPE");
    assertThat(service.context(org, admin, human(admin), null, null).blockers())
        .containsExactly("SELF_ISSUANCE");
    assertThat(service.context(org, member, human(admin), null, null).selection()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from approval_grant_ledgers where organization_id=?",
                Long.class,
                org))
        .isZero();
    assertThatThrownBy(() -> service.context(org, member, human(admin), foreignTeam, "HIGH"))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(() -> service.context(org, member, human(admin), team, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void legacyDuplicatesAndRemovedDisabledUnsupportedSelfRevocation() {
    UUID first = legacy(member, "2099-01-01T00:00:00Z", null, "PUBLISH_PREVIEW", "HIGH");
    legacy(member, "2099-01-01T00:00:00Z", null, "PUBLISH_PREVIEW", "HIGH");
    assertThatThrownBy(() -> write(UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
    var c = service.revocationContext(org, member, first, human(admin));
    assertThat(c.remainingScopeGrants()).isEqualTo(1);
    service.mutate(
        org, member, first, human(admin), UUID.randomUUID(), revoke(0, c.grantFingerprint()));
    assertThat(
            service.context(org, member, human(admin), team, "HIGH").selection().unexpiredCount())
        .isEqualTo(1);
    UUID unsupported = legacy(member, "2000-01-01T00:00:00Z", null, "UNSUPPORTED", "CRITICAL");
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    jdbc.update("update human_accounts set enabled=false where id=?", member);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    var removed = service.revocationContext(org, member, unsupported, human(admin));
    assertThat(removed.recipient().membershipPresent()).isFalse();
    assertThat(removed.recipient().enabled()).isFalse();
    assertThat(service.history(org, member, unsupported, human(admin), 0).items()).isEmpty();
    service.mutate(
        org,
        member,
        unsupported,
        human(admin),
        UUID.randomUUID(),
        revoke(1, removed.grantFingerprint()));
    UUID self = legacy(admin, "2000-01-01T00:00:00Z", null, "LEGACY", "CRITICAL");
    service.mutate(
        org,
        admin,
        self,
        human(admin),
        UUID.randomUUID(),
        revoke(0, service.revocationContext(org, admin, self, human(admin)).grantFingerprint()));
  }

  @Test
  void staleFingerprintRevisionExhaustionAndFreshAuthority() {
    Result r = write(UUID.randomUUID());
    assertThatThrownBy(
            () ->
                service.mutate(
                    org,
                    member,
                    r.grantId(),
                    human(admin),
                    UUID.randomUUID(),
                    revoke(1, "a".repeat(64))))
        .isInstanceOf(ResponseStatusException.class);
    var context = service.revocationContext(org, member, r.grantId(), human(admin));
    assertThatThrownBy(
            () ->
                service.mutate(
                    org,
                    member,
                    r.grantId(),
                    human(admin),
                    UUID.randomUUID(),
                    revoke(0, context.grantFingerprint())))
        .isInstanceOf(ResponseStatusException.class);
    jdbc.update(
        "update approval_grant_ledgers set revision=2147483647 where organization_id=? and"
            + " account_id=?",
        org,
        member);
    assertThatThrownBy(
            () ->
                service.mutate(
                    org,
                    member,
                    r.grantId(),
                    human(admin),
                    UUID.randomUUID(),
                    revoke(Integer.MAX_VALUE, context.grantFingerprint())))
        .isInstanceOf(ResponseStatusException.class);
    jdbc.update("update human_accounts set credential_version=1 where id=?", admin);
    assertThatThrownBy(
            () -> service.receipt(org, member, human(admin), r.idempotencyKey(), "ISSUE"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.context(org, member, human(outsider), null, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.context(org, member, human(member), null, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void auditFailureRollsBackGrantLedgerEventReceipt() {
    String constraint = "reject_management_" + org.toString().replace("-", "");
    jdbc.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (organization_id<>'"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> write(UUID.randomUUID()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      for (String table :
          List.of(
              "approval_grants",
              "approval_grant_ledgers",
              "approval_grant_management_events",
              "approval_grant_management_receipts"))
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from " + table + " where organization_id=?", Long.class, org))
            .isZero();
    } finally {
      jdbc.execute("alter table audit_events drop constraint " + constraint);
    }
  }

  @Test
  void concurrentIssueHasOneWinner() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      var calls =
          List.<java.util.concurrent.Callable<Boolean>>of(
              () -> {
                try {
                  write(UUID.randomUUID());
                  return true;
                } catch (ResponseStatusException ex) {
                  return false;
                }
              },
              () -> {
                try {
                  write(UUID.randomUUID());
                  return true;
                } catch (ResponseStatusException ex) {
                  return false;
                }
              });
      var results = pool.invokeAll(calls, 15, TimeUnit.SECONDS);
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(true, false);
      assertThat(
              service.context(org, member, human(admin), team, "HIGH").selection().unexpiredCount())
          .isEqualTo(1);
      assertThat(service.context(org, member, human(admin), null, null).revision()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void strictWireAndTransportAuthority() throws Exception {
    String valid =
        json.writeValueAsString(
            Map.of(
                "expectedRevision",
                0,
                "teamId",
                team.toString(),
                "actionType",
                "PUBLISH_PREVIEW",
                "riskLevel",
                "HIGH",
                "durationHours",
                1,
                "reason",
                "x",
                "acknowledgement",
                true));
    for (String invalid :
        List.of(
            valid.replace("\"expectedRevision\":0", "\"expectedRevision\":0.0"),
            valid.replace("\"durationHours\":1", "\"durationHours\":\"1\""),
            valid.replace("\"acknowledgement\":true", "\"acknowledgement\":1"),
            valid.replace("\"reason\":\"x\"", "\"reason\":null"),
            valid.replace("\"reason\":\"x\"", "\"reason\":\"x\",\"reason\":\"y\""),
            valid.replace("\"reason\":\"x\"", "\"reason\":\"x\",\"unknown\":1"),
            valid.replace("\"reason\":\"x\"", "\"reason\":\"\\u0000\""),
            valid.replace("\"durationHours\":1", "\"durationHours\":169"),
            valid.replace("\"expectedRevision\":0", "\"expectedRevision\":2147483648"))) {
      mvc.perform(
              post(root(member))
                  .with(actor(admin))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", "1-1-1-1-1")
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(root(member))
                .with(actor(member))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isForbidden());
    mvc.perform(get(root(member) + "/management-context").with(actor(member)))
        .andExpect(status().isForbidden());
    String response =
        mvc.perform(
                post(root(member))
                    .with(actor(admin))
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(valid))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(response).get("data").size()).isEqualTo(13);
    assertThat(response).doesNotContain("reason", "password", "email");
  }

  @Test
  void canonicalHashVectorsAndNormalization() throws Exception {
    UUID o = UUID.fromString("60606060-6060-4060-8060-606060606060"),
        a = UUID.fromString("60800000-0000-4000-8000-000000000002"),
        t = UUID.fromString("60100000-0000-4000-8000-000000000001");
    Command c = new Command(0, t, "PUBLISH_PREVIEW", "HIGH", 1, null, "é\r\n😀", true);
    assertThat(service.commandHash(o, a, null, c))
        .isEqualTo("9591223cde55490c848b8b19d2b02cf7f477a1ee9dc77889ee551b3d1ba9f51b");
    var g =
        new com.agentos.governance.api.MemberAccessResponse.Grant(
            UUID.fromString("60700000-0000-4000-8000-000000000001"),
            o,
            a,
            t,
            "ignored",
            "PUBLISH_PREVIEW",
            "HIGH",
            Instant.parse("2026-10-23T12:34:56.123456Z"),
            null,
            "UNEXPIRED");
    assertThat(service.grantHash(g))
        .isEqualTo("78abadebefb661edad3f9248816bac6d4e4e8031e449d0ba456cf3962d7f56e0");
    assertThatThrownBy(
            () ->
                service.mutate(
                    org, member, null, human(admin), UUID.randomUUID(), issue(0, "\u007f")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.mutate(
                    org,
                    member,
                    null,
                    human(admin),
                    UUID.randomUUID(),
                    issue(0, "😀".repeat(1001))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            service
                .mutate(
                    org, member, null, human(admin), UUID.randomUUID(), issue(0, "😀".repeat(1000)))
                .result()
                .revision())
        .isEqualTo(1);
  }
}

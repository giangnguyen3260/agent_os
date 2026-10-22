package com.agentos.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.ApprovalDetailResponse.Command;
import com.agentos.governance.application.ApprovalDetailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalDetailIntegrationTest {
  @org.springframework.boot.test.mock.mockito.SpyBean JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired ApprovalDetailService service;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired MockMvc mvc;
  UUID org, team, actor, requester, id, action, grant;
  String digest;
  HumanIdentity human;

  @BeforeEach
  void setup() throws Exception {
    org = UUID.randomUUID();
    team = UUID.randomUUID();
    actor = UUID.randomUUID();
    requester = UUID.randomUUID();
    id = UUID.randomUUID();
    action = UUID.randomUUID();
    grant = UUID.randomUUID();
    jdbc.update(
        "insert into organizations values"
            + " (?,'Org',?,null,'ACTIVE',current_timestamp,current_timestamp)",
        org,
        org.toString());
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        team,
        org);
    for (UUID a : List.of(actor, requester))
      jdbc.update(
          "insert into human_accounts(id,email,display_name,password_hash) values"
              + " (?,?,'Human','unused')",
          a,
          a + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", actor, org);
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", requester, org);
    human = new HumanIdentity(actor, "member@example.test", "Human");
    jdbc.update(
        "insert into"
            + " approval_requests(id,organization_id,team_id,requester_human_id,title,action_type,risk_level,status)"
            + " values (?,?,?,?,'Review','PUBLISH_PREVIEW','HIGH','PENDING')",
        id,
        org,
        team,
        requester);
    TreeMap<String, Object> p = new TreeMap<>();
    p.put("id", id.toString());
    p.put("organization_id", org.toString());
    p.put("team_id", team.toString());
    for (String k : List.of("mission_id", "task_id", "requested_by_agent_id")) p.put(k, null);
    p.put("requester_human_id", requester.toString());
    p.put("action_type", "PUBLISH_PREVIEW");
    p.put("risk_level", "HIGH");
    p.put("actionId", action.toString());
    p.put("version", 1);
    p.put("headRevision", 1);
    p.put("expiresAt", "2099-01-01T00:00:00Z");
    p.put("target", "Staging");
    p.put("scope", "One preview");
    p.put("effectSummary", "Create preview");
    p.put("estimatedCost", null);
    p.put("currency", null);
    p.put(
        "parameters",
        new TreeMap<>(
            java.util.Map.of(
                "destination",
                "internal-review",
                "resourcePath",
                "/release/approval-detail",
                "visibility",
                "TEAM")));
    String raw = json.writeValueAsString(p);
    digest = ApprovalDetailService.sha256(raw);
    jdbc.update(
        "insert into approval_action_heads values (?,?,?,1,?,true)", action, org, team, digest);
    jdbc.update(
        "insert into approval_snapshots values (?,?,1,1,?,?,'2099-01-01T00:00:00Z',null)",
        id,
        action,
        raw,
        digest);
    jdbc.update(
        "insert into approval_grants values (?,?,?,?,"
            + " 'PUBLISH_PREVIEW','HIGH','2099-01-01T00:00:00Z',null)",
        grant,
        org,
        team,
        actor);
  }

  Command command() {
    return new Command(1L, digest, true, null);
  }

  long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  @Test
  void memberRecordsExactImmutableResultAndFreshGrantReplay() {
    assertThat(service.detail(org, id, human).eligibility().canApprove()).isTrue();
    long events = count("audit_events");
    UUID key = UUID.randomUUID();
    var first = service.decide(org, id, human, key, true, command());
    assertThat(first.replay()).isFalse();
    assertThat(first.decision().status()).isEqualTo("APPROVED");
    jdbc.update("update approval_action_heads set active=false where action_id=?", action);
    assertThat(service.decide(org, id, human, key, true, command()).decision())
        .isEqualTo(first.decision());
    assertThat(count("audit_events")).isEqualTo(events + 1);
    assertThatThrownBy(
            () ->
                service.decide(
                    org, id, human, key, false, new Command(1L, digest, false, "Reject")))
        .isInstanceOf(ResponseStatusException.class);
    jdbc.update("update approval_grants set revoked_at=current_timestamp where id=?", grant);
    assertThatThrownBy(() -> service.decide(org, id, human, key, true, command()))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(service.detail(org, id, human).decision()).isEqualTo(first.decision());
  }

  @Test
  void failClosedMutationsProduceNoReceiptDecisionEventOrStatusWrite() {
    long decisions = count("approval_decisions"),
        events = count("audit_events"),
        receipts = count("approval_receipts");
    for (String mutation :
        List.of(
            "update approval_action_heads set revision=2 where action_id='" + action + "'",
            "update approval_snapshots set invalidated_at=current_timestamp where approval_id='"
                + id
                + "'",
            "update approval_requests set requester_human_id=null where id='" + id + "'")) {
      jdbc.update(mutation);
      assertThat(service.detail(org, id, human).eligibility().canApprove()).isFalse();
      assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
          .isInstanceOfAny(ResponseStatusException.class, AccessDeniedException.class);
    }
    assertThat(count("approval_decisions")).isEqualTo(decisions);
    assertThat(count("approval_receipts")).isEqualTo(receipts);
    assertThat(count("audit_events")).isEqualTo(events);
    assertThat(
            jdbc.queryForObject(
                "select status from approval_requests where id=?", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void selfNoGrantStaleCredentialsAndForeignDeny() {
    jdbc.update("update approval_requests set requester_human_id=? where id=?", actor, id);
    assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("delete from approval_grants where id=?", grant);
    assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.detail(UUID.randomUUID(), id, human))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set credential_version=1 where id=?", actor);
    assertThatThrownBy(() -> service.detail(org, id, human))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void transportRequiresCsrfAndMemberExceptionDoesNotPermitOtherWrites() throws Exception {
    var auth =
        authentication(UsernamePasswordAuthenticationToken.authenticated(human, null, List.of()));
    String path = "/api/v1/organizations/" + org + "/approvals/" + id;
    mvc.perform(
            post(path + "/approve")
                .with(auth)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(command())))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/organizations/" + org + "/teams")
                .with(auth)
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    UUID key = UUID.randomUUID();
    var initial =
        mvc.perform(
                post(path + "/approve")
                    .with(auth)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsString(command())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    jdbc.update("update approval_action_heads set active=false where action_id=?", action);
    var replay =
        mvc.perform(
                post(path + "/approve")
                    .with(auth)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsString(command())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var initialJson = json.readTree(initial.getContentAsString());
    var replayJson = json.readTree(replay.getContentAsString());
    assertThat(replayJson.path("data")).isEqualTo(initialJson.path("data"));
    assertThat(initialJson.path("data"))
        .isEqualTo(
            json.readTree(json.writeValueAsString(service.detail(org, id, human).decision())));
    UUID initialRequestId = UUID.fromString(initialJson.path("meta").path("requestId").asText());
    UUID replayRequestId = UUID.fromString(replayJson.path("meta").path("requestId").asText());
    assertThat(replayRequestId).isNotEqualTo(initialRequestId);
  }

  @Test
  void concurrentOppositeDecisionsHaveOneWinner() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var calls =
          List.of(true, false).stream()
              .map(
                  approve ->
                      pool.submit(
                          () -> {
                            gate.await();
                            try {
                              service.decide(
                                  org,
                                  id,
                                  human,
                                  UUID.randomUUID(),
                                  approve,
                                  new Command(1L, digest, true, approve ? null : "Decline"));
                              return 201;
                            } catch (ResponseStatusException ex) {
                              return ex.getStatusCode().value();
                            }
                          }))
              .toList();
      gate.countDown();
      assertThat(List.of(calls.get(0).get(), calls.get(1).get()))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from approval_decisions where approval_id=?", Long.class, id))
        .isEqualTo(1);
  }

  @Test
  void wireRejectsCoercionsAndUnsafeAcknowledgementWithoutWrites() throws Exception {
    var auth =
        authentication(UsernamePasswordAuthenticationToken.authenticated(human, null, List.of()));
    String path = "/api/v1/organizations/" + org + "/approvals/" + id + "/approve";
    String valid = json.writeValueAsString(command());
    long before = count("approval_decisions");
    for (String invalid :
        List.of(
            valid.replace("\"snapshotVersion\":1", "\"snapshotVersion\":1.5"),
            valid.replace("\"acknowledgement\":true", "\"acknowledgement\":\"true\""),
            valid.replace("\"acknowledgement\":true", "\"acknowledgement\":false"))) {
      mvc.perform(
              post(path)
                  .with(auth)
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    assertThat(count("approval_decisions")).isEqualTo(before);
  }

  @Test
  void rejectStripsReasonFingerprintAndPreservesImmutableActorName() {
    UUID key = UUID.randomUUID();
    var result =
        service.decide(
            org, id, human, key, false, new Command(1L, digest, false, "  Human declines  "));
    assertThat(result.decision().reason()).isEqualTo("Human declines");
    jdbc.update("update human_accounts set display_name='Changed' where id=?", actor);
    assertThat(
            service
                .decide(
                    org, id, human, key, false, new Command(1L, digest, false, "Human declines"))
                .decision())
        .isEqualTo(result.decision());
    assertThat(service.detail(org, id, human).decision()).isEqualTo(result.decision());
    assertThat(service.detail(org, id, human).executionAvailable()).isFalse();
  }

  @Test
  void legacyAndClosedRequestsStayReadOnly() {
    jdbc.update("delete from approval_snapshots where approval_id=?", id);
    var before = jdbc.queryForMap("select * from approval_requests where id=?", id);
    assertThat(service.detail(org, id, human).snapshot()).isNull();
    assertThat(service.detail(org, id, human).eligibility().blockers()).contains("LEGACY_UNSEALED");
    assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForMap("select * from approval_requests where id=?", id))
        .isEqualTo(before);
    jdbc.update("update approval_requests set status='APPROVED' where id=?", id);
    assertThat(service.detail(org, id, human).decision()).isNull();
    assertThat(service.detail(org, id, human).eligibility().canApprove()).isFalse();
  }

  @Test
  void historicalSealAndDecisionRemainReadableWhenCurrentHeadChangesOrCorrupts() {
    var original = service.detail(org, id, human).snapshot();
    assertThat(original.parameters().destination()).isEqualTo("internal-review");
    assertThat(original.parameters().resourcePath()).isEqualTo("/release/approval-detail");
    assertThat(original.parameters().visibility()).isEqualTo("TEAM");
    var recorded = service.decide(org, id, human, UUID.randomUUID(), true, command()).decision();
    long events = count("audit_events"), receipts = count("approval_receipts");
    jdbc.update(
        "update approval_action_heads set revision=2,payload_sha256=?,active=false where"
            + " action_id=?",
        "x".repeat(64),
        action);
    var changed = service.detail(org, id, human);
    assertThat(changed.snapshot()).isEqualTo(original);
    assertThat(changed.decision()).isEqualTo(recorded);
    assertThat(changed.eligibility().blockers())
        .contains("NOT_PENDING", "CURRENT_HEAD_CHANGED", "INACTIVE_ACTION");
    assertThat(changed.eligibility().canApprove()).isFalse();
    assertThat(changed.eligibility().canReject()).isFalse();
    assertThat(changed.executionAvailable()).isFalse();
    jdbc.update(
        "update approval_snapshots set payload_sha256=? where approval_id=?", "0".repeat(64), id);
    var corruptSeal = service.detail(org, id, human);
    assertThat(corruptSeal.snapshot()).isNull();
    assertThat(corruptSeal.decision()).isEqualTo(recorded);
    assertThat(corruptSeal.eligibility().blockers()).contains("INVALID_SNAPSHOT");
    assertThat(count("audit_events")).isEqualTo(events);
    assertThat(count("approval_receipts")).isEqualTo(receipts);
  }

  @Test
  void historicalDisplayDoesNotRelaxPendingWriteHeadChecks() {
    var original = service.detail(org, id, human).snapshot();
    var requestBefore = jdbc.queryForMap("select * from approval_requests where id=?", id);
    long decisions = count("approval_decisions"),
        events = count("audit_events"),
        receipts = count("approval_receipts");
    for (String change :
        List.of("revision=2", "payload_sha256='" + "0".repeat(64) + "'", "active=false")) {
      jdbc.update(
          "update approval_action_heads set revision=1,payload_sha256=?,active=true where"
              + " action_id=?",
          digest,
          action);
      jdbc.update("update approval_action_heads set " + change + " where action_id=?", action);
      var detail = service.detail(org, id, human);
      assertThat(detail.snapshot()).isEqualTo(original);
      assertThat(detail.eligibility().canApprove()).isFalse();
      assertThat(detail.eligibility().canReject()).isFalse();
      assertThat(detail.eligibility().blockers())
          .contains(change.equals("active=false") ? "INACTIVE_ACTION" : "CURRENT_HEAD_CHANGED");
      assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(
              () ->
                  service.decide(
                      org,
                      id,
                      human,
                      UUID.randomUUID(),
                      false,
                      new Command(1L, digest, false, "Decline")))
          .isInstanceOf(ResponseStatusException.class);
    }
    assertThat(jdbc.queryForMap("select * from approval_requests where id=?", id))
        .isEqualTo(requestBefore);
    assertThat(count("approval_decisions")).isEqualTo(decisions);
    assertThat(count("audit_events")).isEqualTo(events);
    assertThat(count("approval_receipts")).isEqualTo(receipts);
  }

  @Test
  void organizationFirstWriterAndApprovalUseTheSameLockOrder() throws Exception {
    var orgHeld = new CountDownLatch(1);
    var approvalAtOrg = new CountDownLatch(1);
    Mockito.doAnswer(
            invocation -> {
              String query = invocation.getArgument(0);
              if (Thread.currentThread().getName().equals("approval-lock-order-test")
                  && query.contains("from organizations")
                  && query.endsWith("for update")) approvalAtOrg.countDown();
              return invocation.callRealMethod();
            })
        .when(jdbc)
        .queryForList(ArgumentMatchers.anyString(), ArgumentMatchers.any(UUID.class));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var writer =
          pool.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            jdbc.queryForList(
                                "select id from organizations where id=? for update", org);
                            orgHeld.countDown();
                            try {
                              assertThat(
                                      approvalAtOrg.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                  .isTrue();
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(ex);
                            }
                            jdbc.queryForList(
                                "select id from human_accounts where id=? for update", actor);
                            jdbc.queryForList(
                                "select account_id from organization_memberships where account_id=?"
                                    + " and organization_id=? for update",
                                actor,
                                org);
                            return true;
                          }));
      assertThat(orgHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var approval =
          pool.submit(
              () -> {
                Thread.currentThread().setName("approval-lock-order-test");
                return service.decide(org, id, human, UUID.randomUUID(), true, command());
              });
      assertThat(writer.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(approval.get(10, java.util.concurrent.TimeUnit.SECONDS).decision().status())
          .isEqualTo("APPROVED");
    } finally {
      Mockito.reset(jdbc);
    }
  }

  @Test
  void repeatedParentBlockersAreStableAndDistinct() {
    jdbc.update("update organizations set status='INACTIVE' where id=?", org);
    jdbc.update("update teams set status='INACTIVE' where id=?", team);
    var first = service.detail(org, id, human).eligibility();
    assertThat(first.blockers()).containsExactly("INACTIVE_PARENT");
    assertThat(service.detail(org, id, human).eligibility()).isEqualTo(first);
    assertThat(first.canApprove()).isFalse();
    assertThat(first.canReject()).isFalse();
  }

  @Test
  void existingForeignRequesterHumanWithMatchingSealDeniedWithoutWrites() {
    var original = service.detail(org, id, human).snapshot();
    UUID foreign = UUID.randomUUID();
    jdbc.update(
        "insert into organizations values"
            + " (?,'Foreign',?,null,'ACTIVE',current_timestamp,current_timestamp)",
        foreign,
        foreign.toString());
    jdbc.update(
        "delete from organization_memberships where account_id=? and organization_id=?",
        requester,
        org);
    jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", requester, foreign);
    var before = jdbc.queryForMap("select * from approval_requests where id=?", id);
    long decisions = count("approval_decisions"),
        events = count("audit_events"),
        receipts = count("approval_receipts");
    var detail = service.detail(org, id, human);
    assertThat(detail.snapshot()).isEqualTo(original);
    assertThat(detail.eligibility().blockers()).containsExactly("REQUESTER_OUT_OF_SCOPE");
    assertThat(detail.eligibility().canApprove()).isFalse();
    assertThat(detail.eligibility().canReject()).isFalse();
    assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                service.decide(
                    org,
                    id,
                    human,
                    UUID.randomUUID(),
                    false,
                    new Command(1L, digest, false, "Decline")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(jdbc.queryForMap("select * from approval_requests where id=?", id))
        .isEqualTo(before);
    assertThat(count("approval_decisions")).isEqualTo(decisions);
    assertThat(count("audit_events")).isEqualTo(events);
    assertThat(count("approval_receipts")).isEqualTo(receipts);
  }

  @Test
  void requesterMembershipRevocationDeniesNewDecisionDespiteIntactSeal() {
    jdbc.update(
        "delete from organization_memberships where account_id=? and organization_id=?",
        requester,
        org);
    var denied = service.detail(org, id, human);
    assertThat(denied.snapshot()).isNotNull();
    assertThat(denied.eligibility().blockers()).containsExactly("REQUESTER_OUT_OF_SCOPE");
    assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", requester, org);
    assertThat(service.detail(org, id, human).eligibility().canApprove()).isTrue();
  }

  @Test
  void rollbackOnAuditFailureLeavesNoDecision() {
    String constraint = "ad_fail_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (event_type <> 'APPROVAL_DECIDED' or organization_id <> '"
            + org
            + "')");
    var before = jdbc.queryForMap("select * from approval_requests where id=?", id);
    long receipts = count("approval_receipts"), decisions = count("approval_decisions");
    try {
      assertThatThrownBy(() -> service.decide(org, id, human, UUID.randomUUID(), true, command()))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(jdbc.queryForMap("select * from approval_requests where id=?", id))
          .isEqualTo(before);
      assertThat(count("approval_receipts")).isEqualTo(receipts);
      assertThat(count("approval_decisions")).isEqualTo(decisions);
    } finally {
      jdbc.execute("alter table audit_events drop constraint " + constraint);
    }
  }
}

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
class ApprovalInformationIntegrationTest {
  @Autowired com.agentos.governance.application.ApprovalInformationService information;
  @Autowired JdbcTemplate jdbc;
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

  com.agentos.governance.api.ApprovalInformationResponse.Command ask(long revision) {
    return new com.agentos.governance.api.ApprovalInformationResponse.Command(
        revision, "  Explain scope  ", null, null, null, false);
  }

  HumanIdentity requesterIdentity() {
    return new HumanIdentity(requester, "requester@example.test", "Human");
  }

  com.agentos.governance.api.ApprovalInformationResponse.Command answer(
      long revision, UUID question) {
    return new com.agentos.governance.api.ApprovalInformationResponse.Command(
        revision, null, question, "  Scope confirmed  ", "  Internal evidence  ", true);
  }

  @Test
  void sequentialPersistedRoundsAndRevisionBoundDecision() {
    assertThat(information.information(org, id, human).canRequest()).isTrue();
    var first = information.exchange(org, id, human, UUID.randomUUID(), ask(0)).mutation();
    assertThat(first.revision()).isEqualTo(1);
    assertThat(service.detail(org, id, human).informationRevision()).isEqualTo(1);
    assertThat(information.information(org, id, requesterIdentity()).canReply()).isTrue();
    var second =
        information.exchange(
            org, id, requesterIdentity(), UUID.randomUUID(), answer(1, first.entryId()));
    assertThat(second.mutation().status()).isEqualTo("PENDING");
    assertThat(information.information(org, id, human).entries())
        .extracting(e -> e.text())
        .containsExactly("Explain scope", "Scope confirmed");
    assertThatThrownBy(
            () ->
                service.decide(
                    org, id, human, UUID.randomUUID(), true, new Command(1L, digest, true, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            service
                .decide(
                    org,
                    id,
                    human,
                    UUID.randomUUID(),
                    true,
                    new Command(1L, digest, true, null, 2L))
                .decision()
                .status())
        .isEqualTo("APPROVED");
  }

  @Test
  void replaySurvivesLaterStateButReauthorizesGrantAndRequester() {
    UUID key = UUID.randomUUID();
    var first = information.exchange(org, id, human, key, ask(0));
    UUID replyKey = UUID.randomUUID();
    var reply = answer(1, first.mutation().entryId());
    var second = information.exchange(org, id, requesterIdentity(), replyKey, reply);
    information.exchange(org, id, human, UUID.randomUUID(), ask(2));
    assertThat(information.exchange(org, id, human, key, ask(0)).mutation())
        .isEqualTo(first.mutation());
    assertThat(information.exchange(org, id, requesterIdentity(), replyKey, reply).mutation())
        .isEqualTo(second.mutation());
    jdbc.update("update approval_grants set revoked_at=current_timestamp where id=?", grant);
    assertThatThrownBy(() -> information.exchange(org, id, human, key, ask(0)))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        requester);
    assertThatThrownBy(() -> information.exchange(org, id, requesterIdentity(), replyKey, reply))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void exactKeyConflictsAcrossOperationBodyAndApproval() {
    UUID key = UUID.randomUUID();
    information.exchange(org, id, human, key, ask(0));
    assertThatThrownBy(() -> information.exchange(org, id, human, key, ask(2)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> information.exchange(org, UUID.randomUUID(), human, key, ask(0)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> information.exchange(org, id, human, key, answer(1, UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void requesterBoundaryAndGrantAreIndependent() {
    var first = information.exchange(org, id, human, UUID.randomUUID(), ask(0));
    assertThat(information.information(org, id, human).canReply()).isFalse();
    assertThatThrownBy(
            () ->
                information.exchange(
                    org, id, human, UUID.randomUUID(), answer(1, first.mutation().entryId())))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("delete from approval_grants where id=?", grant);
    assertThat(
            information
                .exchange(
                    org,
                    id,
                    requesterIdentity(),
                    UUID.randomUUID(),
                    answer(1, first.mutation().entryId()))
                .mutation()
                .revision())
        .isEqualTo(2);
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(2)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> information.exchange(org, id, requesterIdentity(), UUID.randomUUID(), ask(2)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void staleQuestionStateAndMetadataOnlyInformationFailClosed() {
    jdbc.update("update approval_requests set status='NEEDS_INFORMATION' where id=?", id);
    assertThat(information.information(org, id, requesterIdentity()).canReply()).isFalse();
    assertThatThrownBy(
            () ->
                information.exchange(
                    org, id, requesterIdentity(), UUID.randomUUID(), answer(0, UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class);
    jdbc.update("update approval_requests set status='PENDING' where id=?", id);
    var first = information.exchange(org, id, human, UUID.randomUUID(), ask(0));
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(0)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                information.exchange(
                    org, id, requesterIdentity(), UUID.randomUUID(), answer(1, UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                information.exchange(
                    org,
                    id,
                    requesterIdentity(),
                    UUID.randomUUID(),
                    answer(0, first.mutation().entryId())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void corruptExpiredInactiveAndForeignFailClosed() {
    jdbc.update("update approval_action_heads set revision=2 where action_id=?", action);
    assertThat(information.information(org, id, human).canRequest()).isFalse();
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(0)))
        .isInstanceOf(ResponseStatusException.class);
    jdbc.update("update approval_action_heads set revision=1 where action_id=?", action);
    jdbc.update(
        "update approval_snapshots set invalidated_at=current_timestamp where approval_id=?", id);
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(0)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> information.exchange(UUID.randomUUID(), id, human, UUID.randomUUID(), ask(0)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> information.exchange(org, UUID.randomUUID(), human, UUID.randomUUID(), ask(0)))
        .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
  }

  @Test
  void staleCredentialsAndSelfAreDenied() {
    assertThatThrownBy(
            () ->
                information.exchange(
                    org, id, new HumanIdentity(actor, "x", "Human", 99), UUID.randomUUID(), ask(0)))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update approval_requests set requester_human_id=? where id=?", actor, id);
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(0)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void twentyRoundsLimitIsReadOnlyAndCannotBeExtended() {
    for (int i = 0; i < 20; i++) {
      var question = information.exchange(org, id, human, UUID.randomUUID(), ask(i * 2L));
      information.exchange(
          org,
          id,
          requesterIdentity(),
          UUID.randomUUID(),
          answer(i * 2L + 1, question.mutation().entryId()));
    }
    var result = information.information(org, id, human);
    assertThat(result.entries()).hasSize(40);
    assertThat(result.canRequest()).isFalse();
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(40)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void auditFailureRollsBackEntryStatusRevisionAndReceipt() throws Exception {
    long before =
        jdbc.queryForObject("select count(*) from approval_information_entries", Long.class);
    String constraint = "ai_fail_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (event_type <> 'APPROVAL_INFORMATION_REQUESTED' or organization_id <> '"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), ask(0)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      var auth = UsernamePasswordAuthenticationToken.authenticated(human, null, List.of());
      var response =
          mvc.perform(
                  post("/api/v1/organizations/" + org + "/approvals/" + id + "/request-information")
                      .with(authentication(auth))
                      .with(csrf())
                      .header("Idempotency-Key", UUID.randomUUID())
                      .contentType("application/json")
                      .content("{\"revision\":0,\"question\":\"Private audit rollback question\"}"))
              .andExpect(status().isInternalServerError())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(response)
          .doesNotContain("audit_events", constraint, "Private audit rollback question");
      assertThat(json.readTree(response).path("error").path("code").asText())
          .isEqualTo("INTERNAL_ERROR");
    } finally {
      jdbc.execute("alter table audit_events drop constraint " + constraint);
    }
    assertThat(jdbc.queryForObject("select count(*) from approval_information_entries", Long.class))
        .isEqualTo(before);
    assertThat(service.detail(org, id, human).informationRevision()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from approval_information_receipts where approval_id=?",
                Long.class,
                id))
        .isZero();
  }

  @Test
  void concurrentAskAndDecisionHaveOneWinner() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                try {
                  information.exchange(org, id, human, UUID.randomUUID(), ask(0));
                  return true;
                } catch (ResponseStatusException e) {
                  return false;
                }
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                try {
                  service.decide(
                      org, id, human, UUID.randomUUID(), true, new Command(1L, digest, true, null));
                  return true;
                } catch (ResponseStatusException e) {
                  return false;
                }
              });
      start.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void concurrentDuplicateRepliesCreateExactlyOneEntry() throws Exception {
    var first = information.exchange(org, id, human, UUID.randomUUID(), ask(0));
    var body = answer(1, first.mutation().entryId());
    UUID key = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return information.exchange(org, id, requesterIdentity(), key, body);
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return information.exchange(org, id, requesterIdentity(), key, body);
              });
      start.countDown();
      assertThat(a.get().mutation()).isEqualTo(b.get().mutation());
    }
    assertThat(information.information(org, id, human).entries()).hasSize(2);
  }

  @Test
  void strictWireCsrfAndMemberTransport() throws Exception {
    var auth = UsernamePasswordAuthenticationToken.authenticated(human, null, List.of());
    String path = "/api/v1/organizations/" + org + "/approvals/" + id + "/request-information";
    for (String body :
        List.of(
            "{\"revision\":0,\"question\":\"ok\",\"extra\":1}",
            "{\"revision\":\"0\",\"question\":\"ok\"}",
            "{\"revision\":0,\"revision\":0,\"question\":\"ok\"}",
            "{\"revision\":null,\"question\":\"ok\"}",
            "{\"revision\":0,\"question\":true}",
            "{\"revision\":9007199254740992,\"question\":\"ok\"}"))
      mvc.perform(
              post(path)
                  .with(authentication(auth))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post(path)
                .with(authentication(auth))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"revision\":0,\"question\":\"ok\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .with(authentication(auth))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content("{\"revision\":0,\"question\":\"ok\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void exactReceiptLookupNeverInfersFromConversationRevision() {
    UUID missing = UUID.randomUUID(), key = UUID.randomUUID();
    assertThat(information.receipt(org, id, human, missing)).isNull();
    var question = information.exchange(org, id, human, key, ask(0));
    assertThat(information.receipt(org, id, human, missing)).isNull();
    assertThat(information.receipt(org, id, human, key)).isEqualTo(question.mutation());
    assertThat(information.receipt(org, id, requesterIdentity(), key)).isNull();
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " approval_requests(id,organization_id,team_id,title,action_type,risk_level,status)"
            + " values (?,?,?,'Other','PUBLISH_PREVIEW','HIGH','PENDING')",
        other,
        org,
        team);
    assertThat(information.receipt(org, other, human, key)).isNull();
    UUID replyKey = UUID.randomUUID();
    var reply =
        information.exchange(
            org, id, requesterIdentity(), replyKey, answer(1, question.mutation().entryId()));
    information.exchange(org, id, human, UUID.randomUUID(), ask(2));
    assertThat(information.receipt(org, id, human, key)).isEqualTo(question.mutation());
    assertThat(information.receipt(org, id, requesterIdentity(), replyKey))
        .isEqualTo(reply.mutation());
    jdbc.update("update approval_grants set revoked_at=current_timestamp where id=?", grant);
    assertThatThrownBy(() -> information.receipt(org, id, human, key))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(information.receipt(org, id, requesterIdentity(), replyKey))
        .isEqualTo(reply.mutation());
    jdbc.update(
        "delete from organization_memberships where account_id=? and organization_id=?",
        requester,
        org);
    assertThatThrownBy(() -> information.receipt(org, id, requesterIdentity(), replyKey))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void sharedUnicodeTrimBindsFingerprintAndOptionalEvidence() {
    String edge = "\u00a0\ufeff\u001c\u2009";
    var empty =
        new com.agentos.governance.api.ApprovalInformationResponse.Command(
            0L, edge, null, null, null, false);
    assertThatThrownBy(() -> information.exchange(org, id, human, UUID.randomUUID(), empty))
        .isInstanceOf(IllegalArgumentException.class);
    UUID key = UUID.randomUUID();
    var padded =
        new com.agentos.governance.api.ApprovalInformationResponse.Command(
            0L, edge + "Explain scope" + edge, null, null, null, false);
    var question = information.exchange(org, id, human, key, padded);
    assertThat(information.exchange(org, id, human, key, ask(0)).mutation())
        .isEqualTo(question.mutation());
    var reply =
        new com.agentos.governance.api.ApprovalInformationResponse.Command(
            1L, null, question.mutation().entryId(), edge + "Answer" + edge, edge, true);
    information.exchange(org, id, requesterIdentity(), UUID.randomUUID(), reply);
    var entries = information.information(org, id, human).entries();
    assertThat(entries.get(1).text()).isEqualTo("Answer");
    assertThat(entries.get(1).evidence()).isNull();
  }

  @Test
  void requesterRevocationAndHeadChangeBlockReplyWithoutEffects() {
    var question = information.exchange(org, id, human, UUID.randomUUID(), ask(0));
    var reply = answer(1, question.mutation().entryId());
    jdbc.update("update approval_action_heads set active=false where action_id=?", action);
    assertThat(information.information(org, id, requesterIdentity()).canReply()).isFalse();
    assertThatThrownBy(
            () -> information.exchange(org, id, requesterIdentity(), UUID.randomUUID(), reply))
        .isInstanceOf(ResponseStatusException.class);
    jdbc.update("update approval_action_heads set active=true where action_id=?", action);
    jdbc.update("update human_accounts set enabled=false where id=?", requester);
    assertThatThrownBy(
            () -> information.exchange(org, id, requesterIdentity(), UUID.randomUUID(), reply))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from approval_information_entries where approval_id=?",
                Long.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void replyAuditFailureRollsBackQuestionRemainsOutstanding() {
    var question = information.exchange(org, id, human, UUID.randomUUID(), ask(0));
    String constraint = "ai_reply_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (event_type <> 'APPROVAL_INFORMATION_REPLIED' or organization_id <> '"
            + org
            + "')");
    try {
      assertThatThrownBy(
              () ->
                  information.exchange(
                      org,
                      id,
                      requesterIdentity(),
                      UUID.randomUUID(),
                      answer(1, question.mutation().entryId())))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("alter table audit_events drop constraint " + constraint);
    }
    assertThat(information.information(org, id, requesterIdentity()).canReply()).isTrue();
    assertThat(information.information(org, id, requesterIdentity()).entries()).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from approval_information_receipts where approval_id=?",
                Long.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void legacyRevisionZeroReceiptFingerprintRemainsRecoverable() throws Exception {
    UUID key = UUID.randomUUID();
    var decision = service.decide(org, id, human, key, true, new Command(1L, digest, true, null));
    String legacy =
        ApprovalDetailService.sha256(
            json.writeValueAsString(
                java.util.Arrays.asList(id.toString(), true, 1L, digest, true, null)));
    assertThat(
            jdbc.queryForObject(
                "select fingerprint from approval_receipts where organization_id=? and actor_id=?"
                    + " and idempotency_key=?",
                String.class,
                org,
                actor,
                key))
        .isEqualTo(legacy);
    jdbc.update("update approval_requests set information_revision=2 where id=?", id);
    jdbc.update("update approval_action_heads set active=false where action_id=?", action);
    assertThat(
            service
                .decide(org, id, human, key, true, new Command(1L, digest, true, null, 0L))
                .decision())
        .isEqualTo(decision.decision());
  }

  @Test
  void strictReplyAndDecisionRevisionWireRejectCoercionAndNull() throws Exception {
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(requesterIdentity(), null, List.of());
    var question = information.exchange(org, id, human, UUID.randomUUID(), ask(0));
    String path = "/api/v1/organizations/" + org + "/approvals/" + id + "/reply";
    for (String body :
        List.of(
            "{\"revision\":1,\"questionId\":\""
                + question.mutation().entryId()
                + "\",\"answer\":\"ok\",\"evidence\":true}",
            "{\"revision\":1,\"questionId\":\"1-1-1-1-1\",\"answer\":\"ok\"}",
            "{\"revision\":1,\"questionId\":\""
                + question.mutation().entryId()
                + "\",\"answer\":\"ok\",\"question\":\"unexpected\"}"))
      mvc.perform(
              post(path)
                  .with(authentication(auth))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    var approver = UsernamePasswordAuthenticationToken.authenticated(human, null, List.of());
    for (String revision : List.of("null", "true", "\"0\"", "0.0", "-1", "9007199254740992"))
      mvc.perform(
              post("/api/v1/organizations/" + org + "/approvals/" + id + "/approve")
                  .with(authentication(approver))
                  .with(csrf())
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(
                      "{\"snapshotVersion\":1,\"snapshotSha256\":\""
                          + digest
                          + "\",\"acknowledgement\":true,\"reason\":null,\"informationRevision\":"
                          + revision
                          + "}"))
          .andExpect(status().isBadRequest());
  }
}

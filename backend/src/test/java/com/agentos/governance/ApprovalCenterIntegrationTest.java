package com.agentos.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.application.ApprovalCenterService;
import com.agentos.governance.application.ApprovalCenterService.Group;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalCenterIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ApprovalCenterService service;
  UUID org, foreign, team, otherTeam, account, requester;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org);
    otherTeam = team(org);
    account = UUID.randomUUID();
    requester = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Reader','unused')",
        account,
        account + "@example.test");
    jdbc.update("insert into organization_memberships values (?,?,'MEMBER')", account, org);
    jdbc.update(
        "insert into"
            + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
            + " values"
            + " (?,?,?,'Requester','Role','SPECIALIST','DRAFT','SUGGEST',current_timestamp,current_timestamp)",
        requester,
        org,
        team);
  }

  UUID organization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into organizations values"
            + " (?,'Org',?,null,'ACTIVE',current_timestamp,current_timestamp)",
        id,
        id.toString());
    return id;
  }

  UUID team(UUID o) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        id,
        o);
    return id;
  }

  UUID request(String state) {
    return request(org, team, state);
  }

  UUID request(UUID o, UUID t, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " approval_requests(id,organization_id,team_id,title,action_type,risk_level,status,created_at,updated_at)"
            + " values (?,?,?,'Safe"
            + " title','PUBLISH_PREVIEW','HIGH',?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",
        id,
        o,
        t,
        state);
    return id;
  }

  HumanIdentity human() {
    return new HumanIdentity(account, "reader@example.test", "Reader");
  }

  RequestPostProcessor auth() {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(human(), null, List.of()));
  }

  String path(UUID o) {
    return "/api/v1/organizations/" + o + "/approvals";
  }

  @Test
  void emptyIsRealAndMemberReadsSafeProjection() throws Exception {
    assertThat(service.list(org, human(), Group.PENDING, 0).counts().pending()).isZero();
    request("PENDING");
    var raw =
        mvc.perform(get(path(org)).with(auth()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var data = json.readTree(raw).path("data");
    assertThat(data.path("items").size()).isEqualTo(1);
    assertThat(data.path("items").get(0).size()).isEqualTo(16);
    assertThat(raw)
        .doesNotContain("password", "payload", "decisionReason", "approvedBy", "credentialVersion");
  }

  @Test
  void groupsExactCountsAndStablePaging() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 24; i++) ids.add(request("PENDING"));
    request("NEEDS_INFORMATION");
    for (String state : List.of("APPROVED", "REJECTED", "EXPIRED", "CANCELLED", "INVALIDATED"))
      request(state);
    request(foreign, team(foreign), "PENDING");
    var first = service.list(org, human(), Group.PENDING, 0);
    var second = service.list(org, human(), Group.PENDING, 1);
    assertThat(first.counts().pending()).isEqualTo(24);
    assertThat(first.counts().needsInformation()).isEqualTo(1);
    assertThat(first.counts().decided()).isEqualTo(5);
    ids.sort(Comparator.comparing(UUID::toString));
    assertThat(first.items()).extracting(i -> i.id()).containsExactlyElementsOf(ids.subList(0, 20));
    assertThat(second.items())
        .extracting(i -> i.id())
        .containsExactlyElementsOf(ids.subList(20, 24));
    assertThat(service.list(org, human(), Group.DECIDED, 0).items()).hasSize(5);
    assertThat(service.list(org, human(), Group.NEEDS_INFORMATION, 0).items()).hasSize(1);
    assertThat(service.list(org, human(), Group.PENDING, 100000).items()).isEmpty();
  }

  @Test
  void freshAuthorizationRejectsForeignDisabledStaleAndRevoked() throws Exception {
    mvc.perform(get(path(foreign)).with(auth())).andExpect(status().isForbidden());
    jdbc.update("update human_accounts set enabled=false where id=?", account);
    assertThatThrownBy(() -> service.list(org, human(), Group.PENDING, 0))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set enabled=true,credential_version=1 where id=?", account);
    assertThatThrownBy(() -> service.list(org, human(), Group.PENDING, 0))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("update human_accounts set credential_version=0 where id=?", account);
    jdbc.update("delete from organization_memberships where account_id=?", account);
    mvc.perform(get(path(org)).with(auth())).andExpect(status().isForbidden());
  }

  @Test
  void invalidFiltersAndNoDecisionEndpoint() throws Exception {
    for (String q :
        List.of("?page=-1", "?page=100001", "?page=no", "?group=ALL", "?group=APPROVED"))
      mvc.perform(get(path(org) + q).with(auth())).andExpect(status().isBadRequest());
    mvc.perform(post(path(org)).with(auth()).with(csrf())).andExpect(status().isMethodNotAllowed());
    mvc.perform(get(path(org))).andExpect(status().isUnauthorized());
  }

  @Test
  void movedRequesterIsNotLeakedAndReadsDoNotMutate() {
    UUID id = request("PENDING");
    jdbc.update("update approval_requests set requested_by_agent_id=? where id=?", requester, id);
    var before = jdbc.queryForMap("select * from approval_requests where id=?", id);
    assertThat(service.list(org, human(), Group.PENDING, 0).items().getFirst().requesterName())
        .isEqualTo("Requester");
    jdbc.update("update agents set team_id=? where id=?", otherTeam, requester);
    var row = service.list(org, human(), Group.PENDING, 0).items().getFirst();
    assertThat(row.requesterId()).isNull();
    assertThat(row.requesterName()).isNull();
    assertThat(row.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(jdbc.queryForMap("select * from approval_requests where id=?", id))
        .isEqualTo(before);
  }

  @Test
  void storageRejectsForeignScopeInvalidStatesAndTaskWithoutMission() {
    UUID id = request("PENDING");
    assertThatThrownBy(
            () ->
                jdbc.update("update approval_requests set team_id=? where id=?", team(foreign), id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update approval_requests set requested_by_agent_id=? where id=?",
                    UUID.randomUUID(),
                    id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("update approval_requests set status='READY' where id=?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update approval_requests set task_id=? where id=?", UUID.randomUUID(), id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}

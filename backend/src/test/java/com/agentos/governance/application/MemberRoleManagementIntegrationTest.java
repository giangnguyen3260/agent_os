package com.agentos.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.MemberRoleManagementResponse.Command;
import com.agentos.governance.api.MemberRoleManagementResponse.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
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
class MemberRoleManagementIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired MemberRoleManagementService service;
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

  String root(UUID target) {
    return "/api/v1/organizations/" + org + "/members/" + target + "/role-management";
  }

  Command command(int revision, String before, String after, String reason) {
    return new Command(revision, before, after, reason, true);
  }

  Command promote() {
    return command(0, "MEMBER", "ADMIN", "  Private reason 😀  ");
  }

  MemberRoleManagementService.Mutation write(UUID key) {
    return service.mutate(org, member, human(admin), key, promote());
  }

  String role(UUID account) {
    return jdbc.queryForObject(
        "select role from organization_memberships where organization_id=? and account_id=?",
        String.class,
        org,
        account);
  }

  long count(String table) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where organization_id=?", Long.class, org);
  }

  @Test
  void contextIsPartialReadOnlyAndOrdered() {
    var blank = service.context(org, member, human(admin), null);
    assertThat(blank.selectedRole()).isNull();
    assertThat(blank.canChange()).isFalse();
    assertThat(blank.revision()).isZero();
    assertThat(service.context(org, member, human(admin), "ADMIN").canChange()).isTrue();
    assertThat(service.context(org, member, human(admin), "MEMBER").blockers())
        .containsExactly("NO_CHANGE");
    assertThat(service.context(org, admin, human(admin), "MEMBER").blockers())
        .containsExactly("SELF_CHANGE", "LAST_ENABLED_ADMIN");
    jdbc.update("update human_accounts set enabled=false where id=?", member);
    assertThat(service.context(org, member, human(admin), "MEMBER").blockers())
        .containsExactly("TARGET_DISABLED", "NO_CHANGE");
    assertThat(count("member_role_ledgers")).isZero();
  }

  @Test
  void canonicalLiteralUtf8VectorsAndJavaStrip() {
    UUID o = UUID.fromString("80808080-8080-4080-8080-808080808080");
    UUID a = UUID.fromString("80800000-0000-4000-8000-000000000002");
    assertThat(service.commandHash(o, a, command(0, "MEMBER", "ADMIN", "é\r\n😀")))
        .isEqualTo("a6ee4d1ac1fa261229dcb1aeed52ffb420e6cf3ecedf1ba3dafdc4d99d16fe2c");
    assertThat(service.commandHash(o, a, command(0, "MEMBER", "ADMIN", "e\u0301\r\n😀")))
        .isEqualTo("39e3e2f8e98a2eea93af07603c598aa1772d28163ed1e75c3988415758270955");
    assertThat(service.commandHash(o, a, command(0, "MEMBER", "ADMIN", "\u2003é\r\n😀\u2003")))
        .isEqualTo(service.commandHash(o, a, command(0, "MEMBER", "ADMIN", "é\r\n😀")));
    assertThat(service.commandHash(o, a, command(0, "MEMBER", "ADMIN", "\u00a0é\u00a0")))
        .isNotEqualTo(service.commandHash(o, a, command(0, "MEMBER", "ADMIN", "é")));
    assertThat(service.commandHash(UUID.fromString(o.toString().toUpperCase()), a, promote()))
        .isEqualTo(service.commandHash(o, a, promote()));
  }

  @Test
  void strictUuidSyntaxAndCaseNormalization() throws Exception {
    String mixed = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF";
    assertThat(
            com.agentos.governance.api.MemberRoleManagementCommandDeserializer.uuid(mixed)
                .toString())
        .isEqualTo("abcdefab-cdef-4abc-8def-abcdefabcdef");
    for (String invalid : List.of("1-1-1-1-1", " " + mixed, mixed + " ", mixed.replace("-", ""))) {
      assertThatThrownBy(
              () ->
                  com.agentos.governance.api.MemberRoleManagementCommandDeserializer.uuid(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    mvc.perform(
            get("/api/v1/organizations/nonsense/members/" + member + "/role-management/context")
                .with(actor(admin)))
        .andExpect(status().isForbidden());
    mvc.perform(get(root(member) + "/receipts/1-1-1-1-1").with(actor(admin)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/organizations/" + org + "/members/1-1-1-1-1/role-management/context")
                .with(actor(admin)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", "1-1-1-1-1")
                .contentType("application/json")
                .content(json.writeValueAsString(promote())))
        .andExpect(status().isBadRequest());
    assertThat(count("member_role_ledgers")).isZero();
  }

  @Test
  void reasonBoundsCountCodePointsAndRejectControls() {
    write(UUID.randomUUID());
    for (String reason :
        List.of("", " \u2003", "a\u0000b", "a\u001fb", "a\u007fb", "😀".repeat(1001))) {
      assertThatThrownBy(
              () ->
                  service.mutate(
                      org,
                      member,
                      human(admin),
                      UUID.randomUUID(),
                      command(1, "ADMIN", "MEMBER", reason)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    service.mutate(
        org,
        member,
        human(admin),
        UUID.randomUUID(),
        command(1, "ADMIN", "MEMBER", "😀".repeat(1000)));
    assertThat(service.history(org, member, human(admin), 0).items().getFirst().reason())
        .isEqualTo("😀".repeat(1000));
  }

  @Test
  void unpairedSurrogatesAreRejectedBeforeHashingOrWritingAndOnWire() throws Exception {
    for (char surrogate : new char[] {0xD800, 0xDBFF, 0xDC00, 0xDFFF}) {
      for (String reason :
          List.of(
              String.valueOf(surrogate),
              surrogate + "end",
              "start" + surrogate,
              "inside" + surrogate + "reason")) {
        Command invalid = command(0, "MEMBER", "ADMIN", reason);
        assertThatThrownBy(() -> service.commandHash(org, member, invalid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> service.mutate(org, member, human(admin), UUID.randomUUID(), invalid))
            .isInstanceOf(IllegalArgumentException.class);
        // Send escaped ASCII JSON so the HTTP encoder cannot replace the invalid UTF-16
        // before Jackson and the strict command deserializer inspect it.
        String escaped =
            reason.replace(String.valueOf(surrogate), String.format("\\u%04x", (int) surrogate));
        String body =
            "{\"expectedRevision\":0,\"expectedCurrentRole\":\"MEMBER\",\"newRole\":\"ADMIN\",\"reason\":\""
                + escaped
                + "\",\"acknowledgement\":true}";
        assertThatThrownBy(() -> json.readValue(body, Command.class))
            .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        mvc.perform(
                post(root(member))
                    .with(actor(admin))
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isBadRequest());
      }
    }
    assertThat(count("member_role_ledgers")).isZero();
    assertThat(count("member_role_events")).isZero();
    assertThat(count("member_role_receipts")).isZero();
    assertThat(role(member)).isEqualTo("MEMBER");
  }

  @Test
  void receiptReplayIsHistoricalAndActorSnapshotIsTrusted() {
    UUID key = UUID.randomUUID();
    var original = write(key);
    assertThat(original.replay()).isFalse();
    assertThat(original.result().actorName()).isEqualTo("DB actor");
    assertThat(original.result().recordedAt().getNano() % 1000).isZero();
    assertThat(write(key).replay()).isTrue();
    assertThat(write(key).result()).isEqualTo(original.result());
    service.mutate(
        org, member, human(admin), UUID.randomUUID(), command(1, "ADMIN", "MEMBER", "demote"));
    assertThat(write(key).result()).isEqualTo(original.result());
    assertThat(service.receipt(org, member, human(admin), key)).isEqualTo(original.result());
    assertThat(service.receipt(org, outsider, human(admin), key)).isNull();
    assertThatThrownBy(() -> service.mutate(org, admin, human(admin), key, promote()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(count("member_role_events")).isEqualTo(2);
    assertThat(count("member_role_receipts")).isEqualTo(2);
  }

  @Test
  void staleRoleRevisionAbaNoopAndExhaustionHaveCorrectPrecedence() throws Exception {
    write(UUID.randomUUID());
    service.mutate(
        org, member, human(admin), UUID.randomUUID(), command(1, "ADMIN", "MEMBER", "back"));
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(promote())))
        .andExpect(status().isConflict());
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(command(1, "MEMBER", "MEMBER", "noop stale"))))
        .andExpect(status().isConflict());
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(command(2, "MEMBER", "MEMBER", "noop"))))
        .andExpect(status().isBadRequest());
    jdbc.update(
        "update member_role_ledgers set revision=2147483647 where organization_id=? and"
            + " account_id=?",
        org,
        member);
    assertThatThrownBy(
            () ->
                service.mutate(
                    org,
                    member,
                    human(admin),
                    UUID.randomUUID(),
                    command(Integer.MAX_VALUE, "MEMBER", "ADMIN", "exhausted")))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(count("member_role_events")).isEqualTo(2);
  }

  @Test
  void retainedHistoryAndReplayNeedNoCurrentTarget() {
    UUID key = UUID.randomUUID();
    Result result = write(key).result();
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    jdbc.update("update human_accounts set enabled=false where id=?", member);
    assertThat(service.history(org, member, human(admin), 0).items().getFirst().reason())
        .isEqualTo("Private reason 😀");
    assertThat(service.receipt(org, member, human(admin), key)).isEqualTo(result);
    assertThat(write(key).result()).isEqualTo(result);
    assertThatThrownBy(() -> service.context(org, member, human(admin), null))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(() -> service.history(org, outsider, human(admin), 0))
        .isInstanceOf(EntityNotFoundException.class);
    assertThat(service.history(org, admin, human(admin), 0).items()).isEmpty();
    assertThatThrownBy(() -> service.history(org, member, human(admin), 100001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freshAuthorityRequiredForReadsWritesAndHistoricalRecovery() {
    UUID key = UUID.randomUUID();
    Result result = write(key).result();
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        admin);
    assertThatThrownBy(() -> write(key)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.receipt(org, member, human(admin), key))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.history(org, member, human(admin), 0))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update(
        "update organization_memberships set role='ADMIN' where organization_id=? and account_id=?",
        org,
        admin);
    assertThat(write(key).result()).isEqualTo(result);
    jdbc.update("update human_accounts set credential_version=1 where id=?", admin);
    assertThatThrownBy(() -> write(key)).isInstanceOf(AccessDeniedException.class);
    UUID currentMember = account(org, "MEMBER");
    assertThatThrownBy(() -> service.context(org, outsider, human(currentMember), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(service.context(org, admin, human(member), null).member().accountId())
        .isEqualTo(admin);
    assertThatThrownBy(() -> service.context(org, outsider, human(member), null))
        .isInstanceOf(EntityNotFoundException.class);
    assertThatThrownBy(() -> service.context(org, outsider, human(outsider), null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void strictWireRejectsDuplicatesNullsCoercionsAndPrivateInputWithoutEcho() throws Exception {
    String valid = json.writeValueAsString(promote());
    var invalid = new java.util.ArrayList<String>();
    invalid.add(
        valid.replace("\"expectedRevision\":0", "\"expectedRevision\":0,\"expectedRevision\":0"));
    invalid.add(valid.replace("\"expectedRevision\":0", "\"expectedRevision\":0.0"));
    invalid.add(valid.replace("\"expectedRevision\":0", "\"expectedRevision\":2147483648"));
    invalid.add(valid.replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""));
    invalid.add(valid.replace("\"newRole\":\"ADMIN\"", "\"newRole\":\"admin\""));
    invalid.add(valid.replace("\"acknowledgement\":true", "\"acknowledgement\":\"true\""));
    invalid.add(valid.replace("\"acknowledgement\":true", "\"acknowledgement\":false"));
    invalid.add(valid.replace("{", "{\"privateUnknown\":\"DO_NOT_ECHO\","));
    for (String field :
        List.of(
            "expectedRevision", "expectedCurrentRole", "newRole", "reason", "acknowledgement")) {
      var n = json.readTree(valid).deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) n).putNull(field);
      invalid.add(n.toString());
      ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove(field);
      invalid.add(n.toString());
    }
    invalid.add("null");
    invalid.add("[]");
    for (String body : invalid) {
      String response =
          mvc.perform(
                  post(root(member))
                      .with(actor(admin))
                      .with(csrf())
                      .header("Idempotency-Key", UUID.randomUUID())
                      .contentType("application/json")
                      .content(body))
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(response).doesNotContain("DO_NOT_ECHO", "Private reason");
    }
    assertThat(count("member_role_ledgers")).isZero();
  }

  @Test
  void mockMvcScopeCsrfStatusesAndExactDtos() throws Exception {
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(promote())))
        .andExpect(status().isForbidden());
    mvc.perform(get(root(outsider) + "/context").with(actor(admin)))
        .andExpect(status().isNotFound());
    mvc.perform(get(root(outsider) + "/history").with(actor(member)))
        .andExpect(status().isForbidden());
    mvc.perform(get(root(member) + "/context?newRole=admin").with(actor(admin)))
        .andExpect(status().isBadRequest());
    mvc.perform(get(root(member) + "/history?page=-1").with(actor(admin)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(root(admin))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(command(0, "ADMIN", "MEMBER", "self"))))
        .andExpect(status().isBadRequest());
    jdbc.update("update human_accounts set enabled=false where id=?", member);
    mvc.perform(
            post(root(member))
                .with(actor(admin))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(promote())))
        .andExpect(status().isBadRequest());
    jdbc.update("update human_accounts set enabled=true where id=?", member);
    String context =
        mvc.perform(get(root(member) + "/context").with(actor(admin)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var fields = new java.util.HashSet<String>();
    json.readTree(context).get("data").fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "organizationId",
            "accountId",
            "checkedAt",
            "revision",
            "member",
            "selectedRole",
            "enabledAdminCount",
            "canChange",
            "blockers");
    UUID key = UUID.randomUUID();
    String first =
        mvc.perform(
                post(root(member))
                    .with(actor(admin))
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsString(promote())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String replay =
        mvc.perform(
                post(root(member))
                    .with(actor(admin))
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsString(promote())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(replay).get("data")).isEqualTo(json.readTree(first).get("data"));
    fields.clear();
    json.readTree(first).get("data").fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "operation",
            "organizationId",
            "accountId",
            "actorId",
            "actorName",
            "idempotencyKey",
            "eventId",
            "revision",
            "recordedAt",
            "beforeRole",
            "afterRole");
    assertThat(first).doesNotContain("Private reason", "email", "password");
  }

  @Test
  void demotionPreservesGrantLedgerCredentialsOtherMembershipAndSafeAudit() throws Exception {
    jdbc.update(
        "update organization_memberships set role='ADMIN' where organization_id=? and account_id=?",
        org,
        member);
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        member,
        foreign);
    UUID grant = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at)"
            + " values (?,?,?,?,'PUBLISH_PREVIEW','HIGH',?)",
        grant,
        org,
        member,
        team,
        OffsetDateTime.now().plusDays(1));
    jdbc.update("insert into approval_grant_ledgers values (?,?,7)", org, member);
    var before = jdbc.queryForList("select * from human_accounts where id=?", member);
    var grants = jdbc.queryForList("select * from approval_grants where account_id=?", member);
    service.mutate(
        org,
        member,
        human(admin),
        UUID.randomUUID(),
        command(0, "ADMIN", "MEMBER", "private demotion"));
    assertThat(role(member)).isEqualTo("MEMBER");
    assertThat(jdbc.queryForList("select * from human_accounts where id=?", member))
        .isEqualTo(before);
    assertThat(jdbc.queryForList("select * from approval_grants where account_id=?", member))
        .isEqualTo(grants);
    assertThat(
            jdbc.queryForObject(
                "select revision from approval_grant_ledgers where organization_id=? and"
                    + " account_id=?",
                Integer.class,
                org,
                member))
        .isEqualTo(7);
    assertThat(
            jdbc.queryForObject(
                "select role from organization_memberships where organization_id=? and"
                    + " account_id=?",
                String.class,
                foreign,
                member))
        .isEqualTo("ADMIN");
    String audit =
        jdbc.queryForObject(
            "select payload from audit_events where organization_id=? and"
                + " event_type='MEMBER_ROLE_CHANGED'",
            String.class,
            org);
    assertThat(json.readTree(audit).size()).isEqualTo(2);
    assertThat(audit).doesNotContain("private demotion", "email");
  }

  @Autowired ApprovalGrantManagementService grantService;

  @Test
  void grantAndRoleIdempotencyNamespacesAreIndependent() {
    UUID key = UUID.randomUUID();
    var grant =
        grantService.mutate(
            org,
            member,
            null,
            human(admin),
            key,
            new com.agentos.governance.api.ApprovalGrantManagementResponse.Command(
                0, team, "PUBLISH_PREVIEW", "HIGH", 1, null, "Separate grant", true));
    var role = write(key);
    assertThat(grant.result().idempotencyKey()).isEqualTo(role.result().idempotencyKey());
    assertThat(grant.result().eventId()).isNotEqualTo(role.result().eventId());
    assertThat(count("approval_grant_management_receipts")).isEqualTo(1);
    assertThat(count("member_role_receipts")).isEqualTo(1);
    assertThat(write(key).result()).isEqualTo(role.result());
  }

  @Test
  void historyExactFieldsPaginationAndRetainedLedgerOnRejoin() throws Exception {
    for (int revision = 0; revision < 21; revision++) {
      boolean promote = revision % 2 == 0;
      service.mutate(
          org,
          member,
          human(admin),
          UUID.randomUUID(),
          command(
              revision,
              promote ? "MEMBER" : "ADMIN",
              promote ? "ADMIN" : "MEMBER",
              "reason " + revision));
    }
    var history = service.history(org, member, human(admin), 0);
    assertThat(history.total()).isEqualTo(21);
    assertThat(history.items()).hasSize(20);
    assertThat(history.items().getFirst().revision()).isEqualTo(21);
    assertThat(service.history(org, member, human(admin), 1).items().getFirst().revision())
        .isEqualTo(1);
    var node = json.valueToTree(history);
    var fields = new java.util.HashSet<String>();
    node.fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "organizationId", "accountId", "checkedAt", "items", "page", "size", "total");
    fields.clear();
    node.get("items").get(0).fieldNames().forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "id",
            "actorId",
            "actorName",
            "beforeRole",
            "afterRole",
            "reason",
            "recordedAt",
            "revision");
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        member);
    assertThat(service.history(org, member, human(admin), 0).total()).isEqualTo(21);
    // Privileged rejoin is only a persistence-retention assertion, not a delivered API.
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        member,
        org);
    assertThat(service.context(org, member, human(admin), null).revision()).isEqualTo(21);
    assertThatThrownBy(
            () ->
                service.mutate(
                    org,
                    member,
                    human(admin),
                    UUID.randomUUID(),
                    command(0, "ADMIN", "MEMBER", "stale incarnation")))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void finalAuditFailureRollsBackMembershipLedgerEventReceipt() {
    String constraint = "reject_role_" + org.toString().replace("-", "");
    jdbc.execute(
        "alter table audit_events add constraint "
            + constraint
            + " check (organization_id<>'"
            + org
            + "')");
    try {
      assertThatThrownBy(() -> write(UUID.randomUUID()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(role(member)).isEqualTo("MEMBER");
      for (String table :
          List.of(
              "member_role_ledgers", "member_role_events", "member_role_receipts", "audit_events"))
        assertThat(count(table)).isZero();
    } finally {
      jdbc.execute("alter table audit_events drop constraint " + constraint);
    }
  }

  @Test
  void concurrentPeerDemotionsLeaveOneEnabledAdmin() throws Exception {
    jdbc.update(
        "update organization_memberships set role='ADMIN' where organization_id=? and account_id=?",
        org,
        member);
    var pool = Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () -> {
                start.await();
                return demotePeer(admin, member);
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return demotePeer(member, admin);
              });
      start.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(count("member_role_events")).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from organization_memberships where organization_id=? and"
                      + " role='ADMIN'",
                  Long.class,
                  org))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  boolean demotePeer(UUID actor, UUID target) {
    try {
      service.mutate(
          org, target, human(actor), UUID.randomUUID(), command(0, "ADMIN", "MEMBER", "peer"));
      return true;
    } catch (AccessDeniedException ex) {
      return false;
    }
  }
}

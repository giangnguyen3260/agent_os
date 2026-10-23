package com.agentos.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.application.MemberAccessService.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberAccessIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MemberAccessService service;
  @Autowired PlatformTransactionManager transactions;
  UUID org, foreign, team, foreignTeam, admin, member, outsider;

  @BeforeEach
  void setup() {
    org = organization();
    foreign = organization();
    team = team(org);
    foreignTeam = team(foreign);
    admin = account(org, "Admin", "ADMIN", true);
    member = account(org, "Literal %_\\ Reader", "MEMBER", false);
    outsider = account(foreign, "Foreign private", "ADMIN", true);
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

  UUID team(UUID scope) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Team','ACTIVE',current_timestamp,current_timestamp)",
        id,
        scope);
    return id;
  }

  UUID account(UUID scope, String name, String role, boolean enabled) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash,enabled) values"
            + " (?,?,?,'SECRET_HASH',?)",
        id,
        id + "@example.test",
        name,
        enabled);
    jdbc.update("insert into organization_memberships values (?,?,?)", id, scope, role);
    return id;
  }

  UUID grant(UUID scope, UUID target, UUID parent, String expiry, String revoked) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at,revoked_at)"
            + " values (?,?,?,?,'UNSUPPORTED_ACTION','HIGH',?,?)",
        id,
        scope,
        target,
        parent,
        OffsetDateTime.parse(expiry),
        revoked == null ? null : OffsetDateTime.parse(revoked));
    return id;
  }

  HumanIdentity human() {
    return new HumanIdentity(admin, "admin@example.test", "Admin");
  }

  RequestPostProcessor auth() {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(human(), null, List.of()));
  }

  String path() {
    return "/api/v1/organizations/" + org + "/members";
  }

  String grants(UUID target) {
    return path() + "/" + target + "/approval-grants";
  }

  @Test
  void disabledTargetScopedCountsLiteralSearchAndSafeDtos() throws Exception {
    grant(org, member, team, "2099-01-01T00:00:00Z", null);
    grant(org, member, team, "2000-01-01T00:00:00Z", null);
    grant(org, member, team, "2000-01-01T00:00:00Z", "2001-01-01T00:00:00Z");
    grant(foreign, member, foreignTeam, "2099-01-01T00:00:00Z", null);
    grant(org, admin, team, "2099-01-01T00:00:00Z", null);
    for (String query : List.of("%", "_", "\\", "  literal  ", "\u2003LITERAL\u2003")) {
      var result = service.inventory(org, human(), 0, query, "MEMBER");
      assertThat(result.total()).isEqualTo(1);
      var row = result.items().getFirst();
      assertThat(row.accountId()).isEqualTo(member);
      assertThat(row.enabled()).isFalse();
      assertThat(row.isSelf()).isFalse();
      assertThat(row.grantCounts().unexpired()).isEqualTo(1);
      assertThat(row.grantCounts().expired()).isEqualTo(1);
      assertThat(row.grantCounts().revoked()).isEqualTo(1);
    }
    assertThat(service.inventory(org, human(), 0, "no match", "").total()).isZero();
    assertThat(service.inventory(org, human(), 0, "", "ADMIN").items().getFirst().isSelf())
        .isTrue();
    for (String url : List.of(path(), grants(member))) {
      String raw =
          mvc.perform(get(url).with(auth()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(raw)
          .doesNotContain(
              "password",
              "SECRET_HASH",
              "credential",
              "session",
              "audit",
              "effective",
              "Foreign private");
      var data = json.readTree(raw).path("data");
      assertThat(data.size()).isEqualTo(url.equals(path()) ? 6 : 7);
      assertThat(data.path("items").get(0).size()).isEqualTo(url.equals(path()) ? 7 : 10);
      if (!url.equals(path())) {
        assertThat(data.path("member").size()).isEqualTo(6);
        assertThat(data.path("items").get(0).has("revokedAt")).isTrue();
      }
    }
  }

  @Test
  void currentAdminAccountVersionAndMembershipRequiredOnBothReads() throws Exception {
    for (String change :
        List.of(
            "update organization_memberships set role='MEMBER' where account_id=?",
            "update human_accounts set enabled=false where id=?",
            "update human_accounts set credential_version=1 where id=?",
            "delete from organization_memberships where account_id=?")) {
      jdbc.update(change, admin);
      assertThatThrownBy(() -> service.inventory(org, human(), 0, "", ""))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.grants(org, member, human(), 0, Status.ALL))
          .isInstanceOf(AccessDeniedException.class);
      jdbc.update("update human_accounts set enabled=true,credential_version=0 where id=?", admin);
      jdbc.update("delete from organization_memberships where account_id=?", admin);
      jdbc.update("insert into organization_memberships values (?,?,'ADMIN')", admin, org);
    }
    jdbc.update("update organization_memberships set role='MEMBER' where account_id=?", admin);
    mvc.perform(get(path()).with(auth())).andExpect(status().isForbidden());
    mvc.perform(get(grants(member)).with(auth())).andExpect(status().isForbidden());
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> service.inventory(org, null, 0, "", ""))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                service.inventory(
                    org, new HumanIdentity(UUID.randomUUID(), "missing", "Missing"), 0, "", ""))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void organizationAuthorizationPrecedesMissingOrForeignMember() throws Exception {
    for (UUID scope : List.of(foreign, UUID.randomUUID())) {
      assertThatThrownBy(() -> service.inventory(scope, human(), 0, "", ""))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.grants(scope, UUID.randomUUID(), human(), 0, Status.ALL))
          .isInstanceOf(AccessDeniedException.class);
    }
    for (UUID target : List.of(outsider, UUID.randomUUID())) {
      mvc.perform(get(grants(target)).with(auth())).andExpect(status().isNotFound());
      assertThatThrownBy(() -> service.grants(org, target, human(), 0, Status.ALL))
          .isInstanceOf(EntityNotFoundException.class);
    }
    jdbc.update("delete from organization_memberships where account_id=?", member);
    mvc.perform(get(grants(member)).with(auth())).andExpect(status().isNotFound());
  }

  @Test
  void lifecycleStatusesAsOfTimestampStableGrantPagesAndNoEffects() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 24; i++) ids.add(grant(org, member, team, "2099-01-01T00:00:00Z", null));
    grant(org, member, team, "2000-01-01T00:00:00Z", null);
    grant(org, member, team, "2000-01-01T00:00:00Z", "2001-01-01T00:00:00Z");
    grant(org, member, team, "2099-01-01T00:00:00Z", "2001-01-01T00:00:00Z");
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    var before =
        jdbc.queryForList("select * from approval_grants where organization_id=? order by id", org);
    ids.sort(Comparator.comparing(UUID::toString));
    var first = service.grants(org, member, human(), 0, Status.UNEXPIRED);
    assertThat(first.total()).isEqualTo(24);
    assertThat(first.items()).extracting(i -> i.id()).containsExactlyElementsOf(ids.subList(0, 20));
    assertThat(service.grants(org, member, human(), 1, Status.UNEXPIRED).items())
        .extracting(i -> i.id())
        .containsExactlyElementsOf(ids.subList(20, 24));
    assertThat(service.grants(org, member, human(), 0, Status.EXPIRED).total()).isEqualTo(1);
    assertThat(service.grants(org, member, human(), 0, Status.REVOKED).total()).isEqualTo(2);
    var all = service.grants(org, member, human(), 0, Status.ALL);
    assertThat(all.total()).isEqualTo(27);
    for (Status filter : Status.values())
      for (var row : service.grants(org, member, human(), 0, filter).items()) {
        assertThat(row.status())
            .isEqualTo(
                row.revokedAt() != null
                    ? "REVOKED"
                    : !row.expiresAt().isAfter(all.generatedAt()) ? "EXPIRED" : "UNEXPIRED");
        assertThat(row.actionType()).isEqualTo("UNSUPPORTED_ACTION");
        assertThat(row.teamName()).isEqualTo("Team");
      }
    assertThat(
            jdbc.queryForList(
                "select * from approval_grants where organization_id=? order by id", org))
        .isEqualTo(before);
  }

  @Test
  void boundedStableMemberPagesAndInvalidFilters() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 24; i++) ids.add(account(org, "Same", "MEMBER", true));
    ids.sort(Comparator.comparing(UUID::toString));
    assertThat(service.inventory(org, human(), 0, "same", "").items())
        .extracting(i -> i.accountId())
        .containsExactlyElementsOf(ids.subList(0, 20));
    assertThat(service.inventory(org, human(), 1, "same", "").items())
        .extracting(i -> i.accountId())
        .containsExactlyElementsOf(ids.subList(20, 24));
    assertThat(service.inventory(org, human(), 100000, "", "").items()).isEmpty();
    assertThat(service.grants(org, member, human(), 100000, Status.ALL).items()).isEmpty();
    for (String query :
        List.of("page=-1", "page=100001", "page=x", "role=OWNER", "query=" + "x".repeat(201)))
      mvc.perform(get(path() + "?" + query).with(auth())).andExpect(status().isBadRequest());
    for (String query : List.of("page=-1", "page=100001", "page=x", "status=ACTIVE"))
      mvc.perform(get(grants(member) + "?" + query).with(auth()))
          .andExpect(status().isBadRequest());
  }

  @Test
  void exactBoundaryAndMicrosecondPrecisionUseOneGeneratedAt() {
    Instant at = Instant.parse("2026-10-23T12:00:00.123456Z");
    grant(org, member, team, "2026-10-23T12:00:00.123455Z", null);
    grant(org, member, team, "2026-10-23T12:00:00.123456Z", null);
    grant(org, member, team, "2026-10-23T12:00:00.123457Z", null);
    grant(org, member, team, "2026-10-23T12:00:00.123456Z", "2026-10-23T12:00:00Z");
    var fixed = new MemberAccessService(jdbc, Clock.fixed(at.plusNanos(999), ZoneOffset.UTC));
    var transaction = new TransactionTemplate(transactions);
    transaction.setReadOnly(true);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.executeWithoutResult(
        s -> {
          var result = fixed.grants(org, member, human(), 0, Status.ALL);
          assertThat(result.generatedAt()).isEqualTo(at);
          assertThat(result.items())
              .extracting(i -> i.status())
              .containsExactlyInAnyOrder("UNEXPIRED", "EXPIRED", "REVOKED", "EXPIRED");
          for (var item : result.items())
            assertThat(item.status())
                .isEqualTo(
                    item.revokedAt() != null
                        ? "REVOKED"
                        : item.expiresAt().isAfter(at) ? "UNEXPIRED" : "EXPIRED");
          var counts =
              fixed.inventory(org, human(), 0, "literal", "").items().getFirst().grantCounts();
          assertThat(counts.unexpired()).isEqualTo(1);
          assertThat(counts.expired()).isEqualTo(2);
          assertThat(counts.revoked()).isEqualTo(1);
        });
  }

  @Test
  void supplementaryCodePointsAndStripWhitespaceDefineQueryBound() {
    String supplementary = "\ud83d\ude00".repeat(200);
    jdbc.update(
        "update human_accounts set display_name=? where id=?",
        supplementary.substring(0, 200),
        member);
    assertThat(service.inventory(org, human(), 0, "\u2003" + supplementary + "\u2003", "").total())
        .isZero();
    assertThat(
            service
                .inventory(
                    org, human(), 0, "\u2003" + supplementary.substring(0, 200) + "\u2003", "")
                .total())
        .isEqualTo(1);
    assertThatThrownBy(() -> service.inventory(org, human(), 0, supplementary + "x", ""))
        .isInstanceOf(IllegalArgumentException.class);
    // NBSP is not Character.isWhitespace: it remains part of the literal search.
    assertThat(
            service
                .inventory(org, human(), 0, "\u00a0" + supplementary.substring(0, 2), "")
                .total())
        .isZero();
  }

  @Test
  void repeatableReadRetainsSnapshotThenFreshReadRejectsDemotion() throws Exception {
    for (String method : List.of("inventory", "grants")) {
      var annotation =
          java.util.Arrays.stream(MemberAccessService.class.getDeclaredMethods())
              .filter(m -> m.getName().equals(method))
              .findFirst()
              .orElseThrow()
              .getAnnotation(Transactional.class);
      assertThat(annotation.readOnly()).isTrue();
      assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
    var transaction = new TransactionTemplate(transactions);
    transaction.setReadOnly(true);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.executeWithoutResult(
        s -> {
          assertThat(service.inventory(org, human(), 0, "", "").total()).isEqualTo(2);
          assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
          var independent = new TransactionTemplate(transactions);
          independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          independent.executeWithoutResult(
              other ->
                  jdbc.update(
                      "update organization_memberships set role='MEMBER' where account_id=? and"
                          + " organization_id=?",
                      admin,
                      org));
          assertThat(service.inventory(org, human(), 0, "", "").total()).isEqualTo(2);
          assertThat(service.grants(org, member, human(), 0, Status.ALL).member().accountId())
              .isEqualTo(member);
        });
    assertThatThrownBy(() -> service.inventory(org, human(), 0, "", ""))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.grants(org, member, human(), 0, Status.ALL))
        .isInstanceOf(AccessDeniedException.class);
  }
}

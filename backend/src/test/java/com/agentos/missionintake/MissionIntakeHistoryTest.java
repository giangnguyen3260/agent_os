package com.agentos.missionintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionIntakeHistoryTest extends MissionIntakeIntegrationTest {
  MissionIntakeDtos.History history(int page) {
    return service.history(org, team, mission, human, page);
  }

  void flips(int count) {
    for (int i = 0; i < count; i++) save(UUID.randomUUID(), command(context()));
  }

  @Test
  void acceptedReceiptsPageWithoutDuplicatesAndReadsHaveNoEffects() {
    flips(23);
    var before = jdbc.queryForMap("select * from missions where id=?", mission);
    var first = history(0);
    var second = history(1);
    assertThat(first.total()).isEqualTo(23);
    assertThat(first.pageSize()).isEqualTo(20);
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(3);
    var ids = new HashSet<UUID>();
    first.items().forEach(item -> assertThat(ids.add(item.eventId())).isTrue());
    second.items().forEach(item -> assertThat(ids.add(item.eventId())).isTrue());
    assertThat(history(2).items()).isEmpty();
    assertThat(history(100000).total()).isEqualTo(23);
    assertThat(jdbc.queryForMap("select * from missions where id=?", mission)).isEqualTo(before);
    assertThat(count("mission_intake_receipts")).isEqualTo(23);
    assertThat(count("audit_events")).isEqualTo(23);
    assertThat(count("tasks")).isEqualTo(1);
  }

  @Test
  void emptyHasRealScopeNamesAndNoInventedBaseline() {
    var result = history(0);
    assertThat(result.organizationId()).isEqualTo(org);
    assertThat(result.teamId()).isEqualTo(team);
    assertThat(result.missionId()).isEqualTo(mission);
    assertThat(result.teamName()).isEqualTo("Team");
    assertThat(result.missionTitle()).isEqualTo("Mission");
    assertThat(result.total()).isZero();
    assertThat(result.items()).isEmpty();
  }

  @Test
  void tieOrderUsesCanonicalUuidAndClockCorrectionsDoNotImposeVersionOrder() throws Exception {
    flips(23);
    OffsetDateTime tie = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    var rows =
        jdbc.queryForList(
            "select event_id,original_response from mission_intake_receipts where"
                + " organization_id=?",
            org);
    for (var row : rows) {
      var body =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              json.readTree(row.get("original_response").toString());
      body.put("committedAt", tie.toInstant().toString());
      jdbc.update(
          "update mission_intake_receipts set created_at=?,original_response=? where event_id=?",
          tie,
          json.writeValueAsString(body),
          row.get("event_id"));
    }
    var ordered = new java.util.ArrayList<String>();
    history(0).items().forEach(item -> ordered.add(item.eventId().toString()));
    history(1).items().forEach(item -> ordered.add(item.eventId().toString()));
    assertThat(ordered)
        .hasSize(23)
        .doesNotHaveDuplicates()
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    var oldestVersion =
        rows.stream()
            .filter(
                row -> {
                  try {
                    return json.readTree(row.get("original_response").toString())
                        .get("versionBefore")
                        .asText()
                        .equals("0");
                  } catch (java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                  }
                })
            .findFirst()
            .orElseThrow();
    var original =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(oldestVersion.get("original_response").toString());
    var later = tie.plusDays(1);
    original.put("committedAt", later.toInstant().toString());
    jdbc.update(
        "update mission_intake_receipts set created_at=?,original_response=? where event_id=?",
        later,
        json.writeValueAsString(original),
        oldestVersion.get("event_id"));
    assertThat(history(0).items().get(0).versionBefore()).isEqualTo("0");
  }

  @Test
  void realTaskCreationMakesLegitimateVersionGap() {
    flips(2);
    taskCreate.create(
        org,
        team,
        mission,
        human,
        UUID.randomUUID(),
        new com.agentos.mission.api.dto.TaskCreateRequest(
            "New", "Description", "Criteria", "NORMAL", null, java.util.List.of(), 2L));
    flips(1);
    assertThat(history(0).items())
        .extracting(MissionIntakeDtos.HistoryItem::versionBefore)
        .containsExactly("3", "1", "0");
  }

  @Test
  void currentActorNameSurvivesRoleAndEnabledChangeButRemovalIsExplicitNull() throws Exception {
    flips(1);
    UUID reader = UUID.randomUUID();
    jdbc.update(
        "insert into human_accounts(id,email,display_name,password_hash) values"
            + " (?,?,'Reader','unused')",
        reader,
        reader + "@example.test");
    jdbc.update(
        "insert into organization_memberships(account_id,organization_id,role) values"
            + " (?,?,'ADMIN')",
        reader,
        org);
    human = new com.agentos.auth.HumanIdentity(reader, reader + "@example.test", "Reader");
    jdbc.update("update human_accounts set display_name='Renamed',enabled=false where id=?", actor);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        actor);
    assertThat(history(0).items().get(0).actorName()).isEqualTo("Renamed");
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        actor);
    var response =
        mvc.perform(get(url() + "/history").with(auth()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var data = json.readTree(response).get("data");
    assertThat(data.get("items").get(0).has("actorName")).isTrue();
    assertThat(data.get("items").get(0).get("actorName").isNull()).isTrue();
    assertThat(data.size()).isEqualTo(9);
    assertThat(data.get("items").get(0).size()).isEqualTo(7);
    assertThat(response)
        .doesNotContain(
            actor.toString(), "@example.test", "body_sha256", "original_response", "idempotency");
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "FAILED", "CANCELLED", "PLANNING", "REVIEW"})
  void lifecycleDoesNotRestrictHistory(String state) {
    flips(1);
    jdbc.update(
        "update missions set status=?,task_intake_version=? where id=?",
        state,
        Long.MAX_VALUE,
        mission);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    assertThat(history(0).items()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "page=",
        "page=00",
        "page=-1",
        "page=+1",
        "page=1.0",
        "page=100001",
        "page=99999999999999999999",
        "page=0&page=1",
        "page=0&x=1",
        "x=1",
        "p%61ge=0",
        "page=%30",
        "page=0&"
      })
  void strictQueryRejectsWithoutWrites(String query) throws Exception {
    mvc.perform(
            get(url() + "/history")
                .with(auth())
                .with(
                    request -> {
                      request.setQueryString(query);
                      return request;
                    }))
        .andExpect(status().isBadRequest());
    assertThat(count("mission_intake_receipts")).isZero();
    assertThat(count("audit_events")).isZero();
  }

  @Test
  void getBodyAndNoncanonicalScopeAreRejected() throws Exception {
    mvc.perform(get(url() + "/history").with(auth()).content(" "))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(url().replace(team.toString(), "A" + team.toString().substring(1)) + "/history")
                .with(auth()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(url().replace(team.toString(), UUID.randomUUID().toString()) + "/history")
                .with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get(url().replace(mission.toString(), UUID.randomUUID().toString()) + "/history")
                .with(auth()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get(url().replace(org.toString(), UUID.randomUUID().toString()) + "/history?bad=1")
                .with(auth()))
        .andExpect(status().isForbidden());
  }

  @Test
  void freshAuthorityPrecedesHistoryParser() throws Exception {
    mvc.perform(get(url() + "/history?bad=1")).andExpect(status().isUnauthorized());
    jdbc.update("update human_accounts set enabled=false where id=?", actor);
    mvc.perform(get(url() + "/history?bad=1").with(auth())).andExpect(status().isUnauthorized());
    jdbc.update("update human_accounts set enabled=true,credential_version=1 where id=?", actor);
    mvc.perform(get(url() + "/history?bad=1").with(auth())).andExpect(status().isUnauthorized());
    jdbc.update("update human_accounts set credential_version=0 where id=?", actor);
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    mvc.perform(get(url() + "/history?bad=1").with(auth())).andExpect(status().isForbidden());
    assertThatThrownBy(() -> history(0)).hasMessage("FORBIDDEN");
  }

  @Test
  void unselectedCorruptionDoesNotInventGlobalValidation() {
    flips(21);
    UUID oldest =
        jdbc.queryForObject(
            "select event_id from mission_intake_receipts where organization_id=? order by"
                + " created_at asc,event_id asc limit 1",
            UUID.class,
            org);
    jdbc.update(
        "update mission_intake_receipts set original_response='{}' where event_id=?", oldest);
    assertThat(history(0).items()).hasSize(20);
    assertThat(history(0).total()).isEqualTo(21);
    assertThatThrownBy(() -> history(1)).hasMessage("MISSION_INTAKE_HISTORY_UNAVAILABLE");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "bad\u0001name", "bad\u007fname", "bad\u0085name"})
  void eligibleUnsafeActorNameFailsClosed(String name) throws Exception {
    flips(1);
    jdbc.update("update human_accounts set display_name=? where id=?", name, actor);
    mvc.perform(get(url() + "/history").with(auth()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("error.code").value("MISSION_INTAKE_HISTORY_UNAVAILABLE"));
  }

  @Test
  void upperInt64VersionsRemainStringsInHistory() throws Exception {
    jdbc.update(
        "update missions set task_intake_version=? where id=?", Long.MAX_VALUE - 1, mission);
    flips(1);
    mvc.perform(get(url() + "/history?page=0").with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.items[0].versionBefore").value("9223372036854775806"))
        .andExpect(jsonPath("data.items[0].versionAfter").value("9223372036854775807"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "versionBefore",
        "versionAfter",
        "pausedAfter",
        "eventId",
        "organizationId",
        "committedAt",
        "extra",
        "digest",
        "json",
        "actorName",
        "teamName",
        "missionTitle"
      })
  void selectedCorruptionFailsWholePageWithExactCode(String field) throws Exception {
    flips(2);
    var row =
        jdbc.queryForMap(
            "select event_id,original_response from mission_intake_receipts where organization_id=?"
                + " order by created_at desc limit 1",
            org);
    var body =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(row.get("original_response").toString());
    switch (field) {
      case "digest" ->
          jdbc.update(
              "update mission_intake_receipts set body_sha256=? where event_id=?",
              "z".repeat(64),
              row.get("event_id"));
      case "json" ->
          jdbc.update(
              "update mission_intake_receipts set original_response='{}' where event_id=?",
              row.get("event_id"));
      case "actorName" ->
          jdbc.update("update human_accounts set display_name=? where id=?", "unsafe\u0001", actor);
      case "teamName" -> jdbc.update("update teams set name=' ' where id=?", team);
      case "missionTitle" -> jdbc.update("update missions set title=' ' where id=?", mission);
      default -> {
        switch (field) {
          case "versionBefore" -> body.put(field, "01");
          case "versionAfter" -> body.put(field, "9223372036854775808");
          case "pausedAfter" -> body.put(field, body.get("pausedBefore").booleanValue());
          case "eventId", "organizationId" -> body.put(field, UUID.randomUUID().toString());
          case "committedAt" -> body.put(field, "2000-01-01T00:00:00Z");
          default -> body.put(field, "private-secret");
        }
        jdbc.update(
            "update mission_intake_receipts set original_response=? where event_id=?",
            json.writeValueAsString(body),
            row.get("event_id"));
      }
    }
    var before = jdbc.queryForMap("select * from missions where id=?", mission);
    mvc.perform(get(url() + "/history").with(auth()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("error.code").value("MISSION_INTAKE_HISTORY_UNAVAILABLE"));
    assertThat(count("mission_intake_receipts")).isEqualTo(2);
    assertThat(count("audit_events")).isEqualTo(2);
    assertThat(jdbc.queryForMap("select * from missions where id=?", mission)).isEqualTo(before);
  }
}

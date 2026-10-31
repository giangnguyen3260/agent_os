package com.agentos.taskdependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "server.servlet.encoding.force-request=false",
      "spring.datasource.url=jdbc:h2:mem:dependency_http_qc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
          + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskDependencyHistoryIntegrationTest extends DependencyHttpFixture {
  String history() {
    return root(task) + "/history";
  }

  JsonNode save(UUID... targets) throws Exception {
    return data(send(task, UUID.randomUUID(), command(task, targets)), 201);
  }

  @Test
  void legacyEdgesDoNotInventBaselineOrWriteAnything() throws Exception {
    UUID target = node("Legacy", "DRAFT");
    edge(task, target);
    var baseline = state();
    var result = read(history());
    fields(
        result,
        "organizationId",
        "teamId",
        "missionId",
        "taskId",
        "teamName",
        "missionTitle",
        "taskTitle",
        "page",
        "pageSize",
        "total",
        "items");
    assertThat(result.path("total").asLong()).isZero();
    assertThat(result.path("items").size()).isZero();
    assertThat(state()).isEqualTo(baseline);
  }

  @Test
  void actualCommandsPageByRevisionNotTimestampAndKeepOutOfRangeTotal() throws Exception {
    UUID a = node("A", "DRAFT"), b = node("B", "COMPLETED");
    JsonNode latest = null;
    for (int i = 1; i <= 21; i++) latest = save(i % 2 == 0 ? b : a);
    jdbc.update(
        "update task_dependency_events set created_at=? where organization_id=?", clock(), org);
    var baseline = state();
    var page = read(history());
    assertThat(page.path("total").asInt()).isEqualTo(21);
    assertThat(page.path("pageSize").asInt()).isEqualTo(20);
    assertThat(page.path("items").size()).isEqualTo(20);
    for (int i = 0; i < 20; i++)
      assertThat(page.path("items").get(i).path("revision").asInt()).isEqualTo(21 - i);
    var item = page.path("items").get(0);
    fields(item, "eventId", "revision", "committedAt", "actorName", "before", "after");
    assertThat(item.get("before")).isEqualTo(latest.get("before"));
    assertThat(item.get("after")).isEqualTo(latest.get("after"));
    assertThat(read(history() + "?page=1").path("items").get(0).path("revision").asInt())
        .isEqualTo(1);
    var empty = read(history() + "?page=100000");
    assertThat(empty.path("total").asInt()).isEqualTo(21);
    assertThat(empty.path("items").size()).isZero();
    assertThat(state()).isEqualTo(baseline);
  }

  @Test
  void historicalSnapshotsSurviveMetadataChangesLifecycleAndCurrentGraphCorruption()
      throws Exception {
    UUID a = node("Recorded title", "DRAFT");
    var receipt = save(a);
    jdbc.update("update tasks set title='Changed title',status='COMPLETED' where id=?", a);
    jdbc.update("update tasks set status='COMPLETED' where id=?", task);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update(
        "update missions set status='COMPLETED',task_intake_paused=true where id=?", mission);
    edge(a, task);
    var baseline = state();
    var item = read(history()).path("items").get(0);
    assertThat(item.get("after")).isEqualTo(receipt.get("after"));
    assertThat(item.path("after").get(0).path("title").asText()).isEqualTo("Recorded title");
    assertThat(state()).isEqualTo(baseline);
  }

  @Test
  void currentMemberRenameAndRemovalNeverExposeAnUnscopedActorName() throws Exception {
    save(node("A", "DRAFT"));
    UUID author = actor;
    jdbc.update("update human_accounts set display_name='Current member' where id=?", author);
    assertThat(read(history()).path("items").get(0).path("actorName").asText())
        .isEqualTo("Current member");
    actor = admin();
    session = session(actor);
    jdbc.update(
        "delete from organization_memberships where organization_id=? and account_id=?",
        org,
        author);
    var baseline = state();
    var item = read(history()).path("items").get(0);
    assertThat(item.has("actorName")).isTrue();
    assertThat(item.get("actorName").isNull()).isTrue();
    assertThat(item.has("actorId")).isFalse();
    assertThat(state()).isEqualTo(baseline);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "[{}]",
        "[{\"id\":\"PRIVATE\",\"title\":\"PRIVATE\",\"status\":\"DRAFT\"}]",
        "[] []",
        "[",
        "[{\"id\":null,\"id\":null}]"
      })
  void malformedSelectedSnapshotFailsSafelyAndWritesNothing(String malformed) throws Exception {
    save(node("A", "DRAFT"));
    jdbc.update(
        "update task_dependency_events set after_json=? where organization_id=?", malformed, org);
    var baseline = state();
    var result = mvc.perform(get(history()).session(session).with(auth())).andReturn();
    error(result, 500, "TASK_DEPENDENCY_HISTORY_UNAVAILABLE");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE");
    assertThat(state()).isEqualTo(baseline);
  }

  @Test
  void malformedOffPageSnapshotDoesNotHideAValidPage() throws Exception {
    UUID a = node("A", "DRAFT"), b = node("B", "DRAFT");
    for (int i = 0; i < 21; i++) save(i % 2 == 0 ? a : b);
    jdbc.update(
        "update task_dependency_events set before_json='PRIVATE invalid' where organization_id=?"
            + " and revision=1",
        org);
    var baseline = state();
    assertThat(read(history()).path("items").size()).isEqualTo(20);
    error(
        mvc.perform(get(history() + "?page=1").session(session).with(auth())).andReturn(),
        500,
        "TASK_DEPENDENCY_HISTORY_UNAVAILABLE");
    assertThat(state()).isEqualTo(baseline);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?page=-1",
        "?page=01",
        "?page=1.0",
        "?page=1e0",
        "?page=100001",
        "?page=0&page=0",
        "?extra=0",
        "?p%61ge=0&page=0"
      })
  void strictQueryIsRejectedWithoutBusinessWrites(String query) throws Exception {
    var baseline = state();
    error(
        mvc.perform(get(history() + query).session(session).with(auth())).andReturn(),
        400,
        "BAD_REQUEST");
    assertThat(state()).isEqualTo(baseline);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DRAFT",
        "READY",
        "RUNNING",
        "WAITING",
        "BLOCKED",
        "IN_REVIEW",
        "COMPLETED",
        "FAILED",
        "CANCELLED"
      })
  void everyPersistedTaskLifecycleCanReadRecordedHistory(String state) throws Exception {
    save(node("Recorded", "DRAFT"));
    boolean pending = state.equals("WAITING") || state.equals("BLOCKED");
    jdbc.update(
        "update tasks set status=?,pending_reason=?,waiting_since=?,resolver=? where id=?",
        state,
        pending ? "Awaiting QC input" : null,
        pending ? clock() : null,
        pending ? "QC" : null,
        task);
    var baseline = state();
    assertThat(read(history()).path("total").asInt()).isEqualTo(1);
    assertThat(state()).isEqualTo(baseline);
  }

  @Test
  void freshAuthorityPrecedesMalformedQueryAndScopeIsExact() throws Exception {
    error(mvc.perform(get(history() + "?extra=x")).andReturn(), 401, "UNAUTHORIZED");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where organization_id=? and"
            + " account_id=?",
        org,
        actor);
    var baseline = state();
    error(
        mvc.perform(get(history() + "?extra=x").session(session).with(auth())).andReturn(),
        403,
        "FORBIDDEN");
    assertThat(state()).isEqualTo(baseline);
    jdbc.update(
        "update organization_memberships set role='ADMIN' where organization_id=? and account_id=?",
        org,
        actor);
    error(
        mvc.perform(
                get(history().replace(team.toString(), UUID.randomUUID().toString()))
                    .session(session)
                    .with(auth()))
            .andReturn(),
        404,
        "NOT_FOUND");
    error(
        mvc.perform(get(history()).session(session).with(auth()).content("{}")).andReturn(),
        400,
        "BAD_REQUEST");
  }
}

package com.agentos.taskdependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:dependency_http_qc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE"
            + " DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskDependenciesIntegrationTest extends DependencyHttpFixture {
  public static java.time.OffsetDateTime clock() {
    return DependencyHttpFixture.clock();
  }

  @Test
  void exactContextCandidatesAndReceiptWireWithSessionAndCsrf() throws Exception {
    UUID target = node("Target", "COMPLETED");
    JsonNode c = context();
    fields(
        c,
        "organizationId",
        "teamId",
        "missionId",
        "taskId",
        "checkedAt",
        "revision",
        "fingerprint",
        "teamName",
        "missionTitle",
        "taskTitle",
        "taskStatus",
        "dependencies",
        "canEdit",
        "blockers");
    assertThat(c.path("revision").isInt()).isTrue();
    assertThat(c.path("revision").intValue()).isZero();
    assertThat(c.path("dependencies").isArray()).isTrue();
    assertThat(c.path("dependencies").size()).isZero();
    assertThat(c.path("canEdit").booleanValue()).isTrue();
    assertThat(c.path("fingerprint").asText()).matches("[0-9a-f]{64}");
    java.time.Instant.parse(c.path("checkedAt").asText());
    assertThat(context().get("fingerprint")).isEqualTo(c.get("fingerprint"));
    JsonNode page = read(root(task) + "/eligible-dependencies");
    fields(
        page,
        "organizationId",
        "teamId",
        "missionId",
        "taskId",
        "page",
        "pageSize",
        "total",
        "query",
        "items");
    assertThat(page.path("pageSize").asInt()).isEqualTo(20);
    JsonNode candidate = candidate(task, target);
    fields(candidate, "id", "title", "status", "fingerprint");
    assertThat(candidate.path("status").asText()).isEqualTo("COMPLETED");
    var body = command(task, target);
    UUID key = UUID.randomUUID();
    JsonNode receipt = data(send(task, key, body), 201);
    fields(
        receipt,
        "organizationId",
        "teamId",
        "missionId",
        "taskId",
        "eventId",
        "revision",
        "committedAt",
        "before",
        "after");
    fields(receipt.path("after").get(0), "id", "title", "status");
    assertThat(receipt.path("after").get(0).path("id").asText()).isEqualTo(target.toString());
    java.time.Instant.parse(receipt.path("committedAt").asText());
    assertThat(data(send(task, key, body), 200)).isEqualTo(receipt);
    var lookup = read(root(task) + "/receipts/" + key);
    fields(lookup, "receipt");
    assertThat(lookup.get("receipt")).isEqualTo(receipt);
  }

  @Test
  void addReplaceRemoveAndClearHaveFourEffectsAndPreserveOtherFields() throws Exception {
    UUID a = node("One", "READY"), b = node("Two", "FAILED");
    jdbc.update(
        "update tasks set description='Keep description',acceptance_criteria='Keep"
            + " criteria',priority='HIGH' where id=?",
        task);
    var before = sourceRow();
    var parent = jdbc.queryForMap("select * from missions where id=?", mission);
    var teamBefore = jdbc.queryForMap("select * from teams where id=?", team);
    var targetBefore = jdbc.queryForMap("select * from tasks where id=?", a);
    data(send(task, UUID.randomUUID(), command(task, a)), 201);
    data(send(task, UUID.randomUUID(), command(task, a, b)), 201);
    data(send(task, UUID.randomUUID(), command(task, b)), 201);
    JsonNode receipt = data(send(task, UUID.randomUUID(), command(task)), 201);
    assertThat(receipt.path("before").get(0).path("id").asText()).isEqualTo(b.toString());
    assertThat(receipt.path("after").size()).isZero();
    assertThat(receipt.path("revision").asInt()).isEqualTo(4);
    var after = sourceRow();
    for (String changed : List.of("dependency_revision", "updated_at")) {
      before.remove(changed);
      after.remove(changed);
    }
    assertThat(after).isEqualTo(before);
    assertThat(jdbc.queryForMap("select * from tasks where id=?", a)).isEqualTo(targetBefore);
    assertThat(jdbc.queryForMap("select * from missions where id=?", mission)).isEqualTo(parent);
    assertThat(jdbc.queryForMap("select * from teams where id=?", team)).isEqualTo(teamBefore);
    assertThat(count("task_dependencies")).isZero();
    assertThat(count("task_dependency_events")).isEqualTo(4);
    assertThat(count("task_dependency_receipts")).isEqualTo(4);
    assertThat(count("audit_events")).isEqualTo(4);
    assertThat(count("task_edit_events")).isZero();
    assertThat(count("task_owner_events")).isZero();
    for (var row : jdbc.queryForList("select * from audit_events where organization_id=?", org)) {
      assertThat(row.get("task_id")).isEqualTo(task);
      assertThat(row.get("mission_id")).isEqualTo(mission);
      var payload = json.readTree((String) row.get("payload"));
      assertThat(payload.isObject()).isTrue();
      assertThat(payload.size()).isEqualTo(2);
      assertThat(payload.has("eventId")).isTrue();
      assertThat(payload.has("revision")).isTrue();
    }
  }

  @Test
  void noOpWritesNothingAndCanonicalOrderReplaysButChangedBodyConflicts() throws Exception {
    UUID a = node("A", "DRAFT"), b = node("B", "DRAFT");
    var empty = command(task);
    var initial = state();
    error(send(task, UUID.randomUUID(), empty), 409, "TASK_DEPENDENCIES_NO_CHANGE");
    assertThat(state()).isEqualTo(initial);
    var body = command(task, b, a);
    UUID key = UUID.randomUUID();
    JsonNode receipt = data(send(task, key, body), 201);
    List<String> ids = new ArrayList<>();
    receipt.path("after").forEach(n -> ids.add(n.path("id").asText()));
    assertThat(ids).isSorted();
    var reversed = body.deepCopy();
    var array = reversed.putArray("dependencies");
    array.add(body.path("dependencies").get(1));
    array.add(body.path("dependencies").get(0));
    var committed = state();
    assertThat(data(send(task, key, reversed), 200)).isEqualTo(receipt);
    var changed = body.deepCopy();
    changed.put("expectedFingerprint", "f".repeat(64));
    error(send(task, key, changed), 409, "IDEMPOTENCY_CONFLICT");
    error(send(task, UUID.randomUUID(), command(task, a, b)), 409, "TASK_DEPENDENCIES_NO_CHANGE");
    assertThat(state()).isEqualTo(committed);
  }

  @Test
  void historicalRecoverySurvivesBlockedParentsAndUnsafeCurrentGraphAndIsActorPrivate()
      throws Exception {
    UUID a = node("Historical target", "COMPLETED");
    var body = command(task, a);
    UUID key = UUID.randomUUID();
    var receipt = data(send(task, key, body), 201);
    jdbc.update("update tasks set title=? where id=?", "private\nunsafe", a);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update("update missions set task_intake_paused=true where id=?", mission);
    assertThat(data(send(task, key, body), 200)).isEqualTo(receipt);
    assertThat(read(root(task) + "/receipts/" + key).get("receipt")).isEqualTo(receipt);
    assertThat(read(root(task) + "/receipts/" + UUID.randomUUID()).get("receipt").isNull())
        .isTrue();
    var original = session;
    session = session(admin());
    assertThat(read(root(task) + "/receipts/" + key).get("receipt").isNull()).isTrue();
    session = original;
    error(
        mvc.perform(get(root(task)).session(session).with(auth())).andReturn(),
        500,
        "TASK_DEPENDENCIES_UNAVAILABLE");
    assertThat(count("task_dependency_events")).isEqualTo(1);
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
  void allNineStatesAndBlankLegacyTitlesAreRealSelectableRows(String state) throws Exception {
    UUID target = node("", state);
    var c = candidate(task, target);
    assertThat(c.path("title").asText()).isEmpty();
    assertThat(c.path("status").asText()).isEqualTo(state);
    data(send(task, UUID.randomUUID(), command(task, target)), 201);
    var selected = context().path("dependencies").get(0);
    assertThat(selected.path("title").asText()).isEmpty();
    assertThat(selected.path("status").asText()).isEqualTo(state);
  }

  @Test
  void literalSearchExactCountAndPagingAreIndependentOfSelections() throws Exception {
    List<UUID> targets = new ArrayList<>();
    for (int i = 0; i < 27; i++)
      targets.add(node(String.format(java.util.Locale.ROOT, "row %02d", i), "DRAFT"));
    UUID literal = node("  literal %_! needle  ", "CANCELLED");
    var page = read(root(task) + "/eligible-dependencies?page=1&query=row");
    assertThat(page.path("total").asInt()).isEqualTo(27);
    assertThat(page.path("items").size()).isEqualTo(7);
    assertThat(page.path("items").get(0).path("title").asText()).isEqualTo("row 20");
    var result =
        data(
            mvc.perform(
                    get(root(task) + "/eligible-dependencies")
                        .session(session)
                        .with(auth())
                        .queryParam("query", "  %_!  "))
                .andReturn(),
            200);
    assertThat(result.path("query").asText()).isEqualTo("%_!");
    assertThat(result.path("total").asInt()).isEqualTo(1);
    assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(literal.toString());
    data(send(task, UUID.randomUUID(), command(task, targets.get(26))), 201);
    assertThat(context().path("dependencies").size()).isEqualTo(1);
    assertThat(candidate(task, targets.get(26)).path("title").asText()).isEqualTo("row 26");
    assertThat(read(root(task) + "/eligible-dependencies?page=100000").path("items").size())
        .isZero();
  }

  @Test
  void twentyIsSelectionLimitNotGraphOrSearchLimit() throws Exception {
    UUID[] targets = new UUID[21];
    for (int i = 0; i < 21; i++) targets[i] = node("N" + i, "DRAFT");
    var tooMany = command(task, targets);
    var before = state();
    error(send(task, UUID.randomUUID(), tooMany), 400, "BAD_REQUEST");
    assertThat(state()).isEqualTo(before);
    data(send(task, UUID.randomUUID(), command(task, java.util.Arrays.copyOf(targets, 20))), 201);
    assertThat(context().path("dependencies").size()).isEqualTo(20);
    assertThat(read(root(task) + "/eligible-dependencies").path("total").asInt()).isEqualTo(21);
  }

  @Test
  void arbitraryDepthReachabilityAndDisconnectedCycleFailClosedWithoutLeaks() throws Exception {
    UUID first = node("Deep first", "DRAFT"), previous = first;
    for (int i = 0; i < 1200; i++) {
      UUID next = node("Depth " + i, "DRAFT");
      edge(previous, next);
      previous = next;
    }
    edge(previous, task);
    error(
        mvc.perform(
                get(root(task) + "/eligible-dependencies/" + first).session(session).with(auth()))
            .andReturn(),
        409,
        "TASK_DEPENDENCIES_NOT_ELIGIBLE");
    assertThat(read(root(task) + "/eligible-dependencies").path("total").asInt()).isZero();
    UUID a = node("Private cycle A", "DRAFT"), b = node("Private cycle B", "DRAFT");
    edge(a, b);
    edge(b, a);
    var before = state();
    for (String suffix : List.of("", "/eligible-dependencies", "/eligible-dependencies/" + first)) {
      var response =
          mvc.perform(get(root(task) + suffix).session(session).with(auth())).andReturn();
      error(response, 500, "TASK_DEPENDENCIES_UNAVAILABLE");
      assertThat(response.getResponse().getContentAsString())
          .doesNotContain(a.toString(), b.toString(), "Private cycle", "Depth");
    }
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void multiTargetProspectiveCycleAndMissingTargetsHaveExactErrors() throws Exception {
    UUID safe = node("Safe", "DRAFT"), ancestor = node("Ancestor", "DRAFT");
    edge(ancestor, task);
    var body = command(task, safe);
    var desired = body.withArray("dependencies");
    desired.addObject().put("taskId", ancestor.toString()).put("fingerprint", "a".repeat(64));
    var before = state();
    error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_NOT_ELIGIBLE");
    assertThat(state()).isEqualTo(before);
    desired.remove(1);
    desired
        .addObject()
        .put("taskId", UUID.randomUUID().toString())
        .put("fingerprint", "a".repeat(64));
    error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_NOT_ELIGIBLE");
    error(
        mvc.perform(
                get(root(task) + "/eligible-dependencies/" + UUID.randomUUID())
                    .session(session)
                    .with(auth()))
            .andReturn(),
        404,
        "NOT_FOUND");
  }

  @Test
  void unsafeOffPathMetadataAndExcessiveLegacyEdgesCannotBeCleared() throws Exception {
    UUID off = node("Off path", "DRAFT");
    var body = command(task);
    jdbc.update("update tasks set title=? where id=?", "private\u007fsecret", off);
    var before = state();
    error(send(task, UUID.randomUUID(), body), 500, "TASK_DEPENDENCIES_UNAVAILABLE");
    assertThat(state()).isEqualTo(before);
    jdbc.update("update tasks set title='Safe' where id=?", off);
    for (int i = 0; i < 21; i++) edge(off, node("Legacy" + i, "DRAFT"));
    error(
        mvc.perform(get(root(task)).session(session).with(auth())).andReturn(),
        500,
        "TASK_DEPENDENCIES_UNAVAILABLE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "title",
        "status",
        "updated_at",
        "task_edit_revision",
        "task_owner_revision",
        "dependency_revision",
        "new-node",
        "off-path-edge"
      })
  void fullGraphChangesInvalidateSourceAndCandidateTokens(String mutation) throws Exception {
    UUID target = node("Target", "DRAFT"),
        off = node("Off path", "DRAFT"),
        other = node("Other", "DRAFT");
    var body = command(task, target);
    var old = context();
    var oldTarget = candidate(task, target);
    switch (mutation) {
      case "title" -> jdbc.update("update tasks set title='Changed' where id=?", off);
      case "status" -> jdbc.update("update tasks set status='COMPLETED' where id=?", off);
      case "updated_at" ->
          jdbc.update("update tasks set updated_at=updated_at+interval '1' second where id=?", off);
      case "new-node" -> node("New disconnected", "DRAFT");
      case "off-path-edge" -> edge(off, other);
      default ->
          jdbc.update("update tasks set " + mutation + "=" + mutation + "+1 where id=?", off);
    }
    assertThat(context().get("fingerprint")).isNotEqualTo(old.get("fingerprint"));
    assertThat(candidate(task, target).get("fingerprint"))
        .isNotEqualTo(oldTarget.get("fingerprint"));
    error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_CONFLICT");
    var fresh = command(task, target);
    ((com.fasterxml.jackson.databind.node.ObjectNode) fresh.path("dependencies").get(0))
        .set("fingerprint", oldTarget.get("fingerprint"));
    error(send(task, UUID.randomUUID(), fresh), 409, "TASK_DEPENDENCIES_CANDIDATE_CONFLICT");
  }

  @Test
  void sourceConflictPrecedesBlockersAndDeterministicBlockersDoNotWrite() throws Exception {
    UUID target = node("Target", "DRAFT");
    var body = command(task, target);
    jdbc.update("update tasks set status='READY',dependency_revision=2147483647 where id=?", task);
    jdbc.update("update teams set status='ARCHIVED' where id=?", team);
    jdbc.update(
        "update missions set status='COMPLETED',task_intake_paused=true where id=?", mission);
    var before = state();
    error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_CONFLICT");
    var c = context();
    List<String> reasons = new ArrayList<>();
    c.path("blockers").forEach(n -> reasons.add(n.asText()));
    assertThat(reasons)
        .containsExactly(
            "TASK_NOT_DRAFT",
            "TEAM_ARCHIVED",
            "MISSION_NOT_EDITABLE",
            "INTAKE_PAUSED",
            "REVISION_EXHAUSTED");
    assertThat(c.path("canEdit").asBoolean()).isFalse();
    body.set("expectedRevision", c.get("revision"));
    body.set("expectedFingerprint", c.get("fingerprint"));
    error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_BLOCKED");
    assertThat(state()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "+1", "01", " 1", "1.0", "100001", "999999999999999999999"})
  void strictPageSyntax(String page) throws Exception {
    error(
        mvc.perform(
                get(root(task) + "/eligible-dependencies")
                    .session(session)
                    .with(auth())
                    .queryParam("page", page))
            .andReturn(),
        400,
        "BAD_REQUEST");
  }

  @Test
  void queryAndGetBodyRejectionsAreReadOnly() throws Exception {
    var before = state();
    for (String suffix :
        List.of(
            "", "/eligible-dependencies/" + UUID.randomUUID(), "/receipts/" + UUID.randomUUID())) {
      error(
          mvc.perform(get(root(task) + suffix).session(session).with(auth()).queryParam("x", "1"))
              .andReturn(),
          400,
          "BAD_REQUEST");
      error(
          mvc.perform(get(root(task) + suffix).session(session).with(auth()).content("{}"))
              .andReturn(),
          400,
          "BAD_REQUEST");
      error(
          mvc.perform(
                  get(root(task) + suffix)
                      .session(session)
                      .with(auth())
                      .header("Transfer-Encoding", "chunked")
                      .content("{}"))
              .andReturn(),
          400,
          "BAD_REQUEST");
    }
    for (String key : List.of("page", "query"))
      error(
          mvc.perform(
                  get(root(task) + "/eligible-dependencies")
                      .session(session)
                      .with(auth())
                      .queryParam(key, "0", "1"))
              .andReturn(),
          400,
          "BAD_REQUEST");
    error(
        mvc.perform(
                get(root(task) + "/eligible-dependencies")
                    .session(session)
                    .with(auth())
                    .queryParam("query", "unsafe\ntext"))
            .andReturn(),
        400,
        "BAD_REQUEST");
    error(
        mvc.perform(
                get(root(task) + "/eligible-dependencies")
                    .session(session)
                    .with(auth())
                    .queryParam("query", "a".repeat(101)))
            .andReturn(),
        400,
        "BAD_REQUEST");
    assertThat(state()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "[]",
        "{\"expectedRevision\":0,\"expectedRevision\":0}",
        "{\"expectedRevision\":0} {}"
      })
  void invalidJsonNeverReservesAKey(String body) throws Exception {
    var before = state();
    error(mvc.perform(postRequest(task, UUID.randomUUID(), body)).andReturn(), 400, "BAD_REQUEST");
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void strictCommandTypesDuplicatesUtf8SizeHeadersAndCsrf() throws Exception {
    UUID target = node("Target", "DRAFT");
    var valid = command(task, target);
    var before = state();
    List<String> invalid = new ArrayList<>();
    String text = json.writeValueAsString(valid);
    invalid.add(text.replace("\"expectedRevision\":0", "\"expectedRevision\":0.0"));
    invalid.add(text.replace("\"expectedRevision\":0", "\"expectedRevision\":0e0"));
    invalid.add(text.replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""));
    invalid.add(text.replace("\"expectedRevision\":0", "\"expectedRevision\":null"));
    invalid.add(text.replace("\"dependencies\":[", "\"unknown\":true,\"dependencies\":["));
    invalid.add(text.replace("\"taskId\":", "\"taskId\":\"" + target + "\",\"taskId\":"));
    invalid.add(text.replace(target.toString(), task.toString()));
    var duplicate = valid.deepCopy();
    duplicate.withArray("dependencies").add(valid.path("dependencies").get(0));
    invalid.add(json.writeValueAsString(duplicate));
    invalid.add(text + " ".repeat(16385));
    for (String body : invalid)
      error(
          mvc.perform(postRequest(task, UUID.randomUUID(), body)).andReturn(), 400, "BAD_REQUEST");
    error(
        mvc.perform(
                postRequest(task, UUID.randomUUID(), text)
                    .content(new byte[] {(byte) 0xc3, (byte) 0x28}))
            .andReturn(),
        400,
        "BAD_REQUEST");
    for (String type :
        List.of("text/plain", "application/json;charset=ISO-8859-1", "application/json;extra=x"))
      error(
          mvc.perform(postRequest(task, UUID.randomUUID(), text).contentType(type)).andReturn(),
          400,
          "BAD_REQUEST");
    error(
        mvc.perform(
                postRequest(task, UUID.randomUUID(), text)
                    .with(
                        request -> {
                          request.addHeader("Content-Type", "application/json");
                          assertThat(java.util.Collections.list(request.getHeaders("Content-Type")))
                              .hasSize(2);
                          return request;
                        }))
            .andReturn(),
        400,
        "BAD_REQUEST");
    error(
        mvc.perform(
                postRequest(task, UUID.randomUUID(), text)
                    .header("Idempotency-Key", UUID.randomUUID()))
            .andReturn(),
        400,
        "BAD_REQUEST");
    error(
        mvc.perform(
                post(root(task))
                    .session(session)
                    .with(auth())
                    .contentType("application/json")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .content(text))
            .andReturn(),
        403,
        "FORBIDDEN");
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void authorityPrecedesMalformedWireAndChildScopeIsExact() throws Exception {
    error(mvc.perform(get(root(task))).andReturn(), 401, "UNAUTHORIZED");
    jdbc.update(
        "update organization_memberships set role='MEMBER' where account_id=? and"
            + " organization_id=?",
        actor,
        org);
    error(
        mvc.perform(postRequest(task, UUID.randomUUID(), "{broken").queryParam("bad", "1"))
            .andReturn(),
        403,
        "FORBIDDEN");
    error(
        mvc.perform(
                get(root(task).replace(task.toString(), "bad-child")).session(session).with(auth()))
            .andReturn(),
        403,
        "FORBIDDEN");
    jdbc.update(
        "update organization_memberships set role='ADMIN' where account_id=? and organization_id=?",
        actor,
        org);
    error(
        mvc.perform(
                get(root(task).replace(task.toString(), "bad-child")).session(session).with(auth()))
            .andReturn(),
        400,
        "BAD_REQUEST");
    error(
        mvc.perform(
                get(root(task).replace(team.toString(), UUID.randomUUID().toString()))
                    .session(session)
                    .with(auth()))
            .andReturn(),
        404,
        "NOT_FOUND");
    error(
        mvc.perform(
                get(root(task).replace(org.toString(), UUID.randomUUID().toString()))
                    .session(session)
                    .with(auth())
                    .queryParam("bad", "1"))
            .andReturn(),
        403,
        "FORBIDDEN");
  }

  @Test
  void malformedStoredReceiptAndEventFailSafelyWithoutCurrentMetadataLeak() throws Exception {
    UUID target = node("Private target", "DRAFT");
    UUID key = UUID.randomUUID();
    var body = command(task, target);
    data(send(task, key, body), 201);
    String original =
        jdbc.queryForObject(
            "select original_response from task_dependency_receipts where organization_id=?",
            String.class,
            org);
    jdbc.update(
        "update task_dependency_receipts set original_response=? where organization_id=?",
        "{private invalid",
        org);
    error(
        mvc.perform(get(root(task) + "/receipts/" + key).session(session).with(auth())).andReturn(),
        500,
        "TASK_DEPENDENCIES_UNAVAILABLE");
    error(send(task, key, body), 500, "TASK_DEPENDENCIES_UNAVAILABLE");
    jdbc.update(
        "update task_dependency_receipts set original_response=? where organization_id=?",
        original,
        org);
    jdbc.update("update task_dependency_events set after_json='[]' where organization_id=?", org);
    error(
        mvc.perform(get(root(task) + "/receipts/" + key).session(session).with(auth())).andReturn(),
        500,
        "TASK_DEPENDENCIES_UNAVAILABLE");
  }

  @Test
  void existingEditorsPreserveOldTuplePositionsAndAppendOnlyDependencyRevision() throws Exception {
    for (boolean owner : List.of(false, true)) {
      var tuple = DependencyProtocolOracle.legacyTuple(this, owner);
      String old = DependencyProtocolOracle.hash(this, tuple);
      tuple.set(0, owner ? "TASK_OWNER_SNAPSHOT_V2" : "TASK_DRAFT_EDIT_SNAPSHOT_V3");
      tuple.add(0);
      String expected = DependencyProtocolOracle.hash(this, tuple);
      var context =
          read(root(task).replace("draft-dependencies", owner ? "draft-owner" : "draft-settings"));
      assertThat(context.path("fingerprint").asText()).isEqualTo(expected).isNotEqualTo(old);
    }
  }

  @Test
  void dependencyAbaWithIdenticalTimestampsInvalidatesBothOtherEditors() throws Exception {
    UUID target = node("Target", "DRAFT");
    var editBefore = read(root(task).replace("draft-dependencies", "draft-settings"));
    var ownerBefore = read(root(task).replace("draft-dependencies", "draft-owner"));
    var timestamp = sourceRow().get("updated_at");
    data(send(task, UUID.randomUUID(), command(task, target)), 201);
    data(send(task, UUID.randomUUID(), command(task)), 201);
    jdbc.update("update tasks set updated_at=? where id=?", timestamp, task);
    assertThat(context().path("revision").asInt()).isEqualTo(2);
    assertThat(read(root(task).replace("draft-dependencies", "draft-settings")).get("fingerprint"))
        .isNotEqualTo(editBefore.get("fingerprint"));
    assertThat(read(root(task).replace("draft-dependencies", "draft-owner")).get("fingerprint"))
        .isNotEqualTo(ownerBefore.get("fingerprint"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"INPUT", "OWNER"})
  void reverseHttpAbaWithIdenticalTaskValuesInvalidatesDependencyTokens(String editor)
      throws Exception {
    boolean owner = editor.equals("OWNER");
    if (!owner)
      jdbc.update(
          "update tasks set description='Description',acceptance_criteria='Criteria' where id=?",
          task);
    jdbc.update("update tasks set priority='NORMAL' where id=?", task);
    UUID target = node("Target", "DRAFT"), agent = null;
    if (owner) {
      agent = UUID.randomUUID();
      jdbc.update(
          "insert into"
              + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
              + " values (?,?,?,'ABA"
              + " owner','Role','MID','DRAFT','SUPERVISED',current_timestamp,current_timestamp)",
          agent,
          org,
          team);
    }
    var before = sourceRow();
    var pending = command(task, target);
    String path =
        root(task).replace("draft-dependencies", owner ? "draft-owner" : "draft-settings");
    for (int i = 0; i < 2; i++) {
      var context = read(path);
      var body = json.createObjectNode();
      body.set("expectedRevision", context.get("revision"));
      body.set("expectedFingerprint", context.get("fingerprint"));
      if (owner) {
        if (i == 0) {
          body.put("ownerAgentId", agent.toString());
          body.set("ownerFingerprint", read(path + "/eligible-owners/" + agent).get("fingerprint"));
        } else {
          body.putNull("ownerAgentId");
          body.putNull("ownerFingerprint");
        }
      } else {
        body.put("title", i == 0 ? "Changed" : "Source");
        body.put("description", "Description");
        body.put("acceptanceCriteria", "Criteria");
        body.put("priority", "NORMAL");
      }
      data(
          mvc.perform(postAt(path, UUID.randomUUID(), json.writeValueAsString(body))).andReturn(),
          201);
    }
    jdbc.update("update tasks set updated_at=? where id=?", before.get("updated_at"), task);
    String revision = owner ? "task_owner_revision" : "task_edit_revision";
    var after = sourceRow();
    assertThat(after.remove(revision)).isEqualTo(2);
    before.remove(revision);
    assertThat(after).isEqualTo(before);
    error(send(task, UUID.randomUUID(), pending), 409, "TASK_DEPENDENCIES_CONFLICT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"OWNER", "INPUT"})
  void preUpgradeReceiptsStillReplayTheirOriginalCommandDigest(String editor) throws Exception {
    boolean owner = editor.equals("OWNER");
    String path =
        root(task).replace("draft-dependencies", owner ? "draft-owner" : "draft-settings");
    String oldFingerprint =
        DependencyProtocolOracle.hash(this, DependencyProtocolOracle.legacyTuple(this, owner));
    UUID key = UUID.randomUUID(), event = UUID.randomUUID();
    String instant = "2026-01-02T03:04:05Z";
    var body = json.createObjectNode();
    body.put("expectedRevision", 0);
    body.put("expectedFingerprint", oldFingerprint);
    com.fasterxml.jackson.databind.node.ObjectNode before, after;
    UUID agent = null;
    if (owner) {
      agent = UUID.randomUUID();
      jdbc.update(
          "insert into"
              + " agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)"
              + " values (?,?,?,'Historical"
              + " owner','Role','MID','DRAFT','SUPERVISED',current_timestamp,current_timestamp)",
          agent,
          org,
          team);
      body.put("ownerAgentId", agent.toString());
      body.put("ownerFingerprint", "c".repeat(64));
      before = json.createObjectNode();
      before.put("state", "UNASSIGNED");
      before.putNull("agent");
      after = json.createObjectNode();
      after.put("state", "ASSIGNED");
      var a = after.putObject("agent");
      a.put("id", agent.toString());
      a.put("name", "Historical owner");
      a.put("level", "MID");
      a.put("status", "DRAFT");
    } else {
      body.put("title", "Historical title");
      body.put("description", "Description");
      body.put("acceptanceCriteria", "Criteria");
      body.put("priority", "NORMAL");
      before = json.createObjectNode();
      before.put("title", "Source");
      before.putNull("description");
      before.putNull("acceptanceCriteria");
      before.putNull("priority");
      after = before.deepCopy();
      after.put("title", "Historical title");
      after.put("description", "Description");
      after.put("acceptanceCriteria", "Criteria");
      after.put("priority", "NORMAL");
    }
    var receipt = json.createObjectNode();
    receipt.put("organizationId", org.toString());
    receipt.put("teamId", team.toString());
    receipt.put("missionId", mission.toString());
    receipt.put("taskId", task.toString());
    receipt.put("eventId", event.toString());
    receipt.put("revision", 1);
    receipt.put("committedAt", instant);
    receipt.set("before", before);
    receipt.set("after", after);
    String table = owner ? "task_owner" : "task_edit";
    String digest =
        DependencyProtocolOracle.hash(
            this,
            List.of(
                owner ? "TASK_OWNER_COMMAND_V1" : "TASK_DRAFT_EDIT_COMMAND_V1",
                DependencyProtocolOracle.scope(this),
                actor,
                body));
    if (owner)
      jdbc.update(
          "insert into"
              + " task_owner_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_owner_agent_id,after_owner_agent_id,before_json,after_json)"
              + " values (?,?,?,?,?,1,?,?,null,?,?,?)",
          event,
          org,
          team,
          mission,
          task,
          actor,
          java.time.OffsetDateTime.parse(instant),
          agent,
          json.writeValueAsString(before),
          json.writeValueAsString(after));
    else
      jdbc.update(
          "insert into"
              + " task_edit_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)"
              + " values (?,?,?,?,?,1,?,?,?,?)",
          event,
          org,
          team,
          mission,
          task,
          actor,
          java.time.OffsetDateTime.parse(instant),
          json.writeValueAsString(before),
          json.writeValueAsString(after));
    String extra = owner ? ",created_at" : "";
    String placeholder = owner ? ",?" : "";
    List<Object> args =
        new ArrayList<>(
            List.of(
                org,
                team,
                mission,
                task,
                actor,
                key,
                event,
                digest,
                json.writeValueAsString(receipt)));
    if (owner) args.add(java.time.OffsetDateTime.parse(instant));
    jdbc.update(
        "insert into "
            + table
            + "_receipts(organization_id,team_id,mission_id,task_id,actor_id,idempotency_key,event_id,body_sha256,original_response"
            + extra
            + ") values (?,?,?,?,?,?,?,?,?"
            + placeholder
            + ")",
        args.toArray());
    try {
      // Current source has neither this revision nor these historical values; recovery must use
      // bytes.
      assertThat(
              data(mvc.perform(postAt(path, key, json.writeValueAsString(body))).andReturn(), 200))
          .isEqualTo(receipt);
      assertThat(read(path + "/receipts/" + key).get("receipt")).isEqualTo(receipt);
      error(
          mvc.perform(postAt(path, UUID.randomUUID(), json.writeValueAsString(body))).andReturn(),
          409,
          owner ? "TASK_OWNER_CONFLICT" : "TASK_EDIT_CONFLICT");
    } finally {
      if (owner) {
        jdbc.update("delete from task_owner_receipts where organization_id=?", org);
        jdbc.update("delete from task_owner_events where organization_id=?", org);
        jdbc.update("delete from agents where id=?", agent);
      }
    }
  }

  @Test
  void fingerprintsAndPersistedCommandDigestMatchIndependentFrozenTupleOracle() throws Exception {
    UUID z = node("𐀀", "DRAFT"),
        a = node("\"quoted\" \\ text é", "DRAFT"),
        off = node("Off path", "COMPLETED");
    edge(off, a);
    assertThat(context().path("fingerprint").asText())
        .isEqualTo(DependencyProtocolOracle.source(this));
    assertThat(candidate(task, z).path("fingerprint").asText())
        .isEqualTo(DependencyProtocolOracle.target(this, z));
    var body = command(task, z, a);
    UUID key = UUID.randomUUID();
    List<List<String>> desired = new ArrayList<>();
    body.path("dependencies")
        .forEach(
            n -> desired.add(List.of(n.path("taskId").asText(), n.path("fingerprint").asText())));
    desired.sort(java.util.Comparator.comparing(n -> n.get(0)));
    String expected =
        DependencyProtocolOracle.hash(
            this,
            List.of(
                "TASK_DEPENDENCIES_COMMAND_V1",
                List.of(org.toString(), team.toString(), mission.toString(), task.toString()),
                actor.toString(),
                body.path("expectedRevision").asInt(),
                body.path("expectedFingerprint").asText(),
                desired));
    data(send(task, key, body), 201);
    assertThat(
            jdbc.queryForObject(
                "select body_sha256 from task_dependency_receipts where organization_id=? and"
                    + " idempotency_key=?",
                String.class,
                org,
                key))
        .isEqualTo(expected);
  }

  @Test
  void unknownSafeStatusBlocksWithoutPretendingToBeKnownOrRepairing() throws Exception {
    UUID target = node("Known target", "DRAFT");
    edge(task, target);
    // Team status is stored as text; unlike Task status, future values are legal.
    jdbc.update("update teams set status='FUTURE_STATE' where id=?", team);
    var before = state();
    var c = context();
    assertThat(c.path("canEdit").asBoolean()).isFalse();
    assertThat(c.path("blockers").toString()).isEqualTo("[\"UNKNOWN_STATE\"]");
    assertThat(c.path("dependencies").get(0).path("status").asText()).isEqualTo("DRAFT");
    error(
        mvc.perform(get(root(task) + "/eligible-dependencies").session(session).with(auth()))
            .andReturn(),
        409,
        "TASK_DEPENDENCIES_BLOCKED");
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void exhaustedUnrelatedRevisionsDoNotBlockDependencyCommand() throws Exception {
    UUID target = node("Target", "DRAFT");
    jdbc.update(
        "update tasks set task_edit_revision=2147483647,task_owner_revision=2147483647 where id=?",
        task);
    jdbc.update("update missions set task_intake_version=9223372036854775807 where id=?", mission);
    assertThat(context().path("canEdit").asBoolean()).isTrue();
    data(send(task, UUID.randomUUID(), command(task, target)), 201);
  }

  @Test
  void legalWrongTeamAndMissionTargetsAreIndistinguishableFromMissing() throws Exception {
    UUID otherTeam = UUID.randomUUID(),
        otherMission = UUID.randomUUID(),
        sameTeamMission = UUID.randomUUID();
    jdbc.update(
        "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
            + " (?,?,'Private team','DRAFT',current_timestamp,current_timestamp)",
        otherTeam,
        org);
    for (UUID m : List.of(otherMission, sameTeamMission))
      jdbc.update(
          "insert into"
              + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
              + " values (?,?,?,'Private"
              + " mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
          m,
          org,
          m.equals(otherMission) ? otherTeam : team);
    var source = context();
    for (UUID m : List.of(otherMission, sameTeamMission)) {
      UUID target = UUID.randomUUID();
      jdbc.update(
          "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
              + " (?,?,?,?,'Private target','DRAFT')",
          target,
          org,
          m.equals(otherMission) ? otherTeam : team,
          m);
      var response =
          mvc.perform(
                  get(root(task) + "/eligible-dependencies/" + target)
                      .session(session)
                      .with(auth()))
              .andReturn();
      error(response, 404, "NOT_FOUND");
      assertThat(response.getResponse().getContentAsString())
          .doesNotContain("Private", target.toString());
      var body = json.createObjectNode();
      body.set("expectedRevision", source.get("revision"));
      body.set("expectedFingerprint", source.get("fingerprint"));
      body.putArray("dependencies")
          .addObject()
          .put("taskId", target.toString())
          .put("fingerprint", "a".repeat(64));
      error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_NOT_ELIGIBLE");
    }
  }

  @Test
  void oldCreateReceiptUsesHistoricalUuidNaturalOrderAfterLockOrderChanges() throws Exception {
    UUID
        high =
            new UUID(
                (UUID.randomUUID().getMostSignificantBits() & 0x0fffffffffffffffL)
                    | 0xf000000000000000L,
                UUID.randomUUID().getLeastSignificantBits()),
        low =
            new UUID(
                (UUID.randomUUID().getMostSignificantBits() & 0x0fffffffffffffffL)
                    | 0x1000000000000000L,
                UUID.randomUUID().getLeastSignificantBits());
    // These IDs distinguish Java UUID natural ordering from canonical text ordering.
    for (UUID id : List.of(high, low))
      jdbc.update(
          "insert into tasks(id,organization_id,team_id,mission_id,title,status) values"
              + " (?,?,?,?,'Historical dependency','DRAFT')",
          id,
          org,
          team,
          mission);
    String collection = root(task).substring(0, root(task).indexOf("/" + task));
    var body = json.createObjectNode();
    body.put("title", "Historical created task");
    body.put("description", "Description");
    body.put("acceptanceCriteria", "Criteria");
    body.put("priority", "NORMAL");
    body.putNull("ownerAgentId");
    body.putArray("dependencyIds").add(low.toString()).add(high.toString());
    body.put("expectedIntakeVersion", 0);
    var canonical = body.deepCopy();
    canonical.putArray("dependencyIds").add(high.toString()).add(low.toString());
    String digest =
        DependencyProtocolOracle.hash(
            this, List.of(org.toString(), team.toString(), mission.toString(), canonical));
    var receipt = read(root(task).replace("/draft-dependencies", ""));
    UUID key = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " task_create_receipts(organization_id,account_id,idempotency_key,body_sha256,original_response)"
            + " values (?,?,?,?,?)",
        org,
        actor,
        key,
        digest,
        json.writeValueAsString(receipt));
    jdbc.update("update missions set task_intake_paused=true where id=?", mission);
    var before = state();
    assertThat(
            data(
                mvc.perform(postAt(collection, key, json.writeValueAsString(body))).andReturn(),
                200))
        .isEqualTo(receipt);
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void encodedDuplicatesAndMalformedRawQueryEncodingAreRejected() throws Exception {
    String path = root(task) + "/eligible-dependencies";
    var before = state();
    error(
        mvc.perform(
                get(path)
                    .session(session)
                    .with(auth())
                    .with(
                        r -> {
                          r.setQueryString("page=0&%70age=1");
                          r.addParameter("page", "0", "1");
                          return r;
                        }))
            .andReturn(),
        400,
        "BAD_REQUEST");
    for (String raw : List.of("query=%C3%28", "query=%FF", "query=%", "query=%ED%A0%80")) {
      // MockMvc does not run a servlet container's URI decoder. Supply its raw URI and
      // replacement-decoded parameter to ensure the HTTP adapter checks raw UTF8 itself.
      error(
          mvc.perform(
                  get(path)
                      .session(session)
                      .with(auth())
                      .with(
                          r -> {
                            r.setQueryString(raw);
                            r.addParameter("query", "�");
                            return r;
                          }))
              .andReturn(),
          400,
          "BAD_REQUEST");
    }
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void foreignGraphIsNotReadOrDisclosedAndForeignTargetsBehaveAsMissing() throws Exception {
    UUID foreignOrg = UUID.randomUUID(),
        foreignTeam = UUID.randomUUID(),
        foreignMission = UUID.randomUUID(),
        target = UUID.randomUUID();
    try {
      jdbc.update(
          "insert into organizations(id,name,slug,status,created_at,updated_at) values (?,'Private"
              + " org',?,'ACTIVE',current_timestamp,current_timestamp)",
          foreignOrg,
          foreignOrg.toString());
      jdbc.update(
          "insert into teams(id,organization_id,name,status,created_at,updated_at) values"
              + " (?,?,'Private team','DRAFT',current_timestamp,current_timestamp)",
          foreignTeam,
          foreignOrg);
      jdbc.update(
          "insert into"
              + " missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)"
              + " values (?,?,?,'Private"
              + " mission','Goal','CREATED','NORMAL',current_timestamp,current_timestamp)",
          foreignMission,
          foreignOrg,
          foreignTeam);
      jdbc.update(
          "insert into tasks(id,organization_id,team_id,mission_id,title,status) values (?,?,?,"
              + " ?,?,'DRAFT')",
          target,
          foreignOrg,
          foreignTeam,
          foreignMission,
          "private\nunsafe foreign title");
      var source = context();
      assertThat(source.path("canEdit").asBoolean()).isTrue();
      assertThat(read(root(task) + "/eligible-dependencies").path("total").asInt()).isZero();
      var response =
          mvc.perform(
                  get(root(task) + "/eligible-dependencies/" + target)
                      .session(session)
                      .with(auth()))
              .andReturn();
      error(response, 404, "NOT_FOUND");
      assertThat(response.getResponse().getContentAsString())
          .doesNotContain(target.toString(), "private", "Private");
      var body = json.createObjectNode();
      body.set("expectedRevision", source.get("revision"));
      body.set("expectedFingerprint", source.get("fingerprint"));
      body.putArray("dependencies")
          .addObject()
          .put("taskId", target.toString())
          .put("fingerprint", "a".repeat(64));
      error(send(task, UUID.randomUUID(), body), 409, "TASK_DEPENDENCIES_NOT_ELIGIBLE");
    } finally {
      for (String table : List.of("tasks", "missions", "teams"))
        jdbc.update("delete from " + table + " where organization_id=?", foreignOrg);
      jdbc.update("delete from organizations where id=?", foreignOrg);
    }
  }

  @Test
  void legitimatePrivateMultilineInputsOnSourceTargetAndDisconnectedNodeDoNotPoisonGraphOrCreate()
      throws Exception {
    UUID target = node("Target", "WAITING"), disconnected = node("Disconnected", "BLOCKED");
    List<UUID> existing = List.of(task, target, disconnected);
    List<java.util.Map<String, Object>> original = new ArrayList<>();
    List<String> secrets = new ArrayList<>();
    for (UUID id : existing) {
      String description = "Private description " + id + "\tcolumn\r\nnext line";
      String criteria = "Private criteria " + id + "\rline\nnext\tcolumn";
      String pending = "Private pending " + id + "\tcolumn\r\nreason";
      secrets.addAll(List.of(description, criteria, pending));
      jdbc.update(
          "update tasks set description=?,acceptance_criteria=?,pending_reason=? where id=?",
          description,
          criteria,
          id.equals(task) ? null : pending,
          id);
      original.add(jdbc.queryForMap("select * from tasks where id=?", id));
    }
    // The selected target remains a public Task3; off-path private input is still valid graph data.
    edge(task, target);
    for (String path :
        List.of(
            root(task),
            root(task) + "/eligible-dependencies",
            root(task) + "/eligible-dependencies/" + target)) {
      JsonNode result = read(path);
      String encoded = json.writeValueAsString(result);
      assertThat(encoded)
          .doesNotContain(
              "Private description",
              "Private criteria",
              "Private pending",
              "acceptanceCriteria",
              "pendingReason");
      for (String secret : secrets) assertThat(encoded).doesNotContain(secret);
    }
    assertThat(context().path("dependencies").get(0).path("id").asText())
        .isEqualTo(target.toString());
    String collection = root(task).substring(0, root(task).indexOf("/" + task));
    var body = json.createObjectNode();
    body.put("title", "Fresh task with valid existing graph");
    body.put("description", "New description");
    body.put("acceptanceCriteria", "New criteria");
    body.put("priority", "NORMAL");
    body.putNull("ownerAgentId");
    body.putArray("dependencyIds").add(target.toString());
    body.put("expectedIntakeVersion", 0);
    JsonNode created =
        data(
            mvc.perform(postAt(collection, UUID.randomUUID(), json.writeValueAsString(body)))
                .andReturn(),
            201);
    assertThat(json.writeValueAsString(created))
        .doesNotContain("Private description", "Private criteria", "Private pending");
    for (int i = 0; i < existing.size(); i++)
      assertThat(jdbc.queryForMap("select * from tasks where id=?", existing.get(i)))
          .isEqualTo(original.get(i));
    assertThat(count("task_dependency_events")).isZero();
    assertThat(count("task_dependency_receipts")).isZero();
  }
}

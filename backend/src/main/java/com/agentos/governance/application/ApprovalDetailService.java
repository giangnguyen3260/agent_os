package com.agentos.governance.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.ApprovalCenterResponse.Item;
import com.agentos.governance.api.ApprovalDetailResponse;
import com.agentos.governance.api.ApprovalDetailResponse.Command;
import com.agentos.governance.api.ApprovalDetailResponse.Decision;
import com.agentos.governance.api.ApprovalDetailResponse.Eligibility;
import com.agentos.governance.api.ApprovalDetailResponse.PreviewParameters;
import com.agentos.governance.api.ApprovalDetailResponse.Result;
import com.agentos.governance.api.ApprovalDetailResponse.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApprovalDetailService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public ApprovalDetailService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private void authorize(UUID org, HumanIdentity human, boolean lock) {
    if (human == null) throw new AccessDeniedException("Request denied");
    // Match existing create services: serialize organization writes before actor locks.
    // This deliberately serializes decisions (including replay) within an organization.
    if (lock
        && jdbc.queryForList("select id from organizations where id=? for update", org).size() != 1)
      throw new AccessDeniedException("Request denied");
    var accounts =
        jdbc.queryForList(
            "select id from human_accounts where id=? and enabled=true and credential_version=?"
                + (lock ? " for update" : ""),
            human.id(),
            human.credentialVersion());
    var members =
        jdbc.queryForList(
            "select account_id from organization_memberships where organization_id=? and"
                + " account_id=? and role in ('ADMIN','MEMBER')"
                + (lock ? " for update" : ""),
            org,
            human.id());
    if (accounts.size() != 1 || members.size() != 1)
      throw new AccessDeniedException("Request denied");
  }

  private Map<String, Object> request(UUID org, UUID id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select * from approval_requests where organization_id=? and id=?"
                + (lock ? " for update" : ""),
            org,
            id);
    if (rows.isEmpty()) throw new EntityNotFoundException("Approval not found");
    return rows.getFirst();
  }

  private Map<String, Object> snapshot(UUID id) {
    return snapshot(id, false);
  }

  private Map<String, Object> snapshot(UUID id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select * from approval_snapshots where approval_id=?" + (lock ? " for update" : ""),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static UUID uuid(Map<String, Object> row, String key) {
    return (UUID) row.get(key);
  }

  private static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), null);
  }

  private static Instant time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value == null) return null;
    if (value instanceof OffsetDateTime t) return t.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    return Instant.parse(value.toString());
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<Map<String, Object>> grants(
      UUID org, HumanIdentity human, Map<String, Object> r, boolean lock) {
    return jdbc.queryForList(
        "select * from approval_grants where organization_id=? and account_id=? and team_id=? and"
            + " action_type=? and risk_level=? order by id"
            + (lock ? " for update" : ""),
        org,
        human.id(),
        r.get("team_id"),
        r.get("action_type"),
        r.get("risk_level"));
  }

  private boolean granted(List<Map<String, Object>> rows, Instant now) {
    return rows.stream()
        .anyMatch(g -> g.get("revoked_at") == null && time(g, "expires_at").isAfter(now));
  }

  // Sealed v1 supports the bounded PUBLISH_PREVIEW action only. Its complete safe parameters
  // are explicit; prose alone is never an action binding. New action schemas require review.
  // There is deliberately no public producer and no provider/credential payload field.
  // Historical display checks the seal independently of mutable current-head authority.
  private Snapshot verified(Map<String, Object> r, Map<String, Object> s) {
    if (s == null) return null;
    try {
      String raw = str(s, "canonical_payload"), digest = sha256(raw);
      var node = json.readTree(raw);
      TreeMap<String, Object> expected = new TreeMap<>();
      for (String field :
          List.of("target", "scope", "effectSummary", "estimatedCost", "currency")) {
        var v = node.get(field);
        if (v == null || (!v.isNull() && !v.isTextual())) return null;
        String text = v.isNull() ? null : v.textValue();
        int limit =
            switch (field) {
              case "target" -> 500;
              case "scope" -> 200;
              case "effectSummary" -> 2000;
              default -> 32;
            };
        if (text != null
            && (text.length() > limit || text.codePoints().anyMatch(Character::isISOControl)))
          return null;
        if (List.of("target", "scope", "effectSummary").contains(field)
            && (text == null || text.isBlank())) return null;
        expected.put(field, text);
      }
      String cost = (String) expected.get("estimatedCost");
      String currency = (String) expected.get("currency");
      if (cost == null
          ? currency != null
          : !cost.matches("[0-9]{1,9}(\\.[0-9]{1,4})?") || !"USD".equals(currency)) return null;
      if (!"PUBLISH_PREVIEW".equals(r.get("action_type"))) return null;
      var parameters = node.get("parameters");
      if (parameters == null || !parameters.isObject() || parameters.size() != 3) return null;
      TreeMap<String, String> safeParameters = new TreeMap<>();
      for (String key : List.of("destination", "resourcePath", "visibility")) {
        var value = parameters.get(key);
        if (value == null || !value.isTextual()) return null;
        safeParameters.put(key, value.textValue());
      }
      if (!"internal-review".equals(safeParameters.get("destination"))
          || !"TEAM".equals(safeParameters.get("visibility"))
          || !safeParameters.get("resourcePath").matches("/[a-zA-Z0-9/_-]{1,200}")) return null;
      expected.put("parameters", safeParameters);
      for (String field :
          List.of(
              "id",
              "organization_id",
              "team_id",
              "mission_id",
              "task_id",
              "requested_by_agent_id",
              "requester_human_id",
              "action_type",
              "risk_level")) expected.put(field, str(r, field));
      expected.put("actionId", str(s, "action_id"));
      expected.put("version", ((Number) s.get("version")).longValue());
      expected.put("headRevision", ((Number) s.get("head_revision")).longValue());
      expected.put("expiresAt", time(s, "expires_at").toString());
      if (!json.writeValueAsString(expected).equals(raw) || !digest.equals(s.get("payload_sha256")))
        return null;
      return new Snapshot(
          uuid(s, "action_id"),
          ((Number) s.get("version")).longValue(),
          digest,
          str(r, "action_type"),
          new PreviewParameters(
              safeParameters.get("destination"),
              safeParameters.get("resourcePath"),
              safeParameters.get("visibility")),
          (String) expected.get("target"),
          (String) expected.get("scope"),
          (String) expected.get("effectSummary"),
          (String) expected.get("estimatedCost"),
          (String) expected.get("currency"),
          time(s, "expires_at"));
    } catch (Exception ex) {
      return null;
    }
  }

  private boolean currentHeadMatches(
      Map<String, Object> r, Map<String, Object> s, Map<String, Object> h) {
    return s != null
        && h != null
        && Objects.equals(s.get("action_id"), h.get("action_id"))
        && Objects.equals(s.get("payload_sha256"), h.get("payload_sha256"))
        && Objects.equals(r.get("organization_id"), h.get("organization_id"))
        && Objects.equals(r.get("team_id"), h.get("team_id"))
        && ((Number) s.get("head_revision")).longValue()
            == ((Number) h.get("revision")).longValue();
  }

  private Map<String, Object> head(Map<String, Object> s, boolean lock) {
    if (s == null) return null;
    var rows =
        jdbc.queryForList(
            "select * from approval_action_heads where action_id=?" + (lock ? " for update" : ""),
            s.get("action_id"));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<String> blockers(
      Map<String, Object> r,
      Map<String, Object> s,
      Map<String, Object> h,
      Snapshot v,
      HumanIdentity human,
      boolean grant,
      Instant now,
      boolean lock) {
    List<String> b = new ArrayList<>();
    if (!"PENDING".equals(r.get("status")) || decision(uuid(r, "id")) != null) b.add("NOT_PENDING");
    if (r.get("requester_human_id") == null) b.add("MISSING_REQUESTER");
    else if (human.id().equals(r.get("requester_human_id"))) b.add("SELF_DECISION");
    if (r.get("requester_human_id") != null
        && jdbc.queryForList(
                    "select account_id from organization_memberships where organization_id=? and"
                        + " account_id=? and role in ('ADMIN','MEMBER')"
                        + (lock ? " for update" : ""),
                    r.get("organization_id"),
                    r.get("requester_human_id"))
                .size()
            != 1) b.add("REQUESTER_OUT_OF_SCOPE");
    if (!grant) b.add("NO_SCOPED_GRANT");
    if (v == null) b.add(s == null ? "LEGACY_UNSEALED" : "INVALID_SNAPSHOT");
    if (s != null && s.get("invalidated_at") != null) b.add("INVALIDATED");
    if (s != null && !time(s, "expires_at").isAfter(now)) b.add("EXPIRED");
    if (h == null || !Boolean.TRUE.equals(h.get("active"))) b.add("INACTIVE_ACTION");
    if (s != null && !currentHeadMatches(r, s, h)) b.add("CURRENT_HEAD_CHANGED");
    for (String table : List.of("organizations", "teams", "missions", "tasks", "agents")) {
      String key =
          switch (table) {
            case "organizations" -> "organization_id";
            case "teams" -> "team_id";
            case "missions" -> "mission_id";
            case "tasks" -> "task_id";
            default -> "requested_by_agent_id";
          };
      if (r.get(key) == null) continue;
      var rows =
          jdbc.queryForList(
              "select * from " + table + " where id=?" + (lock ? " for update" : ""), r.get(key));
      if (rows.isEmpty()) {
        b.add("INACTIVE_PARENT");
        continue;
      }
      var p = rows.getFirst();
      if ((List.of("organizations", "teams").contains(table) && !"ACTIVE".equals(str(p, "status")))
          || List.of(
                  "ARCHIVED",
                  "INACTIVE",
                  "DELETED",
                  "CANCELLED",
                  "DISABLED",
                  "COMPLETED",
                  "FAILED",
                  "RETIRED",
                  "SUSPENDED",
                  "PAUSED")
              .contains(str(p, "status"))) b.add("INACTIVE_PARENT");
      if (table.equals("agents") && !Objects.equals(p.get("team_id"), r.get("team_id")))
        b.add("REQUESTER_MOVED");
    }
    return b.stream().distinct().toList();
  }

  private Decision decision(UUID id) {
    var rows = jdbc.queryForList("select * from approval_decisions where approval_id=?", id);
    if (rows.isEmpty()) return null;
    var d = rows.getFirst();
    return new Decision(
        uuid(d, "id"),
        id,
        str(d, "status"),
        uuid(d, "actor_id"),
        str(d, "actor_name"),
        str(d, "reason"),
        ((Number) d.get("snapshot_version")).longValue(),
        str(d, "snapshot_sha256"),
        time(d, "decided_at"));
  }

  private Item item(Map<String, Object> r) {
    UUID org = uuid(r, "organization_id"), team = uuid(r, "team_id");
    String teamName =
        jdbc.queryForObject(
            "select name from teams where organization_id=? and id=?", String.class, org, team);
    String missionTitle = null, taskTitle = null, agentName = null;
    UUID agent = null;
    if (r.get("mission_id") != null)
      missionTitle =
          jdbc.queryForObject(
              "select title from missions where organization_id=? and team_id=? and id=?",
              String.class,
              org,
              team,
              r.get("mission_id"));
    if (r.get("task_id") != null)
      taskTitle =
          jdbc.queryForObject(
              "select title from tasks where organization_id=? and team_id=? and mission_id=? and"
                  + " id=?",
              String.class,
              org,
              team,
              r.get("mission_id"),
              r.get("task_id"));
    var agents =
        jdbc.queryForList(
            "select id,name from agents where organization_id=? and team_id=? and id=?",
            org,
            team,
            r.get("requested_by_agent_id"));
    if (!agents.isEmpty()) {
      agent = uuid(agents.getFirst(), "id");
      agentName = str(agents.getFirst(), "name");
    }
    return new Item(
        uuid(r, "id"),
        org,
        team,
        teamName,
        uuid(r, "mission_id"),
        missionTitle,
        uuid(r, "task_id"),
        taskTitle,
        agent,
        agentName,
        str(r, "title"),
        str(r, "action_type"),
        str(r, "risk_level"),
        str(r, "status"),
        time(r, "created_at"),
        time(r, "updated_at"));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ApprovalDetailResponse detail(UUID org, UUID id, HumanIdentity human) {
    authorize(org, human, false);
    var r = request(org, id, false);
    var s = snapshot(id);
    var h = head(s, false);
    var v = verified(r, s);
    Instant now = Instant.now();
    var b = blockers(r, s, h, v, human, granted(grants(org, human, r, false), now), now, false);
    return new ApprovalDetailResponse(
        now, item(r), v, new Eligibility(b.isEmpty(), b.isEmpty(), b), decision(id), false);
  }

  @Transactional
  public Result decide(
      UUID org, UUID id, HumanIdentity human, UUID key, boolean approve, Command body) {
    if (key == null
        || body == null
        || body.snapshotVersion() == null
        || body.snapshotVersion() < 1
        || body.snapshotSha256() == null
        || !body.snapshotSha256().matches("[a-f0-9]{64}")
        || body.acknowledgement() == null
        || body.reason() != null && body.reason().length() > 2000)
      throw new IllegalArgumentException("Invalid decision");
    String reason = body.reason() == null ? null : body.reason().strip();
    if (approve && !body.acknowledgement() || !approve && (reason == null || reason.isBlank()))
      throw new IllegalArgumentException("Acknowledgement or reason required");
    authorize(org, human, true);
    String fingerprint;
    try {
      fingerprint =
          sha256(
              json.writeValueAsString(
                  Arrays.asList(
                      id.toString(),
                      approve,
                      body.snapshotVersion(),
                      body.snapshotSha256(),
                      body.acknowledgement(),
                      reason)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
    var receipts =
        jdbc.queryForList(
            "select * from approval_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            org,
            human.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if (!granted(grants(org, human, receipt, true), Instant.now()))
        throw new AccessDeniedException("Scoped grant required");
      if (!fingerprint.equals(receipt.get("fingerprint")))
        throw new ResponseStatusException(HttpStatus.CONFLICT);
      return new Result(decision(id), true);
    }
    // Account lock serializes all actor/org key uses, including keys targeting different approvals.
    request(org, id, false);
    var initialSnapshot = snapshot(id);
    var h = head(initialSnapshot, true);
    var r = request(org, id, true);
    var s = snapshot(id, true);
    if (!Objects.equals(initialSnapshot, s)) throw new ResponseStatusException(HttpStatus.CONFLICT);
    var gs = grants(org, human, r, true);
    if (!granted(gs, Instant.now())) throw new AccessDeniedException("Scoped grant required");
    var v = verified(r, s);
    var b = blockers(r, s, h, v, human, true, Instant.now(), true);
    // Recheck wall clock only after every parent and grant lock has been acquired.
    if (!granted(gs, Instant.now())) throw new AccessDeniedException("Scoped grant required");
    if (b.contains("SELF_DECISION")
        || b.contains("MISSING_REQUESTER")
        || b.contains("REQUESTER_OUT_OF_SCOPE")) throw new AccessDeniedException("Request denied");
    if (!b.isEmpty()
        || !time(s, "expires_at").isAfter(Instant.now())
        || v.version() != body.snapshotVersion()
        || !v.payloadSha256().equals(body.snapshotSha256()))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    Instant now = Instant.now();
    UUID decisionId = UUID.randomUUID();
    String status = approve ? "APPROVED" : "REJECTED";
    String actorName =
        jdbc.queryForObject(
            "select display_name from human_accounts where id=?", String.class, human.id());
    jdbc.update(
        "insert into"
            + " approval_decisions(id,approval_id,status,actor_id,actor_name,reason,snapshot_version,snapshot_sha256,decided_at)"
            + " values (?,?,?,?,?,?,?,?,?)",
        decisionId,
        id,
        status,
        human.id(),
        actorName,
        reason,
        v.version(),
        v.payloadSha256(),
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    jdbc.update(
        "update approval_requests set status=?,updated_at=? where id=?",
        status,
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
        id);
    jdbc.update(
        "insert into approval_receipts values (?,?,?,?,?,?,?,?)",
        org,
        human.id(),
        key,
        fingerprint,
        decisionId,
        r.get("team_id"),
        r.get("action_type"),
        r.get("risk_level"));
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'HUMAN',?,'APPROVAL_DECIDED',?,?)",
        UUID.randomUUID(),
        org,
        human.id(),
        "{\"approvalId\":\""
            + id
            + "\",\"decisionId\":\""
            + decisionId
            + "\",\"status\":\""
            + status
            + "\"}",
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    return new Result(decision(id), false);
  }
}

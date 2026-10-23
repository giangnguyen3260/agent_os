package com.agentos.governance.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.ApprovalInformationResponse;
import com.agentos.governance.api.ApprovalInformationResponse.Command;
import com.agentos.governance.api.ApprovalInformationResponse.Entry;
import com.agentos.governance.api.ApprovalInformationResponse.Mutation;
import com.agentos.governance.api.ApprovalInformationResponse.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Information is supplemental plain text, never executable action parameters. */
@Service
public class ApprovalInformationService {
  private final ApprovalDetailService approvals;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public ApprovalInformationService(
      ApprovalDetailService approvals, JdbcTemplate jdbc, ObjectMapper json) {
    this.approvals = approvals;
    this.jdbc = jdbc;
    this.json = json;
  }

  private long revision(Map<String, Object> r) {
    return ((Number) r.get("information_revision")).longValue();
  }

  private List<Map<String, Object>> rows(UUID id) {
    var rows =
        jdbc.queryForList(
            "select * from approval_information_entries where approval_id=? order by revision limit"
                + " 41",
            id);
    if (rows.size() > 40) throw new ResponseStatusException(HttpStatus.CONFLICT);
    return rows;
  }

  private UUID latest(List<Map<String, Object>> rows) {
    for (int i = rows.size() - 1; i >= 0; i--)
      if ("QUESTION".equals(rows.get(i).get("kind"))) return (UUID) rows.get(i).get("id");
    return null;
  }

  // An imported NEEDS_INFORMATION row with no real question cannot create an answer.
  private boolean consistent(Map<String, Object> r, List<Map<String, Object>> rows) {
    long rev = revision(r);
    if (rev != rows.size() || rev > 40) return false;
    UUID question = null;
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      if (((Number) row.get("revision")).longValue() != i + 1) return false;
      if (i % 2 == 0) {
        if (!"QUESTION".equals(row.get("kind")) || row.get("question_id") != null) return false;
        question = (UUID) row.get("id");
      } else if (!"ANSWER".equals(row.get("kind"))
          || !Objects.equals(question, row.get("question_id"))) return false;
    }
    return "PENDING".equals(r.get("status"))
        ? rev % 2 == 0
        : "NEEDS_INFORMATION".equals(r.get("status")) && rev % 2 == 1;
  }

  private boolean safe(
      Map<String, Object> r,
      Map<String, Object> s,
      Map<String, Object> h,
      HumanIdentity human,
      boolean reply,
      boolean grant,
      boolean lock) {
    var blockers =
        approvals.blockers(r, s, h, approvals.verified(r, s), human, grant, Instant.now(), lock);
    return blockers.stream()
        .allMatch(
            b ->
                b.equals("NOT_PENDING")
                    || reply && (b.equals("SELF_DECISION") || b.equals("NO_SCOPED_GRANT")));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ApprovalInformationResponse information(UUID org, UUID id, HumanIdentity human) {
    approvals.authorize(org, human, false);
    var r = approvals.request(org, id, false);
    var s = approvals.snapshot(id);
    var h = approvals.head(s, false);
    var rows = rows(id);
    boolean grant = approvals.granted(approvals.grants(org, human, r, false), Instant.now());
    boolean valid = consistent(r, rows) && approvals.decision(id) == null;
    boolean ask =
        valid
            && "PENDING".equals(r.get("status"))
            && revision(r) < 40
            && safe(r, s, h, human, false, grant, false);
    boolean reply =
        valid
            && "NEEDS_INFORMATION".equals(r.get("status"))
            && human.id().equals(r.get("requester_human_id"))
            && safe(r, s, h, human, true, false, false);
    var entries =
        rows.stream()
            .map(
                e ->
                    new Entry(
                        (UUID) e.get("id"),
                        (String) e.get("kind"),
                        (UUID) e.get("actor_id"),
                        (String) e.get("actor_name"),
                        (String) e.get("text"),
                        (String) e.get("evidence"),
                        ApprovalDetailService.time(e, "created_at")))
            .toList();
    return new ApprovalInformationResponse(id, revision(r), ask, reply, entries, latest(rows));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Mutation receipt(UUID org, UUID id, HumanIdentity human, UUID key) {
    approvals.authorize(org, human, false);
    var request = approvals.request(org, id, false);
    var receipts =
        jdbc.queryForList(
            "select * from approval_information_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=? and approval_id=?",
            org,
            human.id(),
            key,
            id);
    if (receipts.isEmpty()) return null;
    var receipt = receipts.getFirst();
    if ("QUESTION".equals(receipt.get("operation"))) {
      if (!approvals.granted(approvals.grants(org, human, receipt, false), Instant.now()))
        throw new AccessDeniedException("Scoped grant required");
    } else if (!human.id().equals(request.get("requester_human_id")))
      throw new AccessDeniedException("Requester required");
    return new Mutation(
        id,
        ((Number) receipt.get("revision")).longValue(),
        (String) receipt.get("status"),
        (UUID) receipt.get("entry_id"));
  }

  private String text(String value, int max, boolean required) {
    if (value == null) {
      if (required) throw new IllegalArgumentException("Text required");
      return null;
    }
    if (value.length() > max) throw new IllegalArgumentException("Invalid text");
    String clean =
        value.replaceAll(
            "^[\\x09-\\x0D\\x1C-\\x20\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+|[\\x09-\\x0D\\x1C-\\x20\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+$",
            "");
    if (clean
        .codePoints()
        .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
      throw new IllegalArgumentException("Invalid text");
    if (required && clean.isBlank()) throw new IllegalArgumentException("Text required");
    return !required && clean.isBlank() ? null : clean;
  }

  @Transactional
  public Result exchange(UUID org, UUID id, HumanIdentity human, UUID key, Command command) {
    if (command == null
        || key == null
        || command.revision() == null
        || command.revision() < 0
        || command.revision() > 9007199254740991L
        || command.reply() && (command.questionId() == null || command.question() != null)
        || !command.reply()
            && (command.questionId() != null
                || command.answer() != null
                || command.evidence() != null))
      throw new IllegalArgumentException("Invalid command");
    boolean reply = command.reply();
    String content = text(reply ? command.answer() : command.question(), 2000, true);
    String evidence = text(command.evidence(), 1000, false);
    approvals.authorize(org, human, true);
    String fingerprint;
    try {
      fingerprint =
          ApprovalDetailService.sha256(
              json.writeValueAsString(
                  Arrays.asList(
                      id, reply, command.revision(), command.questionId(), content, evidence)));
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(ex);
    }
    var receipts =
        jdbc.queryForList(
            "select * from approval_information_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            org,
            human.id(),
            key);
    if (!receipts.isEmpty()) {
      var receipt = receipts.getFirst();
      if ("QUESTION".equals(receipt.get("operation"))) {
        if (!approvals.granted(approvals.grants(org, human, receipt, true), Instant.now()))
          throw new AccessDeniedException("Scoped grant required");
      } else {
        var original = approvals.request(org, (UUID) receipt.get("approval_id"), false);
        if (!human.id().equals(original.get("requester_human_id")))
          throw new AccessDeniedException("Requester required");
      }
      if (!fingerprint.equals(receipt.get("fingerprint")))
        throw new ResponseStatusException(HttpStatus.CONFLICT);
      return new Result(
          new Mutation(
              (UUID) receipt.get("approval_id"),
              ((Number) receipt.get("revision")).longValue(),
              (String) receipt.get("status"),
              (UUID) receipt.get("entry_id")),
          true);
    }
    approvals.request(org, id, false);
    var initial = approvals.snapshot(id);
    var h = approvals.head(initial, true);
    var r = approvals.request(org, id, true);
    var s = approvals.snapshot(id, true);
    if (!Objects.equals(initial, s)) throw new ResponseStatusException(HttpStatus.CONFLICT);
    var gs = reply ? List.<Map<String, Object>>of() : approvals.grants(org, human, r, true);
    if (reply
        ? !human.id().equals(r.get("requester_human_id"))
        : !approvals.granted(gs, Instant.now()))
      throw new AccessDeniedException("Authority required");
    var blockers =
        approvals.blockers(r, s, h, approvals.verified(r, s), human, !reply, Instant.now(), true);
    if (blockers.contains("MISSING_REQUESTER")
        || blockers.contains("REQUESTER_OUT_OF_SCOPE")
        || !reply && blockers.contains("SELF_DECISION"))
      throw new AccessDeniedException("Request denied");
    var history = rows(id);
    if (!consistent(r, history)
        || approvals.decision(id) != null
        || revision(r) != command.revision()
        || reply
            && (!"NEEDS_INFORMATION".equals(r.get("status"))
                || !Objects.equals(latest(history), command.questionId()))
        || !reply && (!"PENDING".equals(r.get("status")) || revision(r) >= 40)
        || blockers.stream()
            .anyMatch(
                b ->
                    !b.equals("NOT_PENDING")
                        && !(reply && (b.equals("SELF_DECISION") || b.equals("NO_SCOPED_GRANT"))))
        || s == null
        || !ApprovalDetailService.time(s, "expires_at").isAfter(Instant.now()))
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    if (!reply && !approvals.granted(gs, Instant.now()))
      throw new AccessDeniedException("Scoped grant required");
    long next = revision(r) + 1;
    UUID entry = UUID.randomUUID();
    String status = reply ? "PENDING" : "NEEDS_INFORMATION";
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String actorName =
        jdbc.queryForObject(
            "select display_name from human_accounts where id=?", String.class, human.id());
    jdbc.update(
        "insert into"
            + " approval_information_entries(id,approval_id,revision,kind,actor_id,actor_name,text,evidence,created_at,question_id)"
            + " values (?,?,?,?,?,?,?,?,?,?)",
        entry,
        id,
        next,
        reply ? "ANSWER" : "QUESTION",
        human.id(),
        actorName,
        content,
        evidence,
        now,
        command.questionId());
    jdbc.update(
        "update approval_requests set status=?,information_revision=?,updated_at=? where id=?",
        status,
        next,
        now,
        id);
    jdbc.update(
        "insert into"
            + " approval_information_receipts(organization_id,actor_id,idempotency_key,fingerprint,approval_id,revision,status,entry_id,operation,team_id,action_type,risk_level)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
        org,
        human.id(),
        key,
        fingerprint,
        id,
        next,
        status,
        entry,
        reply ? "ANSWER" : "QUESTION",
        r.get("team_id"),
        r.get("action_type"),
        r.get("risk_level"));
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values (?,?,'HUMAN',?,?,?,?)",
        UUID.randomUUID(),
        org,
        human.id(),
        reply ? "APPROVAL_INFORMATION_REPLIED" : "APPROVAL_INFORMATION_REQUESTED",
        "{\"approvalId\":\"" + id + "\",\"entryId\":\"" + entry + "\",\"revision\":" + next + "}",
        now);
    return new Result(new Mutation(id, next, status, entry), false);
  }
}

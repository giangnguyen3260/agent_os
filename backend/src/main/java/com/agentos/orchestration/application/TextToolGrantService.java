package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Human grants for one pinned built-in tool, never Skill/model-text authority. */
@Service
@ConditionalOnProperty(name = "agentos.tools.text-metrics.enabled", havingValue = "true")
public class TextToolGrantService {
  public static final UUID TOOL_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");

  public enum Operation {
    ISSUE,
    REVOKE
  }

  public static class Conflict extends RuntimeException {}

  public record Scope(UUID organizationId, UUID teamId, UUID missionId, UUID agentId) {}

  public record Grant(
      UUID id, OffsetDateTime issuedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {}

  public record Context(
      Scope scope,
      String tool,
      int version,
      String capability,
      String action,
      Grant grant,
      boolean canIssue,
      boolean canRevoke,
      String fingerprint) {}

  public record Receipt(
      Scope scope,
      UUID idempotencyKey,
      Operation operation,
      UUID grantId,
      OffsetDateTime recordedAt) {}

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;
  private final ExecutionClaimStore claims;
  private final ObjectMapper json;

  public TextToolGrantService(
      JdbcTemplate jdbc,
      ExecutionReviewDecisionStore authority,
      ExecutionClaimStore claims,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.claims = claims;
    this.json = json;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context current(Scope s, HumanIdentity actor) {
    authority.requireAdmin(s.organizationId(), actor, false);
    return context(s);
  }

  private Context context(Scope s) {
    var states =
        jdbc.queryForList(
            "select o.status||':'||t.status||':'||m.status||':'||a.status from missions m join"
                + " teams t on t.organization_id=m.organization_id and t.id=m.team_id join"
                + " organizations o on o.id=m.organization_id join agents a on"
                + " a.organization_id=m.organization_id and a.team_id=m.team_id where"
                + " m.organization_id=? and m.team_id=? and m.id=? and a.id=?",
            String.class,
            s.organizationId(),
            s.teamId(),
            s.missionId(),
            s.agentId());
    if (states.size() != 1) throw new Conflict();
    var rows =
        jdbc.query(
            "select id,issued_at,expires_at,revoked_at from text_tool_grants where"
                + " organization_id=? and team_id=? and mission_id=? and agent_id=? order by"
                + " issued_at desc,id desc limit 1",
            (rs, n) ->
                new Grant(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, OffsetDateTime.class),
                    rs.getObject(3, OffsetDateTime.class),
                    rs.getObject(4, OffsetDateTime.class)),
            s.organizationId(),
            s.teamId(),
            s.missionId(),
            s.agentId());
    Grant g = rows.isEmpty() ? null : rows.getFirst();
    boolean live = g != null && g.revokedAt() == null && g.expiresAt().isAfter(claims.now());
    boolean eligible =
        List.of(
                "ACTIVE:ACTIVE:CREATED:ACTIVE",
                "ACTIVE:ACTIVE:PLANNING:ACTIVE",
                "ACTIVE:ACTIVE:READY:ACTIVE",
                "ACTIVE:ACTIVE:RUNNING:ACTIVE")
            .contains(states.getFirst());
    String fingerprint =
        hash("text.metrics@1|" + s + "|" + states.getFirst() + "|" + g + "|" + live);
    return new Context(
        s,
        "text.metrics",
        1,
        "text:inspect",
        "INSPECT",
        g,
        eligible && !live,
        g != null && g.revokedAt() == null,
        fingerprint);
  }

  @Transactional
  public Receipt apply(Scope s, HumanIdentity actor, UUID key, Operation op, String expected) {
    authority.requireAdmin(s.organizationId(), actor, true);
    if (key == null || op == null || expected == null || !expected.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid tool grant command");
    String request = hash("text.metrics.grant@1|" + s + "|" + op + "|" + expected);
    var old =
        jdbc.query(
            "select request_sha256,result_json,result_sha256 from text_tool_grant_receipts where"
                + " organization_id=? and actor_id=? and idempotency_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
            s.organizationId(),
            actor.id(),
            key);
    if (!old.isEmpty()) {
      if (!request.equals(old.getFirst()[0])) throw new Conflict();
      try {
        String raw = old.getFirst()[1];
        if (raw == null || raw.length() > 4096 || !hash(raw).equals(old.getFirst()[2]))
          throw new Conflict();
        var result = json.readValue(raw, Receipt.class);
        if (!s.equals(result.scope())
            || !key.equals(result.idempotencyKey())
            || op != result.operation()
            || result.grantId() == null
            || result.recordedAt() == null) throw new Conflict();
        if (jdbc.queryForObject(
                "select count(*) from text_tool_grants where organization_id=? and team_id=? and"
                    + " mission_id=? and agent_id=? and id=?",
                Integer.class,
                s.organizationId(),
                s.teamId(),
                s.missionId(),
                s.agentId(),
                result.grantId())
            != 1) throw new Conflict();
        return result;
      } catch (Exception e) {
        throw new Conflict();
      }
    }
    Context c = context(s);
    if (!c.fingerprint().equals(expected)
        || (op == Operation.ISSUE ? !c.canIssue() : !c.canRevoke())) throw new Conflict();
    var at =
        claims
            .now()
            .withOffsetSameInstant(java.time.ZoneOffset.UTC)
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID grant;
    if (op == Operation.ISSUE) {
      grant = UUID.randomUUID();
      jdbc.update(
          "insert into"
              + " text_tool_grants(id,organization_id,team_id,mission_id,agent_id,tool_version,issued_by,issued_at,expires_at)"
              + " values(?,?,?,?,?,1,?,?,?)",
          grant,
          s.organizationId(),
          s.teamId(),
          s.missionId(),
          s.agentId(),
          actor.id(),
          at,
          at.plusHours(1));
    } else {
      grant = c.grant().id();
      if (jdbc.update(
              "update text_tool_grants set revoked_at=?,revoked_by=? where organization_id=? and"
                  + " id=? and revoked_at is null",
              at,
              actor.id(),
              s.organizationId(),
              grant)
          != 1) throw new Conflict();
    }
    var result = new Receipt(s, key, op, grant, at);
    try {
      jdbc.update(
          "insert into text_tool_grant_receipts values(?,?,?,?,?,?)",
          s.organizationId(),
          actor.id(),
          key,
          request,
          json.writeValueAsString(result),
          hash(json.writeValueAsString(result)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new Conflict();
    }
    return result;
  }

  static String hash(String value) {
    return AcceptedRunOutputEvidence.sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}

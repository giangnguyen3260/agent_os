package com.agentos.orchestration.application;

import com.agentos.tool.application.ToolActionRequest;
import com.agentos.tool.application.ToolGateway;
import com.agentos.tool.application.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trusted runtime adapter. No HTTP/model-supplied worker token or arbitrary tool dispatch. */
@Service
@ConditionalOnProperty(name = "agentos.tools.text-metrics.enabled", havingValue = "true")
public class ScopedTextToolGateway implements ToolGateway {
  @org.springframework.beans.factory.annotation.Value(
      "${agentos.mission-graph-admission.enabled:false}")
  private boolean graphAdmissionEnabled;

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;
  private final ExecutionBudgetStore budgets;
  private final MissionExecutionControlStore controls;

  public ScopedTextToolGateway(
      JdbcTemplate jdbc,
      ExecutionClaimStore claims,
      ExecutionBudgetStore budgets,
      MissionExecutionControlStore controls) {
    this.jdbc = jdbc;
    this.claims = claims;
    this.budgets = budgets;
    this.controls = controls;
  }

  @Override
  public ToolResult execute(ToolActionRequest request) {
    throw denied();
  }

  @Transactional
  ToolResult execute(ExecutionClaimStore.Token token, ToolActionRequest r) {
    if (token == null
        || r == null
        || !token.organization().equals(r.organizationId())
        || !token.run().equals(r.executionRunId())
        || !TextToolGrantService.TOOL_ID.equals(r.toolId())
        || !"text:inspect".equals(r.capability())
        || !"INSPECT".equals(r.actionType())
        || r.payload() == null
        || !r.payload().keySet().equals(Set.of("text"))
        || !(r.payload().get("text") instanceof String)) throw denied();
    String text = (String) r.payload().get("text");
    if (text.length() > 8192 || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
      throw denied();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 8192) throw denied();
    UUID key;
    try {
      if (r.idempotencyKey() == null
          || !r.idempotencyKey().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
        throw denied();
      key = UUID.fromString(r.idempotencyKey());
    } catch (IllegalArgumentException e) {
      throw denied();
    }
    claims.requireCurrent(token);
    controls.requireOpen(token.organization(), token.run());
    new MissionGraphDependencyStore(jdbc, graphAdmissionEnabled)
        .requireConfiguration(token.organization(), token.run());
    new MissionGraphDependencyStore(jdbc, graphAdmissionEnabled)
        .requireStart(token.organization(), token.run());
    var scopes =
        jdbc.queryForList(
            "select r.team_id from execution_runs r join accepted_mission_plan_tasks p on"
                + " p.organization_id=r.organization_id and p.team_id=r.team_id and"
                + " p.mission_id=r.mission_id and p.plan_revision=r.plan_revision and"
                + " p.task_id=r.task_id join tasks t on t.organization_id=r.organization_id and"
                + " t.id=r.task_id join missions m on m.organization_id=r.organization_id and"
                + " m.id=r.mission_id join teams g on g.organization_id=r.organization_id and"
                + " g.id=r.team_id join organizations o on o.id=r.organization_id join agents a on"
                + " a.organization_id=r.organization_id and a.team_id=r.team_id and"
                + " a.id=p.owner_agent_id where r.organization_id=? and r.id=? and r.mission_id=?"
                + " and r.task_id=? and p.owner_agent_id=? and t.owner_agent_id=p.owner_agent_id"
                + " and r.status='RUNNING' and t.status='RUNNING' and m.status='RUNNING' and"
                + " o.status='ACTIVE' and g.status='ACTIVE' and a.status='ACTIVE'",
            UUID.class,
            token.organization(),
            token.run(),
            r.missionId(),
            r.taskId(),
            r.agentId());
    if (scopes.size() != 1) throw denied();
    var grants =
        jdbc.queryForList(
            "select id from text_tool_grants where organization_id=? and team_id=? and mission_id=?"
                + " and agent_id=? and tool_version=1 and revoked_at is null and expires_at>? order"
                + " by id for update",
            UUID.class,
            token.organization(),
            scopes.getFirst(),
            r.missionId(),
            r.agentId(),
            claims.now());
    if (grants.size() != 1) throw denied();
    String input = AcceptedRunOutputEvidence.sha(bytes);
    String fingerprint =
        TextToolGrantService.hash(
            "text.metrics@1|"
                + token
                + "|"
                + r.missionId()
                + "|"
                + r.taskId()
                + "|"
                + r.agentId()
                + "|"
                + grants.getFirst()
                + "|"
                + input);
    var previous =
        jdbc.query(
            "select request_sha256,input_sha256,utf8_bytes,code_points,lines from text_tool_effects"
                + " where run_id=? and idempotency_key=?",
            (rs, n) ->
                new Object[] {
                  rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)
                },
            token.run(),
            key);
    if (!previous.isEmpty()) {
      var p = previous.getFirst();
      if (!fingerprint.equals(p[0])
          || !input.equals(p[1])
          || !Integer.valueOf(bytes.length).equals(p[2])
          || !Integer.valueOf(text.codePointCount(0, text.length())).equals(p[3])
          || !Integer.valueOf(
                  text.isEmpty() ? 0 : 1 + (int) text.chars().filter(c -> c == '\n').count())
              .equals(p[4])) throw denied();
      return result((String) p[1], (Integer) p[2], (Integer) p[3], (Integer) p[4]);
    }
    UUID action = UUID.randomUUID();
    budgets.reserve(
        token, action, 1); // Transient capacity hold, local deterministic calculation settles zero.
    int points = text.codePointCount(0, text.length()),
        lines = text.isEmpty() ? 0 : 1 + (int) text.chars().filter(c -> c == '\n').count();
    claims.requireCurrent(token);
    controls.requireOpen(token.organization(), token.run());
    if (jdbc.queryForObject(
            "select count(*) from text_tool_grants where id=? and revoked_at is null and"
                + " expires_at>?",
            Integer.class,
            grants.getFirst(),
            claims.now())
        != 1) throw denied();
    budgets.settleVerified(token.organization(), token.run(), action, 0);
    jdbc.update(
        "insert into"
            + " text_tool_effects(organization_id,run_id,idempotency_key,attempt_id,fence,grant_id,action_id,request_sha256,input_sha256,utf8_bytes,code_points,lines,recorded_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        token.organization(),
        token.run(),
        key,
        token.attempt(),
        token.fence(),
        grants.getFirst(),
        action,
        fingerprint,
        input,
        bytes.length,
        points,
        lines,
        claims.now());
    return result(input, bytes.length, points, lines);
  }

  private static ToolResult result(String sha, int bytes, int points, int lines) {
    return new ToolResult(
        "SUCCEEDED",
        Map.of(
            "tool",
            "text.metrics",
            "version",
            1,
            "inputSha256",
            sha,
            "utf8Bytes",
            bytes,
            "codePoints",
            points,
            "lines",
            lines));
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Scoped Tool execution is unavailable.");
  }
}

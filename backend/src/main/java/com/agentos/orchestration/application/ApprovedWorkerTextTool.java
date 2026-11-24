package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.tool.application.ToolActionRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit human-approved local preprocessing. The worker supplies its own trusted lease. */
@Service
@ConditionalOnProperty(name = "agentos.tools.text-metrics.enabled", havingValue = "true")
public class ApprovedWorkerTextTool {
  public record Preview(
      UUID runId,
      String revision,
      UUID grantId,
      String text,
      String inputSha256,
      String fingerprint) {
    @Override
    public String toString() {
      return "WorkerToolPreview[redacted]";
    }
  }

  private final JdbcTemplate jdbc;
  private final ExecutionReviewDecisionStore authority;
  private final ExecutionStartService starts;
  private final ScopedTextToolGateway gateway;
  private final ExecutionClaimStore claims;

  public ApprovedWorkerTextTool(
      JdbcTemplate jdbc,
      ExecutionReviewDecisionStore authority,
      ExecutionStartService starts,
      ScopedTextToolGateway gateway,
      ExecutionClaimStore claims) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.starts = starts;
    this.gateway = gateway;
    this.claims = claims;
  }

  @Transactional
  public Preview preview(UUID org, UUID run, HumanIdentity actor) {
    authority.requireAdmin(org, actor, true);
    return current(org, run);
  }

  private Preview current(UUID org, UUID run) {
    var revisions =
        jdbc.queryForList(
            "select revision from execution_runs where organization_id=? and id=?",
            Long.class,
            org,
            run);
    if (revisions.size() != 1) throw new TextToolGrantService.Conflict();
    long rev = revisions.getFirst();
    starts.requireEligible(org, run, rev);
    var rows =
        jdbc.query(
            "select t.description,g.id from execution_runs r join tasks t on"
                + " t.organization_id=r.organization_id and t.id=r.task_id join text_tool_grants g"
                + " on g.organization_id=r.organization_id and g.team_id=r.team_id and"
                + " g.mission_id=r.mission_id and g.agent_id=t.owner_agent_id where"
                + " r.organization_id=? and r.id=? and g.tool_version=1 and g.revoked_at is null"
                + " and g.expires_at>?",
            (rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)},
            org,
            run,
            claims.now());
    if (rows.size() != 1) throw new TextToolGrantService.Conflict();
    String text = (String) rows.getFirst()[0];
    if (text == null
        || text.length() > 8192
        || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
        || text.getBytes(StandardCharsets.UTF_8).length > 8192)
      throw new TextToolGrantService.Conflict();
    UUID grant = (UUID) rows.getFirst()[1];
    String sha = TextToolGrantService.hash(text);
    return new Preview(
        run,
        Long.toString(rev),
        grant,
        text,
        sha,
        TextToolGrantService.hash(
            "worker.text.metrics@1|" + org + "|" + run + "|" + rev + "|" + grant + "|" + sha));
  }

  @Transactional
  public Preview approve(UUID org, UUID run, HumanIdentity actor, UUID key, String fingerprint) {
    authority.requireAdmin(org, actor, true);
    if (key == null || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid tool approval");
    Preview p = current(org, run);
    if (!p.fingerprint().equals(fingerprint)) throw new TextToolGrantService.Conflict();
    var old =
        jdbc.queryForList(
            "select fingerprint from worker_text_tool_approvals where organization_id=? and"
                + " actor_id=? and idempotency_key=?",
            String.class,
            org,
            actor.id(),
            key);
    if (!old.isEmpty()) {
      if (!old.getFirst().equals(fingerprint)) throw new TextToolGrantService.Conflict();
      return p;
    }
    if (jdbc.queryForObject(
            "select count(*) from worker_text_tool_approvals where run_id=? and source_revision=?",
            Integer.class,
            run,
            Long.parseLong(p.revision()))
        != 0) throw new TextToolGrantService.Conflict();
    jdbc.update(
        "insert into worker_text_tool_approvals values(?,?,?,?,?,?,?,?,?,?,?)",
        org,
        run,
        Long.parseLong(p.revision()),
        key,
        actor.id(),
        actor.credentialVersion(),
        p.grantId(),
        p.text(),
        p.inputSha256(),
        p.fingerprint(),
        claims.now());
    return p;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Preview receipt(UUID org, UUID run, HumanIdentity actor, UUID key) {
    authority.requireAdmin(org, actor, false);
    var rows =
        jdbc.query(
            "select source_revision,grant_id,input_text,input_sha256,fingerprint from"
                + " worker_text_tool_approvals where organization_id=? and run_id=? and actor_id=?"
                + " and idempotency_key=?",
            (rs, n) ->
                new Preview(
                    run,
                    rs.getString(1),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5)),
            org,
            run,
            actor.id(),
            key);
    if (rows.isEmpty()) return null;
    var p = rows.getFirst();
    String text = p.text();
    if (rows.size() != 1
        || text == null
        || text.length() > 8192
        || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
        || text.getBytes(StandardCharsets.UTF_8).length > 8192
        || !TextToolGrantService.hash(text).equals(p.inputSha256())
        || !TextToolGrantService.hash(
                "worker.text.metrics@1|"
                    + org
                    + "|"
                    + run
                    + "|"
                    + p.revision()
                    + "|"
                    + p.grantId()
                    + "|"
                    + p.inputSha256())
            .equals(p.fingerprint())) throw new TextToolGrantService.Conflict();
    return p;
  }

  @Transactional
  void execute(ExecutionClaimStore.Token token) {
    claims.requireCurrent(token);
    var rows =
        jdbc.query(
            "select"
                + " a.actor_id,a.actor_version,a.grant_id,a.input_text,a.input_sha256,a.fingerprint,r.source_run_revision,a.idempotency_key"
                + " from worker_text_tool_approvals a join execution_start_dispatch r on"
                + " r.organization_id=a.organization_id and r.run_id=a.run_id and"
                + " r.source_run_revision=a.source_revision where a.organization_id=? and"
                + " a.run_id=? and r.state='CLAIMED' and r.source_run_revision=(select revision-1"
                + " from execution_runs where id=a.run_id)",
            (rs, n) ->
                new Object[] {
                  rs.getObject(1, UUID.class),
                  rs.getLong(2),
                  rs.getObject(3, UUID.class),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getLong(7),
                  rs.getString(6),
                  rs.getObject(8, UUID.class)
                },
            token.organization(),
            token.run());
    if (rows.isEmpty()) {
      if (jdbc.queryForObject(
              "select count(*) from worker_text_tool_approvals a join execution_runs r on"
                  + " r.id=a.run_id where a.organization_id=? and a.run_id=? and"
                  + " a.source_revision=r.revision-1",
              Integer.class,
              token.organization(),
              token.run())
          != 0) throw new TextToolGrantService.Conflict();
      return;
    }
    if (rows.size() != 1) throw new TextToolGrantService.Conflict();
    var a = rows.getFirst();
    authority.requireAdmin(
        token.organization(), new HumanIdentity((UUID) a[0], "", "", (Long) a[1]), true);
    String text = (String) a[3];
    if (!TextToolGrantService.hash(text).equals(a[4])
        || !TextToolGrantService.hash(
                "worker.text.metrics@1|"
                    + token.organization()
                    + "|"
                    + token.run()
                    + "|"
                    + a[5]
                    + "|"
                    + a[2]
                    + "|"
                    + a[4])
            .equals(a[6])) throw new TextToolGrantService.Conflict();
    if (jdbc.queryForObject(
            "select count(*) from text_tool_grants where id=? and revoked_at is null and"
                + " expires_at>?",
            Integer.class,
            a[2],
            claims.now())
        != 1) throw new TextToolGrantService.Conflict();
    var scope =
        jdbc.queryForMap(
            "select r.mission_id,r.task_id,p.owner_agent_id from execution_runs r join"
                + " accepted_mission_plan_tasks p on p.organization_id=r.organization_id and"
                + " p.team_id=r.team_id and p.mission_id=r.mission_id and"
                + " p.plan_revision=r.plan_revision and p.task_id=r.task_id where"
                + " r.organization_id=? and r.id=?",
            token.organization(),
            token.run());
    gateway.execute(
        token,
        new ToolActionRequest(
            token.organization(),
            (UUID) scope.get("mission_id"),
            (UUID) scope.get("task_id"),
            token.run(),
            (UUID) scope.get("owner_agent_id"),
            TextToolGrantService.TOOL_ID,
            "text:inspect",
            "INSPECT",
            Map.of("text", text),
            a[7].toString()));
  }
}

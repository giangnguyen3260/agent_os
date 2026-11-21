package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Internal evidence boundary. Callers must authorize and lock the organization before use. */
@Service
public class AcceptedPlanningOutputReader {
  public record Source(
      UUID missionId,
      UUID runId,
      UUID attemptId,
      String outputSha256,
      String provenanceSha256,
      String completionFingerprint,
      String content) {
    @Override
    public String toString() {
      return "AcceptedPlanningSource[redacted]";
    }
  }

  private final JdbcTemplate jdbc;
  private final org.springframework.beans.factory.ObjectProvider<MissionGraphCompletionService>
      completions;

  public AcceptedPlanningOutputReader(
      JdbcTemplate jdbc,
      org.springframework.beans.factory.ObjectProvider<MissionGraphCompletionService> completions) {
    this.jdbc = jdbc;
    this.completions = completions;
  }

  public Source read(
      UUID org, UUID team, UUID target, UUID run, com.agentos.auth.HumanIdentity actor) {
    var rows =
        jdbc.query(
            "select r.mission_id,r.plan_revision from execution_runs r join missions m on"
                + " m.organization_id=r.organization_id and m.team_id=r.team_id and"
                + " m.id=r.mission_id where r.organization_id=? and r.team_id=? and r.id=? and"
                + " m.status='COMPLETED' and m.id<>?",
            (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getLong(2)},
            org,
            team,
            run,
            target);
    if (rows.size() != 1) throw new IllegalStateException("Planning evidence unavailable");
    var service = completions.getIfAvailable();
    if (service == null) throw new IllegalStateException("Planning completion unavailable");
    var completion = service.current(org, team, (UUID) rows.getFirst()[0], actor);
    if (!"COMPLETED".equals(completion.status()) || !completion.blockers().isEmpty())
      throw new IllegalStateException("Planning completion unavailable");
    var evidence = new AcceptedRunOutputEvidence(jdbc).current(org, run, (Long) rows.getFirst()[1]);
    if (evidence == null) throw new IllegalStateException("Planning evidence unavailable");
    String content =
        jdbc.queryForObject(
            "select content from execution_verified_output_content where run_id=? and attempt_id=?"
                + " and output_sha256=?",
            String.class,
            run,
            evidence.attemptId(),
            evidence.outputSha256());
    if (content == null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
      throw new IllegalStateException("Planning evidence unavailable");
    return new Source(
        (UUID) rows.getFirst()[0],
        run,
        evidence.attemptId(),
        evidence.outputSha256(),
        evidence.provenanceSha256(),
        completion.fingerprint(),
        content);
  }
}

package com.agentos.orchestration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Same-plan accepted output with complete settled provenance, independent of Mission size. */
final class AcceptedRunOutputEvidence {
  record Evidence(
      UUID runId,
      UUID attemptId,
      String outputSha256,
      String requestSha256,
      long runRevision,
      String provenanceSha256) {}

  private final JdbcTemplate jdbc;

  AcceptedRunOutputEvidence(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Evidence current(UUID org, UUID run, long plan) {
    var rows =
        jdbc.query(
            """
select t.id,r.id,a.id,e.output_sha256,x.content,r.revision,r.plan_revision,
  a.attempt_number,p.owner_agent_id,p.task_edit_revision,p.task_owner_revision,p.dependency_revision,
  z.action_id,z.connection_id,q.request_sha256,u.provider_request_sha256,u.input_tokens,u.output_tokens,
  b.actual_microusd,q.input_rate,q.output_rate,q.maximum_microusd,q.request_body,z.credential_revision,z.model_id,z.fence,z.control_revision
from tasks t
join execution_runs r on r.organization_id=t.organization_id and r.team_id=t.team_id
  and r.mission_id=t.mission_id and r.task_id=t.id
join accepted_mission_plan_tasks p on p.organization_id=r.organization_id and p.team_id=r.team_id
  and p.mission_id=r.mission_id and p.plan_revision=r.plan_revision and p.task_id=t.id
join execution_attempts a on a.run_id=r.id and a.status='SUCCEEDED'
  and a.attempt_number=(select max(a2.attempt_number) from execution_attempts a2 where a2.run_id=r.id)
join execution_review_decisions d on d.run_id=r.id and d.attempt_id=a.id
  and d.decision='ACCEPT' and d.source_run_revision=r.revision-1
join execution_reconciled_review_submissions s on s.run_id=r.id and s.attempt_id=a.id
  and s.source_run_revision=d.source_run_revision-1
join execution_attempt_reconciliations e on e.run_id=r.id and e.attempt_id=a.id and e.outcome='SUCCEEDED'
join execution_verified_output_content x on x.run_id=r.id and x.attempt_id=a.id and x.output_sha256=e.output_sha256
join execution_model_authorizations z on z.run_id=r.id and z.attempt_id=a.id
  and z.organization_id=r.organization_id and z.agent_id=p.owner_agent_id and z.handed_off_at is not null
join execution_budget_reservations b on b.run_id=z.run_id and b.action_id=z.action_id
  and b.attempt_id=a.id and b.fence=z.fence and b.status='SETTLED'
join execution_model_usage u on u.organization_id=r.organization_id and u.run_id=z.run_id
  and u.action_id=z.action_id and u.connection_id=z.connection_id
join execution_start_dispatch sd on sd.organization_id=r.organization_id and sd.run_id=r.id
  and sd.id=z.action_id and sd.state='FINISHED'
join execution_openai_requests q on q.dispatch_id=sd.id and q.connection_id=z.connection_id
  and q.credential_revision=z.credential_revision and q.model_id=z.model_id and q.request_sha256=z.request_sha256
where t.organization_id=? and r.id=? and r.plan_revision=?
  and t.status='COMPLETED' and r.status='COMPLETED'
  and t.owner_agent_id=p.owner_agent_id and t.task_edit_revision=p.task_edit_revision
  and t.task_owner_revision=p.task_owner_revision and t.dependency_revision=p.dependency_revision
""",
            (rs, n) ->
                new Object[] {
                  rs.getObject(3, UUID.class),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getLong(6),
                  rs.getString(15),
                  rs.getLong(17),
                  rs.getLong(18),
                  rs.getLong(19),
                  rs.getLong(20),
                  rs.getLong(21),
                  rs.getLong(22),
                  rs.getBytes(23),
                  rs.getObject(13, UUID.class),
                  rs.getObject(14, UUID.class),
                  rs.getString(16),
                  rs.getInt(8),
                  rs.getObject(9, UUID.class),
                  rs.getInt(10),
                  rs.getInt(11),
                  rs.getInt(12),
                  rs.getLong(24),
                  rs.getString(25),
                  rs.getLong(26),
                  rs.getLong(27)
                },
            org,
            run,
            plan);
    if (rows.size() != 1) return null;
    var row = rows.getFirst();
    String content = (String) row[2];
    if (content == null
        || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
        || content.getBytes(StandardCharsets.UTF_8).length > 262144
        || !sha(content.getBytes(StandardCharsets.UTF_8)).equals(row[1])
        || !sha((byte[]) row[11]).equals(row[4])) return null;
    var cost =
        java.math.BigInteger.valueOf((Long) row[5])
            .multiply(java.math.BigInteger.valueOf((Long) row[8]))
            .add(
                java.math.BigInteger.valueOf((Long) row[6])
                    .multiply(java.math.BigInteger.valueOf((Long) row[9])))
            .add(java.math.BigInteger.valueOf(999999))
            .divide(java.math.BigInteger.valueOf(1000000));
    if (!cost.equals(java.math.BigInteger.valueOf((Long) row[7])) || (Long) row[7] > (Long) row[10])
      return null;
    if (count(
                "select count(*) from execution_attempts where run_id=? and status not in"
                    + " ('SUCCEEDED','FAILED','CANCELLED')",
                run)
            > 0
        || count(
                "select count(*) from execution_claims where run_id=? and invalidated_at is null"
                    + " and expires_at>current_timestamp",
                run)
            > 0
        || count(
                "select count(*) from execution_budget_reservations where run_id=? and"
                    + " status<>'SETTLED'",
                run)
            > 0
        || count(
                "select count(*) from execution_start_dispatch where run_id=? and"
                    + " state<>'FINISHED'",
                run)
            > 0) return null;
    String contextSha;
    try {
      contextSha =
          ExecutionDependencyContextStore.storedDigest(
              jdbc, org, run, (UUID) row[12], (String) row[4]);
    } catch (IllegalStateException e) {
      return null;
    }
    var provenance = new StringBuilder(org + "|" + run + "|" + plan);
    for (int i = 0; i < row.length; i++)
      if (i != 2 && i != 11) provenance.append('|').append(row[i]);
    if (!contextSha.isEmpty()) provenance.append("|dependencyContext:").append(contextSha);
    return new Evidence(
        run,
        (UUID) row[0],
        (String) row[1],
        (String) row[4],
        (Long) row[3],
        sha(provenance.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private long count(String sql, UUID run) {
    return jdbc.queryForObject(sql, Long.class, run);
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("Digest unavailable");
    }
  }
}

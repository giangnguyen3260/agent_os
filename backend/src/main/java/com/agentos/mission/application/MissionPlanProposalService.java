package com.agentos.mission.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.orchestration.application.AcceptedPlanningOutputReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, all-or-nothing import of reviewed data. Never creates Runs or accepts a plan. */
@Service
@ConditionalOnProperty(name = "agentos.mission-plan-proposal.enabled", havingValue = "true")
public class MissionPlanProposalService {
  public static class Conflict extends RuntimeException {}

  public record Input(UUID sourceRunId, String proposalJson, Map<String, UUID> owners) {
    @Override
    public String toString() {
      return "ProposalInput[redacted]";
    }
  }

  public record Command(UUID idempotencyKey, String expectedFingerprint, Input input) {}

  public record Owner(UUID id, String name) {}

  public record Context(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      String title,
      String intakeVersion,
      String targetFingerprint,
      List<Owner> owners) {}

  public record Preview(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID sourceMissionId,
      UUID sourceRunId,
      UUID sourceAttemptId,
      String sourceOutputSha256,
      String sourceProvenanceSha256,
      String sourceCompletionFingerprint,
      String sourceJson,
      String proposalJson,
      String origin,
      MissionPlanProposalParser.Proposal proposal,
      Map<String, UUID> owners,
      String intakeVersion,
      String fingerprint,
      boolean executionAuthorized) {
    @Override
    public String toString() {
      return "ProposalPreview[redacted]";
    }
  }

  public record Receipt(
      UUID organizationId,
      UUID teamId,
      UUID missionId,
      UUID idempotencyKey,
      UUID sourceRunId,
      String reviewedFingerprint,
      String snapshotSha256,
      String origin,
      String intakeVersionBefore,
      String intakeVersionAfter,
      Map<String, UUID> taskIds,
      boolean executionAuthorized) {}

  public record Result(Receipt receipt, boolean replay) {}

  public record Applied(Receipt receipt, Preview snapshot) {}

  private final JdbcTemplate jdbc;
  private final MissionCreateService authority;
  private final AcceptedPlanningOutputReader sources;
  private final ObjectMapper json;

  public MissionPlanProposalService(
      JdbcTemplate jdbc,
      MissionCreateService authority,
      AcceptedPlanningOutputReader sources,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.authority = authority;
    this.sources = sources;
    this.json = json;
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  private Context target(UUID org, UUID team, UUID mission, boolean lock) {
    String suffix = lock ? " for update" : "";
    var parents =
        jdbc.query(
            "select"
                + " m.title,m.status,m.task_intake_version,m.task_intake_paused,t.status,o.status,m.goal,m.required_output,m.context,m.updated_at"
                + " from missions m join teams t on t.organization_id=m.organization_id and"
                + " t.id=m.team_id join organizations o on o.id=m.organization_id where"
                + " m.organization_id=? and m.team_id=? and m.id=?"
                + suffix,
            (rs, n) ->
                new Object[] {
                  rs.getString(1),
                  rs.getString(2),
                  rs.getLong(3),
                  rs.getBoolean(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
                  rs.getString(9),
                  rs.getObject(10).toString()
                },
            org,
            team,
            mission);
    if (parents.size() != 1) throw new Conflict();
    Object[] p = parents.getFirst();
    long version = (Long) p[2];
    if (!"CREATED".equals(p[1])
        || !"ACTIVE".equals(p[4])
        || !"ACTIVE".equals(p[5])
        || (Boolean) p[3]
        || version < 0
        || version == Long.MAX_VALUE) throw new Conflict();
    for (String table :
        List.of(
            "tasks",
            "execution_runs",
            "accepted_mission_plans",
            "mission_plan_selections",
            "mission_graph_admissions",
            "mission_execution_controls",
            "task_dependencies",
            "mission_plan_proposal_receipts"))
      if (jdbc.queryForObject(
              "select count(*) from "
                  + table
                  + " where organization_id=? and team_id=? and mission_id=?",
              Long.class,
              org,
              team,
              mission)
          != 0) throw new Conflict();
    Long count =
        jdbc.queryForObject(
            "select count(*) from agents where organization_id=? and team_id=? and status='ACTIVE'",
            Long.class,
            org,
            team);
    if (count > 200) throw new Conflict();
    var owners =
        jdbc.query(
            "select id,name from agents where organization_id=? and team_id=? and status='ACTIVE'"
                + " order by id",
            (rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2)),
            org,
            team);
    return new Context(
        org,
        team,
        mission,
        (String) p[0],
        Long.toString(version),
        hash(encode(java.util.Arrays.asList(p))),
        List.copyOf(owners));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID team, UUID mission, HumanIdentity actor) {
    authority.authorize(org, actor, true, false);
    return target(org, team, mission, false);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AcceptedPlanningOutputReader.Source source(
      UUID org, UUID team, UUID mission, HumanIdentity actor, UUID run) {
    authority.authorize(org, actor, true, false);
    target(org, team, mission, false);
    try {
      return sources.read(org, team, mission, run, actor);
    } catch (IllegalStateException e) {
      throw new Conflict();
    }
  }

  private Preview inspect(
      UUID org, UUID team, UUID mission, HumanIdentity actor, Input input, boolean lock) {
    if (input == null
        || input.sourceRunId() == null
        || input.owners() == null
        || input.owners().size() > 20) throw new IllegalArgumentException("Invalid proposal input");
    var target = target(org, team, mission, lock);
    AcceptedPlanningOutputReader.Source source;
    try {
      source = sources.read(org, team, mission, input.sourceRunId(), actor);
    } catch (IllegalStateException e) {
      throw new Conflict();
    }
    String proposed = input.proposalJson() == null ? source.content() : input.proposalJson();
    var proposal = MissionPlanProposalParser.parse(proposed);
    var keys =
        proposal.tasks().stream()
            .map(MissionPlanProposalParser.Task::key)
            .collect(java.util.stream.Collectors.toSet());
    if (!keys.equals(input.owners().keySet())
        || input.owners().values().stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("Bind every proposed Task to an Agent");
    var available = target.owners().stream().map(Owner::id).toList();
    if (!available.containsAll(input.owners().values())) throw new Conflict();
    var owners = new TreeMap<>(input.owners());
    String origin = proposed.equals(source.content()) ? "AGENT_OUTPUT" : "HUMAN_CORRECTED";
    String fingerprint =
        hash(
            encode(
                List.of(
                    "REVIEWED_PLAN_IMPORT_V1",
                    org,
                    team,
                    mission,
                    target.title(),
                    target.intakeVersion(),
                    target.targetFingerprint(),
                    source.missionId(),
                    source.runId(),
                    source.attemptId(),
                    source.outputSha256(),
                    source.provenanceSha256(),
                    source.completionFingerprint(),
                    hash(proposed),
                    target.owners().stream().filter(o -> owners.containsValue(o.id())).toList(),
                    owners,
                    proposal)));
    var result =
        new Preview(
            org,
            team,
            mission,
            source.missionId(),
            source.runId(),
            source.attemptId(),
            source.outputSha256(),
            source.provenanceSha256(),
            source.completionFingerprint(),
            source.content(),
            proposed,
            origin,
            proposal,
            Map.copyOf(owners),
            target.intakeVersion(),
            fingerprint,
            false);
    if (encode(result).getBytes(StandardCharsets.UTF_8).length > 131072)
      throw new IllegalArgumentException("Proposal snapshot too large");
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Preview preview(UUID org, UUID team, UUID mission, HumanIdentity actor, Input input) {
    authority.authorize(org, actor, true, false);
    return inspect(org, team, mission, actor, input, false);
  }

  private Receipt saved(
      UUID org, UUID team, UUID mission, HumanIdentity actor, UUID key, String digest) {
    var rows =
        jdbc.query(
            "select"
                + " command_sha256,snapshot_json,snapshot_sha256,response_json,response_sha256,team_id,mission_id"
                + " from mission_plan_proposal_receipts where organization_id=? and actor_id=? and"
                + " idempotency_key=?",
            (rs, n) ->
                new String[] {
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7)
                },
            org,
            actor.id(),
            key);
    if (rows.isEmpty()) return null;
    var r = rows.getFirst();
    if ((digest != null && !digest.equals(r[0]))
        || !r[0].matches("[0-9a-f]{64}")
        || !team.toString().equals(r[5])
        || !mission.toString().equals(r[6])
        || !hash(r[1]).equals(r[2])
        || !hash(r[3]).equals(r[4])) throw new Conflict();
    try {
      var receipt = json.readValue(r[3], Receipt.class);
      var snapshot = json.readValue(r[1], Preview.class);
      if (!org.equals(receipt.organizationId())
          || !team.equals(receipt.teamId())
          || !mission.equals(receipt.missionId())
          || !key.equals(receipt.idempotencyKey())
          || receipt.executionAuthorized()
          || snapshot.executionAuthorized()
          || !receipt.snapshotSha256().equals(r[2])
          || !receipt.reviewedFingerprint().equals(snapshot.fingerprint())
          || !org.equals(snapshot.organizationId())
          || !team.equals(snapshot.teamId())
          || !mission.equals(snapshot.missionId())
          || !receipt.sourceRunId().equals(snapshot.sourceRunId())
          || !receipt.origin().equals(snapshot.origin())
          || !receipt.intakeVersionBefore().equals(snapshot.intakeVersion())
          || Long.parseLong(receipt.intakeVersionAfter())
              != Math.addExact(Long.parseLong(receipt.intakeVersionBefore()), 1)
          || !receipt.taskIds().keySet().equals(snapshot.owners().keySet())
          || new java.util.HashSet<>(receipt.taskIds().values()).size() != receipt.taskIds().size())
        throw new Conflict();
      var mappings =
          jdbc.query(
              "select local_key,task_id from mission_plan_proposal_tasks where organization_id=?"
                  + " and team_id=? and mission_id=? order by local_key",
              (rs, n) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class)),
              org,
              team,
              mission);
      var expected = new TreeMap<String, UUID>();
      mappings.forEach(e -> expected.put(e.getKey(), e.getValue()));
      if (!expected.equals(receipt.taskIds())) throw new Conflict();
      return receipt;
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Applied receipt(UUID org, UUID team, UUID mission, HumanIdentity actor, UUID key) {
    authority.authorize(org, actor, true, false);
    if (key == null) throw new IllegalArgumentException("Receipt key required");
    var receipt = saved(org, team, mission, actor, key, null);
    if (receipt == null) throw new Conflict();
    String snapshot =
        jdbc.queryForObject(
            "select snapshot_json from mission_plan_proposal_receipts where organization_id=? and"
                + " actor_id=? and idempotency_key=?",
            String.class,
            org,
            actor.id(),
            key);
    try {
      return new Applied(receipt, json.readValue(snapshot, Preview.class));
    } catch (Exception e) {
      throw new Conflict();
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result apply(UUID org, UUID team, UUID mission, HumanIdentity actor, Command command) {
    authority.authorize(org, actor, true, true);
    if (command == null
        || command.idempotencyKey() == null
        || command.expectedFingerprint() == null
        || !command.expectedFingerprint().matches("[0-9a-f]{64}")
        || command.input() == null) throw new IllegalArgumentException("Invalid apply command");
    // Exact command includes unmodified JSON bytes and separately human-bound owners.
    var input = command.input();
    if (input.sourceRunId() == null
        || input.owners() == null
        || input.owners().size() > 20
        || input.owners().values().stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("Owners required");
    String digest =
        hash(
            encode(
                List.of(
                    "PLAN_IMPORT_COMMAND_V1",
                    org,
                    team,
                    mission,
                    actor.id(),
                    command.expectedFingerprint(),
                    input.sourceRunId(),
                    input.proposalJson() == null ? List.of() : List.of(input.proposalJson()),
                    new TreeMap<>(input.owners()))));
    var replay = saved(org, team, mission, actor, command.idempotencyKey(), digest);
    if (replay != null) return new Result(replay, true);
    Preview preview = inspect(org, team, mission, actor, input, true);
    if (!preview.fingerprint().equals(command.expectedFingerprint())) throw new Conflict();
    var ids = new TreeMap<String, UUID>();
    preview.proposal().tasks().forEach(task -> ids.put(task.key(), UUID.randomUUID()));
    for (var task : preview.proposal().tasks()) {
      jdbc.update(
          "insert into"
              + " tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority,owner_agent_id)"
              + " values(?,?,?,?,?,'DRAFT',?,?,?,?)",
          ids.get(task.key()),
          org,
          team,
          mission,
          task.title(),
          task.description(),
          task.acceptanceCriteria(),
          task.priority(),
          preview.owners().get(task.key()));
    }
    for (var task : preview.proposal().tasks())
      for (var dep : task.dependencies())
        jdbc.update(
            "insert into"
                + " task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)"
                + " values(?,?,?,?,?)",
            org,
            team,
            mission,
            ids.get(task.key()),
            ids.get(dep));
    long before = Long.parseLong(preview.intakeVersion());
    if (jdbc.update(
            "update missions set"
                + " task_intake_version=task_intake_version+1,updated_at=current_timestamp where"
                + " organization_id=? and team_id=? and id=? and status='CREATED' and"
                + " task_intake_paused=false and task_intake_version=?",
            org,
            team,
            mission,
            before)
        != 1) throw new Conflict();
    String snapshot = encode(preview), snapshotSha = hash(snapshot);
    if (snapshot.getBytes(StandardCharsets.UTF_8).length > 131072) throw new Conflict();
    var receipt =
        new Receipt(
            org,
            team,
            mission,
            command.idempotencyKey(),
            preview.sourceRunId(),
            preview.fingerprint(),
            snapshotSha,
            preview.origin(),
            preview.intakeVersion(),
            Long.toString(before + 1),
            Map.copyOf(ids),
            false);
    String response = encode(receipt);
    jdbc.update(
        "insert into"
            + " mission_plan_proposal_receipts(organization_id,actor_id,idempotency_key,team_id,mission_id,command_sha256,snapshot_json,snapshot_sha256,response_json,response_sha256,source_run_id)"
            + " values(?,?,?,?,?,?,?,?,?,?,?)",
        org,
        actor.id(),
        command.idempotencyKey(),
        team,
        mission,
        digest,
        snapshot,
        snapshotSha,
        response,
        hash(response),
        preview.sourceRunId());
    ids.forEach(
        (local, id) ->
            jdbc.update(
                "insert into"
                    + " mission_plan_proposal_tasks(organization_id,team_id,mission_id,local_key,task_id)"
                    + " values(?,?,?,?,?)",
                org,
                team,
                mission,
                local,
                id));
    jdbc.update(
        "insert into"
            + " audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at)"
            + " values(?,?,?,'HUMAN',?,'MISSION_PLAN_PROPOSAL_APPLIED',?,current_timestamp)",
        UUID.randomUUID(),
        org,
        mission,
        actor.id(),
        encode(
            Map.of(
                "snapshotSha256",
                snapshotSha,
                "taskCount",
                ids.size(),
                "sourceRunId",
                preview.sourceRunId(),
                "origin",
                preview.origin())));
    return new Result(receipt, false);
  }
}

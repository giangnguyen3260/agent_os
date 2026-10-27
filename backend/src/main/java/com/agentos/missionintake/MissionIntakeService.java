package com.agentos.missionintake;

import com.agentos.auth.HumanIdentity;
import com.agentos.missionintake.MissionIntakeDtos.Command;
import com.agentos.missionintake.MissionIntakeDtos.Context;
import com.agentos.missionintake.MissionIntakeDtos.Receipt;
import com.agentos.missionintake.MissionIntakeDtos.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionIntakeService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public MissionIntakeService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private record Scope(UUID organizationId, UUID teamId, UUID missionId) {}
  private record Snapshot(String teamName, String teamStatus, String missionTitle, String missionStatus, boolean paused, long version, String fingerprint) {}
  private record Stored(String digest, String response, UUID eventId) {}

  private static MissionIntakeFailure failure(int status, String code) {
    return new MissionIntakeFailure(status, code);
  }

  private void authorize(UUID org, HumanIdentity human, boolean lock) {
    String suffix = lock ? " for update" : "";
    if (human == null
        || jdbc.queryForList("select id from organizations where id=?" + suffix, org).isEmpty())
      throw failure(403, "FORBIDDEN");
    var accounts =
        jdbc.query(
            "select enabled,credential_version from human_accounts where id=?" + suffix,
            (rs, n) -> rs.getBoolean(1) && rs.getLong(2) == human.credentialVersion(),
            human.id());
    if (accounts.isEmpty() || !accounts.get(0)) throw failure(403, "FORBIDDEN");
    var roles =
        jdbc.query(
            "select role from organization_memberships where organization_id=? and account_id=?"
                + suffix,
            (rs, n) -> rs.getString(1),
            org,
            human.id());
    if (roles.isEmpty() || !"ADMIN".equals(roles.get(0))) throw failure(403, "FORBIDDEN");
  }

  private void resolve(Scope s, boolean lock) {
    String suffix = lock ? " for update" : "";
    if (jdbc.queryForList(
                "select id from teams where organization_id=? and id=?" + suffix,
                s.organizationId(),
                s.teamId())
            .isEmpty()
        || jdbc.queryForList(
                "select id from missions where organization_id=? and team_id=? and id=?" + suffix,
                s.organizationId(),
                s.teamId(),
                s.missionId())
            .isEmpty()
) throw failure(404, "NOT_FOUND");
  }

  private Snapshot snapshot(Scope s) {
    return jdbc.queryForObject("select g.name,g.status,m.title,m.status as mission_status,m.task_intake_paused,m.task_intake_version,m.updated_at from teams g join missions m on m.organization_id=g.organization_id and m.team_id=g.id where m.organization_id=? and m.team_id=? and m.id=?",
      (rs,n) -> {
        long version = rs.getLong("task_intake_version");
        String fingerprint = hash(Arrays.asList("MISSION_INTAKE_SNAPSHOT_V1",s.organizationId(),s.teamId(),s.missionId(),rs.getString("name"),rs.getString("status"),rs.getString("title"),rs.getString("mission_status"),rs.getBoolean("task_intake_paused"),Long.toString(version),rs.getObject("updated_at",OffsetDateTime.class).toInstant().toString()));
        return new Snapshot(rs.getString("name"),rs.getString("status"),rs.getString("title"),rs.getString("mission_status"),rs.getBoolean("task_intake_paused"),version,fingerprint);
      },s.organizationId(),s.teamId(),s.missionId());
  }

  static List<String> blockers(String team, String mission, long version) {
    var reasons = new ArrayList<String>();
    boolean teamKnown = team != null && Set.of("DRAFT","ACTIVE","ARCHIVED").contains(team);
    boolean missionKnown = mission != null && Set.of("CREATED","PLANNING","READY","RUNNING","WAITING_APPROVAL","REVIEW","COMPLETED","FAILED","CANCELLED").contains(mission);
    if ("ARCHIVED".equals(team)) reasons.add("TEAM_ARCHIVED");
    if (missionKnown && !Set.of("CREATED","READY","RUNNING","WAITING_APPROVAL").contains(mission)) reasons.add("MISSION_NOT_EDITABLE");
    if (version == Long.MAX_VALUE) reasons.add("REVISION_EXHAUSTED");
    if (!teamKnown || !missionKnown || version < 0) reasons.add("UNKNOWN_STATE");
    return List.copyOf(reasons);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Context context(UUID org, UUID team, UUID mission, HumanIdentity human) {
    authorize(org,human,false);
    Scope scope = new Scope(org,team,mission); resolve(scope,false);
    Snapshot s = snapshot(scope);
    var blockers = blockers(s.teamStatus(),s.missionStatus(),s.version());
    return new Context(org,team,mission,now(),s.teamName(),s.missionTitle(),s.teamStatus(),s.missionStatus(),s.paused(),Long.toString(s.version()),s.fingerprint(),blockers.isEmpty(),blockers);
  }

  private Stored stored(Scope s, HumanIdentity human, UUID key) {
    var rows =
        jdbc.query(
            "select body_sha256,original_response,event_id from mission_intake_receipts where organization_id=?"
                + " and team_id=? and mission_id=? and actor_id=? and"
                + " idempotency_key=?",
            (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getObject(3,UUID.class)),
            s.organizationId(),
            s.teamId(),
            s.missionId(),
            human.id(),
            key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Receipt decode(Stored saved, Scope scope) {
    try {
      if (saved.digest() == null || !saved.digest().matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      var root = MissionIntakeWire.object(saved.response(),Set.of("organizationId","teamId","missionId","eventId","versionBefore","versionAfter","pausedBefore","pausedAfter","committedAt"));
      for (String field : List.of("organizationId","teamId","missionId","eventId","versionBefore","versionAfter","committedAt")) if (!root.get(field).isTextual()) throw new IllegalArgumentException();
      if (!root.get("pausedBefore").isBoolean() || !root.get("pausedAfter").isBoolean()) throw new IllegalArgumentException();
      for (String field : List.of("organizationId","teamId","missionId","eventId")) MissionIntakeWire.uuid(root.get(field).textValue());
      Receipt r = MissionIntakeWire.JSON.treeToValue(root,Receipt.class);
      if (!r.eventId().equals(saved.eventId())) throw new IllegalArgumentException();
      long before = MissionIntakeWire.version(r.versionBefore()), after = MissionIntakeWire.version(r.versionAfter());
      if (!scope.organizationId().equals(r.organizationId()) || !scope.teamId().equals(r.teamId()) || !scope.missionId().equals(r.missionId()) || before == Long.MAX_VALUE || after != before+1 || r.pausedBefore()==r.pausedAfter()) throw new IllegalArgumentException();
      MissionIntakeWire.uuid(r.eventId().toString());
      java.time.Instant.parse(r.committedAt());
      return r;
    } catch (Exception ex) { throw failure(500,"MISSION_INTAKE_UNAVAILABLE"); }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Receipt receipt(UUID org, UUID team, UUID mission, HumanIdentity human, UUID key) {
    authorize(org,human,false);
    Scope scope = new Scope(org,team,mission); resolve(scope,false);
    Stored saved = stored(scope,human,key);
    return saved == null ? null : decode(saved,scope);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result update(UUID org, UUID team, UUID mission, HumanIdentity human, UUID key, Command command) {
    authorize(org,human,true);
    Scope scope = new Scope(org,team,mission); resolve(scope,true);
    authorize(org,human,false);
    String digest = hash(List.of("MISSION_INTAKE_COMMAND_V1",scope,human.id(),command));
    Stored saved = stored(scope,human,key);
    if (saved != null) {
      Receipt receipt = decode(saved,scope);
      if (!digest.equals(saved.digest())) throw failure(409,"IDEMPOTENCY_CONFLICT");
      return new Result(receipt,true);
    }
    Snapshot source = snapshot(scope);
    if (!Long.toString(source.version()).equals(command.expectedVersion()) || !source.fingerprint().equals(command.expectedFingerprint())) throw failure(409,"MISSION_INTAKE_CONFLICT");
    if (!blockers(source.teamStatus(),source.missionStatus(),source.version()).isEmpty()) throw failure(409,"MISSION_INTAKE_BLOCKED");
    if (source.paused()==command.paused()) throw failure(409,"MISSION_INTAKE_NO_CHANGE");
    long version = source.version()+1;
    String time = now(); OffsetDateTime timestamp = OffsetDateTime.parse(time); UUID event = UUID.randomUUID();
    if (jdbc.update("update missions set task_intake_paused=?,task_intake_version=?,updated_at=? where organization_id=? and team_id=? and id=? and task_intake_version=?",command.paused(),version,timestamp,org,team,mission,source.version()) != 1) throw failure(409,"MISSION_INTAKE_CONFLICT");
    Receipt receipt = new Receipt(org,team,mission,event,Long.toString(source.version()),Long.toString(version),source.paused(),command.paused(),time);
    jdbc.update("insert into mission_intake_receipts(organization_id,team_id,mission_id,actor_id,idempotency_key,event_id,body_sha256,original_response,created_at) values (?,?,?,?,?,?,?,?,?)",org,team,mission,human.id(),key,event,digest,encode(receipt),timestamp);
    jdbc.update("insert into audit_events(id,organization_id,mission_id,actor_type,actor_id,event_type,payload,created_at) values (?,?,?,'HUMAN',?,'MISSION_TASK_INTAKE_UPDATED',?,?)",UUID.randomUUID(),org,mission,human.id(),encode(Map.of("eventId",event,"versionBefore",receipt.versionBefore(),"versionAfter",receipt.versionAfter(),"pausedBefore",receipt.pausedBefore(),"pausedAfter",receipt.pausedAfter())),timestamp);
    return new Result(receipt,false);
  }

  private String now() {
    return new DateTimeFormatterBuilder()
        .appendInstant(6)
        .toFormatter()
        .format(jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class).toInstant());
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}

package com.agentos.orchestration.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.model.application.ModelMessage;
import com.agentos.model.application.ModelRequest;
import com.agentos.model.infrastructure.OpenAiTextTransport;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly assembled, opt-in text-only execution flow. No credentials are accepted from callers.
 */
public final class OpenAiExecutionFlow {
  public record Settings(
      String model, int outputTokens, long inputRate, long outputRate, long maximumMicrousd) {
    public Settings {
      if (model == null
          || !model.matches("[A-Za-z0-9._:-]{1,256}")
          || outputTokens < 1
          || outputTokens > 4096
          || inputRate <= 0
          || outputRate <= 0
          || maximumMicrousd <= 0)
        throw new IllegalArgumentException("Invalid OpenAI execution limits");
      if (cost(17408, outputTokens, inputRate, outputRate) > maximumMicrousd)
        throw new IllegalArgumentException("Execution cap is below the required reservation");
    }
  }

  public record Preflight(
      UUID runId,
      String revision,
      UUID connectionId,
      UUID ownerAgentId,
      String requestPreview,
      String model,
      int maxOutputTokens,
      String maximumMicrousd,
      String fingerprint,
      String dependencyContextSha256,
      int dependencyCount) {
    @Override
    public String toString() {
      return "ExecutionPreflight[redacted]";
    }
  }

  public record Started(UUID dispatchId, UUID runId, String sourceRevision) {}

  private record Prepared(
      UUID owner,
      UUID connection,
      long credentialRevision,
      String model,
      byte[] body,
      String sha,
      ExecutionDependencyContextStore.Captured context) {}

  private record Job(
      UUID org,
      UUID dispatch,
      UUID run,
      UUID actor,
      long actorVersion,
      UUID connection,
      long credentialRevision,
      String model,
      byte[] body,
      String sha,
      int maxOutput,
      long maximum,
      long inputRate,
      long outputRate) {
    @Override
    public String toString() {
      return "ExecutionJob[redacted]";
    }
  }

  @org.springframework.beans.factory.annotation.Value(
      "${agentos.execution.dependency-context.enabled:false}")
  private boolean dependencyContextEnabled;

  private ApprovedWorkerTextTool approvedTools;

  void approvedTools(ApprovedWorkerTextTool tools) {
    this.approvedTools = tools;
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ExecutionStartService starts;
  private final ExecutionDispatchClaimService dispatch;
  private final ExecutionClaimStore claims;
  private final ExecutionBudgetStore budgets;
  private final ExecutionModelAuthorizationStore models;
  private final ExecutionAttemptReconciliationStore reconciliations;
  private final ExecutionOutputContentStore outputs;
  private final ExecutionRunReviewStore reviews;
  private final ExecutionReviewDecisionStore authority;
  private final ProviderConnectionService connections;
  private final CredentialBoundary credentials;
  private final OpenAiTextTransport transport;
  private final Settings settings;
  private final Clock clock;
  private final UUID worker = UUID.randomUUID();

  OpenAiExecutionFlow(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ExecutionStartService starts,
      ExecutionDispatchClaimService dispatch,
      ExecutionClaimStore claims,
      ExecutionBudgetStore budgets,
      ExecutionModelAuthorizationStore models,
      ExecutionAttemptReconciliationStore reconciliations,
      ExecutionOutputContentStore outputs,
      ExecutionRunReviewStore reviews,
      ExecutionReviewDecisionStore authority,
      ProviderConnectionService connections,
      CredentialBoundary credentials,
      OpenAiTextTransport transport,
      Settings settings,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
    this.starts = starts;
    this.dispatch = dispatch;
    this.claims = claims;
    this.budgets = budgets;
    this.models = models;
    this.reconciliations = reconciliations;
    this.outputs = outputs;
    this.reviews = reviews;
    this.authority = authority;
    this.connections = connections;
    this.credentials = credentials;
    this.transport = transport;
    this.settings = settings;
    this.clock = clock;
    tx.setTimeout(10);
  }

  public Preflight preflight(UUID org, UUID run, HumanIdentity actor) {
    return tx.execute(
        s -> {
          authority.requireAdmin(org, actor, true);
          long rev = revision(org, run);
          starts.requireEligible(org, run, rev);
          if (!jdbc.queryForList(
                  "select id from execution_start_dispatch where run_id=? and"
                      + " source_run_revision=?",
                  UUID.class,
                  run,
                  rev)
              .isEmpty()) throw unavailable();
          var p = prepare(org, run);
          return new Preflight(
              run,
              Long.toString(rev),
              p.connection(),
              p.owner(),
              new String(p.body(), StandardCharsets.UTF_8),
              p.model(),
              settings.outputTokens(),
              Long.toString(reservation()),
              fingerprint(rev, p),
              p.context() == null ? null : p.context().sha256(),
              p.context() == null ? 0 : p.context().count());
        });
  }

  public Started start(
      UUID org, UUID run, long revision, UUID key, String fingerprint, HumanIdentity actor) {
    return tx.execute(
        s -> {
          // Historical replay must precede mutable configuration checks, but follow fresh
          // authority.
          authority.requireAdmin(org, actor, true);
          var old =
              jdbc.queryForList(
                  "select id from execution_start_dispatch where organization_id=? and actor_id=?"
                      + " and idempotency_key=?",
                  UUID.class,
                  org,
                  actor.id(),
                  key);
          if (!old.isEmpty()) {
            var hashes =
                jdbc.queryForList(
                    "select preflight_sha256 from execution_openai_requests where dispatch_id=?",
                    String.class,
                    old.getFirst());
            if (hashes.size() != 1 || !hashes.getFirst().equals(fingerprint)) throw unavailable();
            String storedSha =
                jdbc.queryForObject(
                    "select request_sha256 from execution_openai_requests where dispatch_id=?",
                    String.class,
                    old.getFirst());
            ExecutionDependencyContextStore.storedDigest(jdbc, org, run, old.getFirst(), storedSha);
            var receipt = starts.start(org, run, revision, key, actor);
            return new Started(receipt.dispatchId(), run, Long.toString(revision));
          }
          var p = prepare(org, run);
          if (fingerprint == null || !fingerprint.equals(fingerprint(revision, p)))
            throw unavailable();
          var receipt = starts.start(org, run, revision, key, actor);
          jdbc.update(
              """
insert into execution_openai_requests(dispatch_id,connection_id,credential_revision,model_id,request_body,request_sha256,preflight_sha256,max_output_tokens,maximum_microusd,input_rate,output_rate)
values(?,?,?,?,?,?,?,?,?,?,?)
""",
              receipt.dispatchId(),
              p.connection(),
              p.credentialRevision(),
              p.model(),
              p.body(),
              p.sha(),
              fingerprint,
              settings.outputTokens(),
              reservation(),
              settings.inputRate(),
              settings.outputRate());
          new ExecutionDependencyContextStore(jdbc, dependencyContextEnabled)
              .save(org, run, receipt.dispatchId(), p.context(), p.sha());
          jdbc.update(
              """
insert into audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)
select ?,organization_id,mission_id,task_id,'HUMAN',?,'EXECUTION_START_REQUESTED',?,current_timestamp
from execution_runs where organization_id=? and id=?
""",
              UUID.randomUUID(),
              actor.id(),
              "{\"dispatchId\":\"" + receipt.dispatchId() + "\"}",
              org,
              run);
          return new Started(receipt.dispatchId(), run, Long.toString(revision));
        });
  }

  public record TaskPreflight(
      UUID taskId,
      String revision,
      UUID connectionId,
      UUID ownerAgentId,
      String requestPreview,
      String model,
      int maxOutputTokens,
      String maximumMicrousd,
      String fingerprint) {
    @Override
    public String toString() {
      return "ExecutionPreflight[redacted]";
    }
  }

  private record TaskPrepared(
      UUID owner, int edit, int ownership, int dependency, Prepared model, String fingerprint) {}

  public TaskPreflight taskPreflight(
      UUID org, UUID team, UUID mission, UUID task, HumanIdentity actor) {
    return tx.execute(
        s -> {
          authority.requireAdmin(org, actor, true);
          var p = prepareTask(org, team, mission, task);
          return new TaskPreflight(
              task,
              "0",
              p.model().connection(),
              p.owner(),
              new String(p.model().body(), StandardCharsets.UTF_8),
              p.model().model(),
              settings.outputTokens(),
              Long.toString(reservation()),
              p.fingerprint());
        });
  }

  public Started startTask(
      UUID org,
      UUID team,
      UUID mission,
      UUID task,
      UUID key,
      String fingerprint,
      HumanIdentity actor) {
    return tx.execute(
        s -> {
          authority.requireAdmin(org, actor, true);
          var existing =
              jdbc.query(
                  """
select d.id,r.id,d.source_run_revision,q.preflight_sha256,r.team_id,r.mission_id,r.task_id
from execution_start_dispatch d join execution_runs r on r.id=d.run_id
join execution_openai_requests q on q.dispatch_id=d.id
where d.organization_id=? and d.actor_id=? and d.idempotency_key=?
""",
                  (rs, n) ->
                      new Object[] {
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getLong(3),
                        rs.getString(4),
                        rs.getObject(5, UUID.class),
                        rs.getObject(6, UUID.class),
                        rs.getObject(7, UUID.class)
                      },
                  org,
                  actor.id(),
                  key);
          if (!existing.isEmpty()) {
            Object[] row = existing.getFirst();
            if (!team.equals(row[4])
                || !mission.equals(row[5])
                || !task.equals(row[6])
                || !java.util.Objects.equals(fingerprint, row[3])) throw unavailable();
            return new Started((UUID) row[0], (UUID) row[1], Long.toString((Long) row[2]));
          }
          var p = prepareTask(org, team, mission, task);
          if (!p.fingerprint().equals(fingerprint)) throw unavailable();
          // Explicit single-task acceptance, never an AI-generated plan or a hidden GET-side write.
          long plan =
              jdbc.queryForObject(
                  "select coalesce(max(plan_revision),0)+1 from accepted_mission_plans where"
                      + " organization_id=? and team_id=? and mission_id=?",
                  Long.class,
                  org,
                  team,
                  mission);
          jdbc.update(
              "insert into"
                  + " accepted_mission_plans(organization_id,team_id,mission_id,plan_revision,accepted_by)"
                  + " values(?,?,?,?,?)",
              org,
              team,
              mission,
              plan,
              actor.id());
          jdbc.update(
              "insert into"
                  + " accepted_mission_plan_tasks(organization_id,team_id,mission_id,plan_revision,task_id,owner_agent_id,task_edit_revision,task_owner_revision,dependency_revision)"
                  + " values(?,?,?,?,?,?,?,?,?)",
              org,
              team,
              mission,
              plan,
              task,
              p.owner(),
              p.edit(),
              p.ownership(),
              p.dependency());
          if (jdbc.queryForList(
                  "select revision from mission_execution_controls where organization_id=? and"
                      + " team_id=? and mission_id=?",
                  Long.class,
                  org,
                  team,
                  mission)
              .isEmpty())
            new MissionExecutionControlStore(jdbc)
                .initialize(new MissionExecutionControlStore.Scope(org, team, mission));
          UUID run = UUID.randomUUID();
          jdbc.update(
              "insert into"
                  + " execution_runs(id,organization_id,team_id,mission_id,task_id,plan_revision,status,revision,max_reworks)"
                  + " values(?,?,?,?,?,?,'CREATED',0,1)",
              run,
              org,
              team,
              mission,
              task,
              plan);
          // Reuse the standard Run admission and body storage; persist the exact task preflight for
          // replay.
          var prepared = prepare(org, run);
          var started = start(org, run, 0, key, fingerprint(0, prepared), actor);
          jdbc.update(
              "update execution_openai_requests set preflight_sha256=? where dispatch_id=?",
              fingerprint,
              started.dispatchId());
          return started;
        });
  }

  private TaskPrepared prepareTask(UUID org, UUID team, UUID mission, UUID task) {
    var rows =
        jdbc.query(
            """
select t.owner_agent_id,t.task_edit_revision,t.task_owner_revision,t.dependency_revision,
  b.connection_id,c.credential_revision,b.model_id,t.title,t.description,t.acceptance_criteria,g.responsibilities,g.output_format
from tasks t join missions m on m.organization_id=t.organization_id and m.team_id=t.team_id and m.id=t.mission_id
join teams tm on tm.organization_id=t.organization_id and tm.id=t.team_id
join agents a on a.organization_id=t.organization_id and a.team_id=t.team_id and a.id=t.owner_agent_id
join agent_draft_config g on g.agent_id=a.id
join agent_model_bindings b on b.organization_id=t.organization_id and b.agent_id=a.id
join provider_connections c on c.organization_id=t.organization_id and c.id=b.connection_id and c.provider='OPENAI' and c.state='ENABLED'
where exists(select 1 from organizations o where o.id=t.organization_id and o.status='ACTIVE') and t.organization_id=? and t.team_id=? and t.mission_id=? and t.id=? and t.status='READY'
  and m.status='RUNNING' and tm.status='ACTIVE' and a.status='ACTIVE'
  and not exists(select 1 from task_dependencies d where d.organization_id=t.organization_id and d.team_id=t.team_id and d.mission_id=t.mission_id and d.task_id=t.id)
  and not exists(select 1 from execution_runs r where r.organization_id=t.organization_id and r.task_id=t.id)
  and not exists(select 1 from mission_plan_selections p where p.organization_id=t.organization_id and p.team_id=t.team_id and p.mission_id=t.mission_id)
  and not exists(select 1 from mission_graph_admissions p where p.organization_id=t.organization_id and p.team_id=t.team_id and p.mission_id=t.mission_id)
  and not exists(select 1 from mission_execution_controls x where x.organization_id=t.organization_id and x.team_id=t.team_id and x.mission_id=t.mission_id and x.state<>'OPEN')
""",
            (rs, n) -> {
              UUID owner = rs.getObject(1, UUID.class), connection = rs.getObject(5, UUID.class);
              String model = rs.getString(7);
              if (!settings.model().equals(model)
                  || !models.configurationGranted(org, owner, connection, model))
                throw unavailable();
              requireBudget(org, team, mission, owner);
              byte[] body =
                  OpenAiTextTransport.prepare(
                      new ModelRequest(
                          org,
                          mission,
                          task,
                          null,
                          owner,
                          connection,
                          model,
                          List.of(
                              new ModelMessage(
                                  "system",
                                  rs.getString(11) + "\nOutput format: " + rs.getString(12)),
                              new ModelMessage(
                                  "user",
                                  rs.getString(8)
                                      + "\n"
                                      + java.util.Objects.toString(rs.getString(9), "")
                                      + "\nAcceptance criteria: "
                                      + java.util.Objects.toString(rs.getString(10), ""))),
                          settings.outputTokens()));
              var p = new Prepared(owner, connection, rs.getLong(6), model, body, sha(body), null);
              String hash =
                  sha(
                      (org
                              + ":"
                              + team
                              + ":"
                              + mission
                              + ":"
                              + task
                              + ":"
                              + owner
                              + ":"
                              + rs.getInt(2)
                              + ":"
                              + rs.getInt(3)
                              + ":"
                              + rs.getInt(4)
                              + ":"
                              + fingerprint(0, p))
                          .getBytes(StandardCharsets.UTF_8));
              return new TaskPrepared(owner, rs.getInt(2), rs.getInt(3), rs.getInt(4), p, hash);
            },
            org,
            team,
            mission,
            task);
    if (rows.size() != 1) throw unavailable();
    return rows.getFirst();
  }

  private Prepared prepare(UUID org, UUID run) {
    var context =
        new ExecutionDependencyContextStore(jdbc, dependencyContextEnabled).capture(org, run);
    var rows =
        jdbc.query(
            """
select b.connection_id,c.credential_revision,b.model_id,p.owner_agent_id,r.mission_id,r.task_id,
  t.title,t.description,t.acceptance_criteria,g.responsibilities,g.output_format
from execution_runs r
join accepted_mission_plan_tasks p on p.organization_id=r.organization_id and p.team_id=r.team_id and p.mission_id=r.mission_id and p.plan_revision=r.plan_revision and p.task_id=r.task_id
join tasks t on t.id=r.task_id
join agent_model_bindings b on b.organization_id=r.organization_id and b.agent_id=p.owner_agent_id
join provider_connections c on c.organization_id=r.organization_id and c.id=b.connection_id and c.state='ENABLED' and c.provider='OPENAI'
join agent_draft_config g on g.agent_id=p.owner_agent_id
where r.organization_id=? and r.id=?
""",
            (rs, n) -> {
              var request =
                  new ModelRequest(
                      org,
                      rs.getObject(5, UUID.class),
                      rs.getObject(6, UUID.class),
                      run,
                      rs.getObject(4, UUID.class),
                      rs.getObject(1, UUID.class),
                      rs.getString(3),
                      messages(
                          rs.getString(10),
                          rs.getString(11),
                          rs.getString(7),
                          rs.getString(8),
                          rs.getString(9),
                          context),
                      settings.outputTokens());
              if (!settings.model().equals(request.model())) throw unavailable();
              if (!models.configurationGranted(
                  org, request.agentId(), request.providerId(), request.model()))
                throw unavailable();
              requireBudget(
                  org,
                  jdbc.queryForObject(
                      "select team_id from execution_runs where id=?", UUID.class, run),
                  request.missionId(),
                  request.agentId());
              byte[] body = OpenAiTextTransport.prepare(request);
              return new Prepared(
                  request.agentId(),
                  request.providerId(),
                  rs.getLong(2),
                  request.model(),
                  body,
                  sha(body),
                  context);
            },
            org,
            run);
    if (rows.size() != 1) throw unavailable();
    return rows.getFirst();
  }

  private List<ModelMessage> messages(
      String responsibilities,
      String format,
      String title,
      String description,
      String criteria,
      ExecutionDependencyContextStore.Captured context) {
    var messages = new java.util.ArrayList<ModelMessage>();
    String system = responsibilities + "\nOutput format: " + format;
    if (context != null && context.count() > 0)
      system += "\n" + ExecutionDependencyContextStore.INSTRUCTION;
    messages.add(new ModelMessage("system", system));
    if (context != null && context.count() > 0)
      messages.add(new ModelMessage("user", context.json()));
    messages.add(
        new ModelMessage(
            "user",
            title
                + "\n"
                + java.util.Objects.toString(description, "")
                + "\nAcceptance criteria: "
                + java.util.Objects.toString(criteria, "")));
    return List.copyOf(messages);
  }

  private void requireBudget(UUID org, UUID team, UUID mission, UUID owner) {
    var rows =
        jdbc.query(
            """
select b.scope,b.limit_microusd,b.held_microusd,b.spent_microusd,l.max_concurrent,l.max_admissions,l.window_seconds
from execution_budget_buckets b left join execution_capacity_limits l on l.organization_id=b.organization_id and l.scope=b.scope and l.subject_id=b.subject_id
where b.organization_id=? and b.opens_at<=current_timestamp and b.closes_at>current_timestamp
  and b.subject_id=case b.scope when 'ORGANIZATION' then ? when 'TEAM' then ? when 'AGENT' then ? when 'MISSION' then ? end
""",
            (rs, n) ->
                new Object[] {
                  rs.getString(1),
                  rs.getLong(2),
                  rs.getLong(3),
                  rs.getLong(4),
                  rs.getInt(5),
                  rs.getInt(6),
                  rs.getInt(7)
                },
            org,
            org,
            team,
            owner,
            mission);
    if (rows.size() != 4 || rows.stream().map(r -> r[0]).distinct().count() != 4)
      throw unavailable();
    for (Object[] row : rows) {
      var available =
          BigInteger.valueOf((Long) row[1])
              .subtract(BigInteger.valueOf((Long) row[2]))
              .subtract(BigInteger.valueOf((Long) row[3]));
      if (available.compareTo(BigInteger.valueOf(reservation())) < 0
          || (Integer) row[4] < 1
          || (Integer) row[5] < 1
          || (Integer) row[6] < 1) throw unavailable();
    }
  }

  private long revision(UUID org, UUID run) {
    var rows =
        jdbc.queryForList(
            "select revision from execution_runs where organization_id=? and id=?",
            Long.class,
            org,
            run);
    if (rows.size() != 1) throw unavailable();
    return rows.getFirst();
  }

  private long reservation() {
    return cost(17408, settings.outputTokens(), settings.inputRate(), settings.outputRate());
  }

  private String fingerprint(long revision, Prepared p) {
    return sha(
        (revision
                + ":"
                + p.connection()
                + ":"
                + p.credentialRevision()
                + ":"
                + p.sha()
                + ":"
                + settings.inputRate()
                + ":"
                + settings.outputRate()
                + ":"
                + reservation()
                + (p.context() == null ? "" : ":" + p.context().sha256()))
            .getBytes(StandardCharsets.UTF_8));
  }

  public synchronized void poll() {
    List<Job> jobs =
        jdbc.query(
            """
select d.organization_id,d.id,d.run_id,d.actor_id,d.actor_credential_version,q.connection_id,q.credential_revision,q.model_id,q.request_body,q.request_sha256,q.max_output_tokens,q.maximum_microusd,q.input_rate,q.output_rate
from execution_start_dispatch d join execution_openai_requests q on q.dispatch_id=d.id
join execution_runs r on r.id=d.run_id
join mission_execution_controls c on c.organization_id=r.organization_id and c.team_id=r.team_id and c.mission_id=r.mission_id and c.state='OPEN'
where d.state='QUEUED' order by d.created_at,d.id limit 1
""",
            (rs, n) ->
                new Job(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class),
                    rs.getLong(5),
                    rs.getObject(6, UUID.class),
                    rs.getLong(7),
                    rs.getString(8),
                    rs.getBytes(9),
                    rs.getString(10),
                    rs.getInt(11),
                    rs.getLong(12),
                    rs.getLong(13),
                    rs.getLong(14)));
    if (jobs.isEmpty()) return;
    Job job = jobs.getFirst();
    if (!settings.model().equals(job.model())
        || job.inputRate() != settings.inputRate()
        || job.outputRate() != settings.outputRate()
        || job.maxOutput() > settings.outputTokens()
        || job.maximum() > settings.maximumMicrousd()) {
      rejectQueued(job);
      return;
    }
    ExecutionClaimStore.Token token;
    try {
      var now = OffsetDateTime.now(clock);
      token =
          tx.execute(
              s ->
                  dispatch.claim(
                      job.org(),
                      job.dispatch(),
                      worker,
                      now.plusSeconds(90),
                      now.plusSeconds(120)));
    } catch (RuntimeException failure) {
      rejectQueued(job);
      return;
    }
    boolean[] handedOff = {false};
    try {
      if (approvedTools != null) approvedTools.execute(token);
      else if (Boolean.TRUE.equals(
          tx.execute(
              s ->
                  jdbc.queryForObject(
                      "select count(*)>0 from worker_text_tool_approvals where run_id=? and"
                          + " source_revision=?",
                      Boolean.class,
                      job.run(),
                      jdbc.queryForObject(
                          "select source_run_revision from execution_start_dispatch where id=?",
                          Long.class,
                          job.dispatch()))))) throw unavailable();
      var actor = new HumanIdentity(job.actor(), "", "", job.actorVersion());
      var connection = connections.get(actor, job.org(), job.connection());
      if (connection == null
          || connection.provider() != CredentialBoundary.Provider.OPENAI
          || connection.credentialRevision() != job.credentialRevision()
          || !"ENABLED".equals(connection.state())) throw unavailable();
      tx.executeWithoutResult(
          s -> {
            authority.requireAdmin(job.org(), actor, true);
            models.authorize(
                token,
                new ExecutionModelAuthorizationStore.Request(
                    job.dispatch(), job.connection(), job.model(), job.sha(), job.maximum()));
          });
      OpenAiTextTransport.Result[] result = {null};
      credentials.use(
          new CredentialBoundary.Scope(
              job.org(),
              job.connection(),
              CredentialBoundary.Provider.OPENAI,
              job.credentialRevision()),
          connection.metadata(),
          key -> {
            var handoff =
                tx.execute(
                    s -> {
                      authority.requireAdmin(job.org(), actor, true);
                      return models.handoff(
                          token, job.dispatch(), job.connection(), job.model(), job.body());
                    });
            if (handoff == null) throw unavailable();
            handedOff[0] = true;
            byte[] exact = new byte[handoff.body().remaining()];
            handoff.body().get(exact);
            result[0] =
                transport.execute(exact, key, clock.instant().plusSeconds(30), job.maxOutput());
          });
      if (result[0] == null) throw unavailable();
      finish(job, token, result[0]);
    } catch (RuntimeException failure) {
      uncertain(job, token, handedOff[0]);
    }
  }

  private void rejectQueued(Job job) {
    try {
      tx.executeWithoutResult(
          s -> {
            jdbc.queryForList(
                "select id from organizations where id=? for update", UUID.class, job.org());
            if (jdbc.queryForObject(
                    "select count(*) from mission_execution_controls c join execution_runs r on"
                        + " r.organization_id=c.organization_id and r.team_id=c.team_id and"
                        + " r.mission_id=c.mission_id where r.organization_id=? and r.id=? and"
                        + " c.state='PAUSE_REQUESTED'",
                    Long.class,
                    job.org(),
                    job.run())
                > 0) return;
            if (jdbc.update(
                    "update execution_start_dispatch set state='FINISHED' where id=? and"
                        + " state='QUEUED'",
                    job.dispatch())
                == 1)
              jdbc.update(
                  "update execution_runs set revision=revision+1 where id=? and status='CREATED'"
                      + " and revision=(select source_run_revision from execution_start_dispatch"
                      + " where id=?) and revision<9223372036854775807",
                  job.run(),
                  job.dispatch());
          });
    } catch (RuntimeException ignored) {
      /* A later poll can safely retry queue admission, never provider transport. */
    }
  }

  private void finish(Job job, ExecutionClaimStore.Token token, OpenAiTextTransport.Result result) {
    tx.executeWithoutResult(
        s -> {
          claims.requireCurrent(token);
          long actual =
              cost(result.inputTokens(), result.outputTokens(), job.inputRate(), job.outputRate());
          if (actual > job.maximum()) throw unavailable();
          budgets.settleModelUsage(
              job.org(),
              job.run(),
              job.dispatch(),
              job.connection(),
              result.requestId(),
              result.inputTokens(),
              result.outputTokens(),
              actual);
          block(job, token, true);
          String digest = sha(result.content().getBytes(StandardCharsets.UTF_8));
          reconciliations.recordVerified(
              job.org(),
              job.run(),
              token.attempt(),
              ExecutionAttemptReconciliationStore.Outcome.SUCCEEDED,
              result.evidenceSha256(),
              digest);
          outputs.recordVerified(job.org(), job.run(), token.attempt(), result.content());
          reviews.submitReconciled(
              job.org(), job.run(), token.attempt(), revision(job.org(), job.run()), digest);
          jdbc.update(
              "update execution_start_dispatch set state='FINISHED' where id=?", job.dispatch());
        });
  }

  private void uncertain(Job job, ExecutionClaimStore.Token token, boolean sent) {
    try {
      tx.executeWithoutResult(
          s -> {
            claims.requireCurrent(token);
            if (!sent
                && new ExecutionPausedDispatchStore(jdbc, claims, budgets)
                    .releaseUnsent(token, job.dispatch())) return;
            if (sent) budgets.markUnknown(job.org(), job.run(), job.dispatch());
            else if (!jdbc.queryForList(
                    "select action_id from execution_budget_reservations where run_id=? and"
                        + " action_id=?",
                    UUID.class,
                    job.run(),
                    job.dispatch())
                .isEmpty()) budgets.settleVerified(job.org(), job.run(), job.dispatch(), 0);
            block(job, token, sent);
            jdbc.update(
                "update execution_start_dispatch set state=? where id=?",
                sent ? "UNKNOWN" : "FINISHED",
                job.dispatch());
          });
    } catch (RuntimeException ignored) {
      /* Existing lease expiry reconciles the visible unresolved attempt after crash/deadline. */
    }
  }

  private void block(Job job, ExecutionClaimStore.Token token, boolean unknown) {
    claims.invalidate(token);
    jdbc.update(
        "update execution_attempts set status=?,outcome_slot=case when ? then 0 else attempt_number"
            + " end where id=?",
        unknown ? "UNKNOWN" : "FAILED",
        unknown,
        token.attempt());
    jdbc.update(
        "update execution_runs set status='BLOCKED',wait_reason=null,revision=revision+1 where"
            + " id=?",
        job.run());
    jdbc.update(
        "update tasks set"
            + " status='BLOCKED',pending_reason=?,waiting_since=current_timestamp,resolver='Runtime',updated_at=current_timestamp"
            + " where id=(select task_id from execution_runs where id=?)",
        unknown ? "OUTCOME_UNKNOWN" : "EXECUTION_UNAVAILABLE",
        job.run());
  }

  static long cost(long input, long output, long inputRate, long outputRate) {
    if (input < 0 || output < 0 || inputRate <= 0 || outputRate <= 0) throw unavailable();
    return BigInteger.valueOf(input)
        .multiply(BigInteger.valueOf(inputRate))
        .add(BigInteger.valueOf(output).multiply(BigInteger.valueOf(outputRate)))
        .add(BigInteger.valueOf(999999))
        .divide(BigInteger.valueOf(1000000))
        .longValueExact();
  }

  static String sha(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("OpenAI execution is unavailable.");
  }
}

package com.agentos.agent.application;

import com.agentos.agent.api.dto.AgentActivationRequest;
import com.agentos.agent.api.dto.AgentActivationReview;
import com.agentos.agent.api.dto.AgentActivationReview.Check;
import com.agentos.agent.api.dto.AgentActivationReview.Status;
import com.agentos.agent.api.dto.AgentConfigurationResponse;
import com.agentos.auth.HumanIdentity;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentActivationService {
  private final AgentDraftService drafts;
  private final AgentConfigurationService configurations;
  private final JdbcTemplate jdbc;

  public AgentActivationService(
      AgentDraftService drafts, AgentConfigurationService configurations, JdbcTemplate jdbc) {
    this.drafts = drafts;
    this.configurations = configurations;
    this.jdbc = jdbc;
  }

  public static class Blocked extends RuntimeException {
    private final AgentActivationReview review;

    public Blocked(AgentActivationReview review) {
      super("Activation is blocked");
      this.review = review;
    }

    public AgentActivationReview review() {
      return review;
    }
  }

  private record Team(String status, Integer concurrency, BigDecimal budget, String currency) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AgentActivationReview get(UUID org, HumanIdentity human, UUID agent) {
    drafts.authorize(org, human, true, false);
    return review(org, human, agent);
  }

  @Transactional
  public void activate(UUID org, HumanIdentity human, UUID agent, AgentActivationRequest request) {
    drafts.authorize(org, human, true, true);
    var targets =
        jdbc.query(
            "select id from agents where organization_id=? and id=? for update",
            (rs, n) -> rs.getObject(1, UUID.class),
            org,
            agent);
    if (targets.isEmpty()) throw new EntityNotFoundException("Agent not found");
    if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0)
      throw new IllegalArgumentException(
          "expectedRevision must be an integer from 0 to 2147483647");
    var review = review(org, human, agent);
    if (request.expectedRevision() != review.revision())
      throw new AgentDraftService.Conflict("CONFIGURATION_CONFLICT");
    // No activation path exists until authoritative authorization and execution integrations exist.
    throw new Blocked(review);
  }

  private boolean text(String value, int max) {
    return value != null && !value.strip().isEmpty() && value.strip().length() <= max;
  }

  private boolean bounded(Integer value, int max) {
    return value != null && value >= 1 && value <= max;
  }

  private boolean budget(BigDecimal value) {
    return value != null
        && value.signum() >= 0
        && value.compareTo(new BigDecimal("1000000")) <= 0
        && value.scale() <= 2;
  }

  private Check check(String code, String label, String problem, String passed) {
    return new Check(
        code,
        label,
        problem == null ? Status.PASS : Status.BLOCKED,
        problem == null ? passed : problem);
  }

  private String structuredLimits(AgentConfigurationResponse config) {
    if (!bounded(config.concurrency(), 1000))
      return "Configure agent concurrency as an integer from 1 to 1000.";
    if (!bounded(config.maxTokensPerRun(), 2000000))
      return "Configure maximum tokens per run as an integer from 1 to 2000000.";
    if (!budget(config.monthlyBudget()))
      return "Configure agent monthly budget from 0 to 1000000 with at most two decimal places.";
    return null;
  }

  private String teamEnvelope(Team team, AgentConfigurationResponse config) {
    if (team == null) return "Assign an existing team in this organization.";
    if (!"USD".equals(team.currency())) return "Configure team budget currency as USD.";
    if (!bounded(team.concurrency(), 1000)) return "Configure team concurrency from 1 to 1000.";
    if (!budget(team.budget()))
      return "Configure team monthly budget from 0 to 1000000 with at most two decimal places.";
    if (!bounded(config.concurrency(), 1000) || !budget(config.monthlyBudget()))
      return "Configure valid agent concurrency and monthly budget before comparing team limits.";
    if (config.concurrency() > team.concurrency())
      return "Agent concurrency "
          + config.concurrency()
          + " exceeds team concurrency "
          + team.concurrency()
          + "; reduce agent concurrency to fit the team limit.";
    if (config.monthlyBudget().compareTo(team.budget()) > 0)
      return "Agent monthly budget "
          + config.monthlyBudget().toPlainString()
          + " USD exceeds team monthly budget "
          + team.budget().toPlainString()
          + " USD; reduce agent monthly budget to fit the team limit.";
    return null;
  }

  private AgentActivationReview review(UUID org, HumanIdentity human, UUID agent) {
    // Public configuration read joins this transaction, including the repeatable-read GET snapshot.
    var config = configurations.get(org, human, agent);
    var a = config.agent();
    List<Team> teams =
        a.teamId() == null
            ? List.of()
            : jdbc.query(
                "select status,concurrency,monthly_budget,currency from teams where"
                    + " organization_id=? and id=?",
                (rs, n) ->
                    new Team(
                        rs.getString(1),
                        rs.getObject(2, Integer.class),
                        rs.getBigDecimal(3),
                        rs.getString(4)),
                org,
                a.teamId());
    Team team = teams.isEmpty() ? null : teams.getFirst();
    String limitsProblem = structuredLimits(config);
    String envelopeProblem = teamEnvelope(team, config);
    boolean modelRequested = text(a.model(), 2000) && a.model().length() <= 2000;
    var checks =
        List.of(
            check(
                "DRAFT_STATUS",
                "Draft status",
                "DRAFT".equals(a.status())
                    ? null
                    : "Only draft agents can be considered for activation.",
                "Agent is a draft."),
            check(
                "CONTRACT",
                "Agent contract",
                text(a.responsibilities(), 4000)
                        && text(a.expectedInput(), 4000)
                        && text(a.outputFormat(), 4000)
                        && text(a.acceptanceCriteria(), 4000)
                    ? null
                    : "Complete responsibilities, expected input, output format and acceptance"
                        + " criteria with 1 to 4000 characters each.",
                "Required contract fields are configured."),
            check(
                "MODEL_SELECTION",
                "Requested model",
                modelRequested ? null : "Request a model using 1 to 2000 characters.",
                modelRequested
                    ? "Requested model: " + a.model() + "; this does not authorize its use."
                    : ""),
            check(
                "STRUCTURED_LIMITS",
                "Structured limits",
                limitsProblem,
                limitsProblem == null
                    ? "Concurrency: "
                        + config.concurrency()
                        + "; maximum tokens per run: "
                        + config.maxTokensPerRun()
                        + "; monthly budget: "
                        + config.monthlyBudget().toPlainString()
                        + " USD."
                    : ""),
            check(
                "TEAM_ACTIVE",
                "Active team",
                team == null
                    ? "Assign an existing team in this organization."
                    : !"ACTIVE".equals(team.status()) ? "Assign an active team." : null,
                "Assigned team is active."),
            check(
                "TEAM_ENVELOPE",
                "Team limits",
                envelopeProblem,
                envelopeProblem == null
                    ? "Agent concurrency "
                        + config.concurrency()
                        + " <= team concurrency "
                        + team.concurrency()
                        + "; agent monthly budget "
                        + config.monthlyBudget().toPlainString()
                        + " USD <= team monthly budget "
                        + team.budget().toPlainString()
                        + " USD; this does not allocate or enforce resources."
                    : ""),
            new Check(
                "MODEL_AUTHORIZATION",
                "Model authorization",
                Status.UNAVAILABLE,
                "Model authorization is not available. A requested model does not grant access."),
            new Check(
                "TOOL_AUTHORIZATION",
                "Tool authorization",
                Status.UNAVAILABLE,
                "Tool authorization is not available. Requested tools do not grant access."),
            new Check(
                "EXECUTION_RUNTIME",
                "Execution runtime",
                Status.UNAVAILABLE,
                "Agent execution is not available. Activation remains blocked."));
    return new AgentActivationReview(
        org,
        agent,
        a.name(),
        a.status(),
        config.revision(),
        Instant.now(),
        checks.stream().allMatch(c -> c.status() == Status.PASS),
        checks);
  }
}

package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyService {
  private final PolicyJdbc db;
  private final PolicyAuthority authority;

  public PolicyService(PolicyJdbc db, PolicyAuthority authority) {
    this.db = db;
    this.authority = authority;
  }

  @JsonIgnoreType
  public record Policy(
      long revision,
      boolean openaiEnabled,
      boolean anthropicEnabled,
      boolean geminiEnabled,
      Instant createdAt,
      Instant updatedAt) {
    @Override
    public String toString() {
      return "Policy[redacted]";
    }

    boolean same(Set<Provider> providers) {
      return openaiEnabled == providers.contains(Provider.OPENAI)
          && anthropicEnabled == providers.contains(Provider.ANTHROPIC)
          && geminiEnabled == providers.contains(Provider.GOOGLE_GEMINI);
    }
  }

  public record ProviderOption(
      Provider provider, String credentialMode, boolean enabled, String optionsSchema) {}

  public record Options(
      UUID organizationId, String policyRevision, List<ProviderOption> providers) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Policy provision(Set<Provider> providers) {
    db.context(false);
    validate(providers);
    Instant now = db.clock();
    try {
      db.jdbc.update(
          "insert into"
              + " provider_deployment_policy(id,revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at)"
              + " values (1,1,?,?,?,?,?)",
          providers.contains(Provider.OPENAI),
          providers.contains(Provider.ANTHROPIC),
          providers.contains(Provider.GOOGLE_GEMINI),
          stamp(now),
          stamp(now));
    } catch (DuplicateKeyException failure) {
      throw new PolicyFailure(409, "POLICY_ALREADY_INITIALIZED");
    }
    return new Policy(
        1,
        providers.contains(Provider.OPENAI),
        providers.contains(Provider.ANTHROPIC),
        providers.contains(Provider.GOOGLE_GEMINI),
        now,
        now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Policy replace(long expectedRevision, Set<Provider> providers) {
    db.context(false);
    validate(providers);
    if (expectedRevision <= 0) throw new IllegalArgumentException("INVALID_POLICY");
    Policy old = selected(true);
    if (old.revision() != expectedRevision) throw new PolicyFailure(409, "REVISION_CONFLICT");
    if (old.same(providers)) throw new PolicyFailure(409, "NO_CHANGE");
    if (old.revision() == Long.MAX_VALUE) throw new PolicyFailure(409, "REVISION_EXHAUSTED");
    Instant now = db.clock();
    if (now.isBefore(old.updatedAt())) throw PolicyFailure.unavailable();
    if (db.jdbc.update(
            "update provider_deployment_policy set"
                + " revision=?,openai_enabled=?,anthropic_enabled=?,gemini_enabled=?,updated_at=?"
                + " where id=1",
            old.revision() + 1,
            providers.contains(Provider.OPENAI),
            providers.contains(Provider.ANTHROPIC),
            providers.contains(Provider.GOOGLE_GEMINI),
            stamp(now))
        != 1) throw PolicyFailure.unavailable();
    return new Policy(
        old.revision() + 1,
        providers.contains(Provider.OPENAI),
        providers.contains(Provider.ANTHROPIC),
        providers.contains(Provider.GOOGLE_GEMINI),
        old.createdAt(),
        now);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
  public Policy readTrusted() {
    db.context(true);
    return selected(false);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
  public Options options(HumanIdentity actor, UUID org) {
    db.context(true);
    authority.require(actor, org);
    Policy policy = selected(false);
    return new Options(
        org,
        Long.toString(policy.revision()),
        List.of(
            new ProviderOption(Provider.OPENAI, "OPENAI_API_KEY", policy.openaiEnabled(), "EMPTY"),
            new ProviderOption(
                Provider.ANTHROPIC,
                "ANTHROPIC_API_KEY",
                policy.anthropicEnabled(),
                "ANTHROPIC_WORKSPACE"),
            new ProviderOption(
                Provider.GOOGLE_GEMINI, "GEMINI_API_KEY", policy.geminiEnabled(), "EMPTY")));
  }

  private Policy selected(boolean lock) {
    var rows =
        db.jdbc.query(
            "select revision,openai_enabled,anthropic_enabled,gemini_enabled,created_at,updated_at"
                + " from provider_deployment_policy where id=1"
                + (lock ? " for update" : ""),
            PolicyService::row);
    if (rows.size() != 1) throw PolicyFailure.unavailable();
    return rows.get(0);
  }

  private static Policy row(ResultSet rs, int index) throws SQLException {
    Long revision = rs.getObject("revision", Long.class);
    Boolean openai = rs.getObject("openai_enabled", Boolean.class);
    Boolean anthropic = rs.getObject("anthropic_enabled", Boolean.class);
    Boolean gemini = rs.getObject("gemini_enabled", Boolean.class);
    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
    OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
    if (revision == null
        || revision <= 0
        || openai == null
        || anthropic == null
        || gemini == null
        || !(openai || anthropic || gemini)
        || !PolicyJdbc.finite(created)
        || !PolicyJdbc.finite(updated)
        || updated.isBefore(created)) throw PolicyFailure.unavailable();
    return new Policy(
        revision, openai, anthropic, gemini, created.toInstant(), updated.toInstant());
  }

  private static void validate(Set<Provider> providers) {
    if (providers == null || providers.isEmpty() || providers.stream().anyMatch(p -> p == null))
      throw new IllegalArgumentException("INVALID_POLICY");
  }

  static OffsetDateTime stamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}

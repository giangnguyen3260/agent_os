package com.agentos.providercreation;

import com.agentos.auth.HumanIdentity;
import com.agentos.providercommands.CommandBoundary.Provider;
import com.agentos.providerreceipts.ProviderReceiptFailure;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable pre-Vault admission; explicit internal dependency, not an endpoint or automatic bean. */
public final class ProviderCreateAdmission {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String clock;

  public ProviderCreateAdmission(DataSource datasource) {
    if (datasource == null) throw failure(503, "COMMAND_UNAVAILABLE");
    jdbc = new JdbcTemplate(datasource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    transaction.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
    try (var connection = datasource.getConnection()) {
      clock =
          switch (connection.getMetaData().getDatabaseProductName()) {
            case "PostgreSQL" -> "select clock_timestamp()";
            case "H2" -> "select CURRENT_TIMESTAMP";
            default -> throw failure(503, "COMMAND_UNAVAILABLE");
          };
    } catch (Exception failure) {
      throw failure(503, "COMMAND_UNAVAILABLE");
    }
  }

  /** False means a concurrent own receipt exists: re-probe/compare it, never prepare a new key. */
  public boolean acquire(HumanIdentity actor, UUID org, UUID key, Provider provider) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw failure(503, "COMMAND_UNAVAILABLE");
    if (actor == null || actor.id() == null) throw failure(401, "UNAUTHORIZED");
    if (org == null || key == null || provider == null) throw failure(400, "INVALID_COMMAND");
    try {
      return Boolean.TRUE.equals(
          transaction.execute(
              status -> {
                if (jdbc.queryForList("select id from organizations where id=? for update", org)
                        .size()
                    != 1) throw failure(403, "FORBIDDEN");
                var policy =
                    jdbc.queryForList(
                        "select * from provider_deployment_policy where id=1 for update");
                var accounts =
                    jdbc.queryForList(
                        "select enabled,credential_version from human_accounts where id=? for"
                            + " update",
                        actor.id());
                var members =
                    jdbc.queryForList(
                        "select role from organization_memberships where organization_id=? and"
                            + " account_id=? for update",
                        org,
                        actor.id());
                if (accounts.size() != 1
                    || members.size() != 1
                    || !Boolean.TRUE.equals(accounts.get(0).get("enabled"))
                    || ((Number) accounts.get(0).get("credential_version")).longValue()
                        != actor.credentialVersion()
                    || !"ADMIN".equals(members.get(0).get("role"))) throw failure(403, "FORBIDDEN");
                Long historical =
                    jdbc.queryForObject(
                        "select count(*) from provider_command_receipts where organization_id=? and"
                            + " actor_id=? and idempotency_key=?",
                        Long.class,
                        org,
                        actor.id(),
                        key);
                if (historical == null) throw failure(503, "COMMAND_UNAVAILABLE");
                if (historical > 0) return false;
                if (policy.size() != 1) throw failure(503, "COMMAND_UNAVAILABLE");
                String enabled =
                    switch (provider) {
                      case OPENAI -> "openai_enabled";
                      case ANTHROPIC -> "anthropic_enabled";
                      case GOOGLE_GEMINI -> "gemini_enabled";
                    };
                if (!Boolean.TRUE.equals(policy.get(0).get(enabled)))
                  throw failure(409, "PROVIDER_DISABLED");
                Long count =
                    jdbc.queryForObject(
                        "select count(*) from provider_connections where organization_id=?",
                        Long.class,
                        org);
                if (count == null || count < 0) throw failure(503, "COMMAND_UNAVAILABLE");
                if (count >= 100) throw failure(409, "CONNECTION_LIMIT");
                var rows =
                    jdbc.queryForList(
                        "select recent_requests from provider_write_admission where"
                            + " organization_id=? and actor_id=? for update",
                        String.class,
                        org,
                        actor.id());
                OffsetDateTime now = jdbc.queryForObject(clock, OffsetDateTime.class);
                if (now == null || rows.size() > 1) throw failure(503, "COMMAND_UNAVAILABLE");
                long millis = now.toInstant().toEpochMilli();
                var retained = new ArrayList<String>();
                long previous = Long.MIN_VALUE;
                if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
                  String[] samples = rows.get(0).split(",", -1);
                  if (samples.length > 30) throw failure(503, "COMMAND_UNAVAILABLE");
                  for (String sample : samples) {
                    if (!sample.matches("[0-9]{1,16}")) throw failure(503, "COMMAND_UNAVAILABLE");
                    long value = Long.parseLong(sample);
                    if (value < previous || value > millis)
                      throw failure(503, "COMMAND_UNAVAILABLE");
                    previous = value;
                    if (value > millis - 60_000) retained.add(sample);
                  }
                }
                if (retained.size() >= 30) {
                  long remaining = Long.parseLong(retained.get(0)) + 60_000 - millis;
                  int retryAfter = (int) Math.max(1, Math.min(60, (remaining + 999) / 1000));
                  throw new ProviderReceiptFailure(429, "RATE_LIMITED", retryAfter);
                }
                retained.add(Long.toString(millis));
                if (rows.isEmpty()) {
                  jdbc.update(
                      "insert into"
                          + " provider_write_admission(organization_id,actor_id,recent_requests,updated_at)"
                          + " values (?,?,?,?)",
                      org,
                      actor.id(),
                      String.join(",", retained),
                      now);
                } else {
                  jdbc.update(
                      "update provider_write_admission set recent_requests=?,updated_at=? where"
                          + " organization_id=? and actor_id=?",
                      String.join(",", retained),
                      now,
                      org,
                      actor.id());
                }
                return true;
              }));
    } catch (ProviderReceiptFailure known) {
      throw known;
    } catch (RuntimeException unexpected) {
      throw failure(503, "COMMAND_UNAVAILABLE");
    }
  }

  private static ProviderReceiptFailure failure(int status, String code) {
    return new ProviderReceiptFailure(status, code);
  }
}

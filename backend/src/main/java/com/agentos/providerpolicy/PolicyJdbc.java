package com.agentos.providerpolicy;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
final class PolicyJdbc {
  final JdbcTemplate jdbc;

  PolicyJdbc(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void context(boolean read) {
    try {
      Object resource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isCurrentTransactionReadOnly() != read
          || !(resource instanceof ConnectionHolder holder)
          || holder.getConnection().getTransactionIsolation()
              != (read
                  ? Connection.TRANSACTION_REPEATABLE_READ
                  : Connection.TRANSACTION_READ_COMMITTED)
          || holder.getConnection().getAutoCommit()) throw PolicyFailure.unavailable();
    } catch (Exception failure) {
      throw PolicyFailure.unavailable();
    }
  }

  static boolean finite(OffsetDateTime value) {
    return value != null && value.getYear() >= -4712 && value.getYear() <= 294276;
  }

  Instant clock() {
    try {
      var holder =
          (ConnectionHolder) TransactionSynchronizationManager.getResource(jdbc.getDataSource());
      boolean postgres =
          holder.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL");
      if (!postgres && !holder.getConnection().getMetaData().getDatabaseProductName().equals("H2"))
        throw PolicyFailure.unavailable();
      OffsetDateTime sample =
          jdbc.queryForObject(
              "select " + (postgres ? "clock_timestamp()" : "current_timestamp"),
              OffsetDateTime.class);
      if (!finite(sample)) throw PolicyFailure.unavailable();
      return sample.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    } catch (Exception failure) {
      throw PolicyFailure.unavailable();
    }
  }
}

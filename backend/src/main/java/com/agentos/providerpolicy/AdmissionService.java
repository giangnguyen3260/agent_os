package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionService {
  private static final long WINDOW = 60_000_000L;
  private final PolicyJdbc db;
  private final PolicyAuthority authority;

  public AdmissionService(PolicyJdbc db, PolicyAuthority authority) {
    this.db = db;
    this.authority = authority;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void acquire(HumanIdentity actor, UUID org) {
    db.context(false);
    authority.require(actor, org);
    db.jdbc.update(
        "insert into provider_read_admission(organization_id,actor_id,recent_requests,updated_at)"
            + " values (?,?,'[]',current_timestamp) on conflict do nothing",
        org,
        actor.id());
    var rows =
        db.jdbc.query(
            "select recent_requests,updated_at from provider_read_admission where organization_id=?"
                + " and actor_id=? for update",
            (rs, i) -> new Stored(rs.getString(1), rs.getObject(2, OffsetDateTime.class)),
            org,
            actor.id());
    if (rows.size() != 1) throw PolicyFailure.unavailable();
    authority.require(actor, org);
    Instant clock = db.clock();
    long now = micros(clock);
    Stored stored = rows.get(0);
    if (!PolicyJdbc.finite(stored.updated())
        || stored
            .updated()
            .toInstant()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            .isAfter(clock)) throw PolicyFailure.unavailable();
    List<Long> requests = parse(stored.requests(), now);
    List<Long> live = append(requests, now);
    String encoded = "[" + String.join(",", live.stream().map(String::valueOf).toList()) + "]";
    if (db.jdbc.update(
            "update provider_read_admission set recent_requests=?,updated_at=? where"
                + " organization_id=? and actor_id=?",
            encoded,
            PolicyService.stamp(clock),
            org,
            actor.id())
        != 1) throw PolicyFailure.unavailable();
  }

  static List<Long> append(List<Long> requests, long now) {
    var live = new ArrayList<Long>();
    for (long value : requests) if (value > now - WINDOW) live.add(value);
    if (live.size() == 120) {
      long delay = WINDOW - (now - live.get(0));
      int retry = (int) Math.max(1, Math.min(60, (delay + 999_999) / 1_000_000));
      throw new PolicyFailure(429, "RATE_LIMITED", retry);
    }
    live.add(now);
    return live;
  }

  private record Stored(String requests, OffsetDateTime updated) {}

  static long micros(Instant instant) {
    try {
      long value =
          Math.addExact(
              Math.multiplyExact(instant.getEpochSecond(), 1_000_000), instant.getNano() / 1000);
      if (value < 0) throw PolicyFailure.unavailable();
      return value;
    } catch (ArithmeticException failure) {
      throw PolicyFailure.unavailable();
    }
  }

  static List<Long> parse(String json, long now) {
    if (json == null
        || json.length() > 4096
        || !json.matches("\\[(?:0|[1-9][0-9]*)(?:,(?:0|[1-9][0-9]*))*\\]|\\[\\]"))
      throw PolicyFailure.unavailable();
    var values = new ArrayList<Long>();
    if (json.equals("[]")) return values;
    long previous = -1;
    try {
      for (String number : json.substring(1, json.length() - 1).split(",")) {
        long value = Long.parseLong(number);
        if (value < previous || value > now || values.size() == 120)
          throw PolicyFailure.unavailable();
        values.add(value);
        previous = value;
      }
    } catch (NumberFormatException failure) {
      throw PolicyFailure.unavailable();
    }
    return values;
  }
}

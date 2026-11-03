package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal configuration read. Credential evidence must be evaluated before exposing an API. */
@Service
class ProviderListSnapshot {
  static final int PAGE_SIZE = 20;
  private static final long MAX_SAFE_TOTAL = 9_007_199_254_740_991L;
  private final JdbcTemplate jdbc;
  private final PolicyAuthority authority;

  ProviderListSnapshot(JdbcTemplate jdbc, PolicyAuthority authority) {
    this.jdbc = jdbc;
    this.authority = authority;
  }

  @JsonIgnoreType
  record Row(
      UUID organization,
      UUID id,
      String provider,
      String mode,
      String name,
      String workspace,
      String state,
      long revision,
      long credentialRevision,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  @JsonIgnoreType
  record Page(long total, int page, List<Row> rows) {
    Page {
      rows = List.copyOf(rows);
    }
  }

  @Transactional(
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ,
      propagation = Propagation.REQUIRES_NEW)
  public Page read(HumanIdentity actor, UUID organization, int page) {
    try {
      authority.require(actor, organization);
      if (page < 0 || page > 100_000) throw OptionsWire.bad();
      Long total =
          jdbc.queryForObject(
              "select count(*) from public.provider_connections where organization_id=?",
              Long.class,
              organization);
      if (total == null || total < 0 || total > MAX_SAFE_TOTAL) throw PolicyFailure.unavailable();
      List<Row> rows =
          jdbc.query(
              """
              select organization_id,id,provider,mode,name,workspace,state,
                     configuration_revision,credential_revision,created_at,updated_at
              from public.provider_connections where organization_id=?
              order by created_at desc,id desc limit 20 offset ?
              """,
              (rs, index) ->
                  new Row(
                      rs.getObject(1, UUID.class),
                      rs.getObject(2, UUID.class),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getString(5),
                      rs.getString(6),
                      rs.getString(7),
                      rs.getLong(8),
                      rs.getLong(9),
                      rs.getObject(10, OffsetDateTime.class),
                      rs.getObject(11, OffsetDateTime.class)),
              organization,
              page * PAGE_SIZE);
      return new Page(total, page, rows);
    } catch (PolicyFailure known) {
      throw known;
    } catch (RuntimeException failure) {
      throw PolicyFailure.unavailable();
    }
  }
}

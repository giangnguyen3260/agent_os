package com.agentos.governance.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.MemberAccessResponse.Counts;
import com.agentos.governance.api.MemberAccessResponse.Grant;
import com.agentos.governance.api.MemberAccessResponse.Grants;
import com.agentos.governance.api.MemberAccessResponse.Inventory;
import com.agentos.governance.api.MemberAccessResponse.InventoryItem;
import com.agentos.governance.api.MemberAccessResponse.Member;
import jakarta.persistence.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberAccessService {
  public enum Status {
    ALL,
    UNEXPIRED,
    EXPIRED,
    REVOKED
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;

  @Autowired
  public MemberAccessService(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  MemberAccessService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  private void authorize(UUID org, HumanIdentity human) {
    if (human == null
        || jdbc.queryForObject(
                """
                select count(*) from organization_memberships m
                join human_accounts h on h.id=m.account_id
                join organizations o on o.id=m.organization_id
                where m.organization_id=? and h.id=? and h.enabled=true
                  and h.credential_version=? and m.role='ADMIN'
                """,
                Long.class,
                org,
                human.id(),
                human.credentialVersion())
            != 1L) {
      throw new AccessDeniedException("Request denied");
    }
  }

  private void validatePage(int page) {
    if (page < 0 || page > 100000) throw new IllegalArgumentException("Invalid member filters");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Inventory inventory(UUID org, HumanIdentity human, int page, String query, String role) {
    authorize(org, human);
    validatePage(page);
    // Strip exactly Character.isWhitespace code points (String.strip); no NFC/NFKC
    // normalization. The limit counts Unicode code points, not UTF-16 code units.
    String search = query == null ? "" : query.strip();
    String selectedRole = role == null ? "" : role;
    if (search.codePointCount(0, search.length()) > 200
        || !List.of("", "ADMIN", "MEMBER").contains(selectedRole))
      throw new IllegalArgumentException("Invalid member filters");
    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String pattern =
        "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    String filter =
        """
        from organization_memberships m join human_accounts h on h.id=m.account_id
        where m.organization_id=? and (?='' or m.role=?)
          and (lower(h.display_name) like lower(?) escape '\\'
            or lower(h.email) like lower(?) escape '\\')
        """;
    long total =
        jdbc.queryForObject(
            "select count(*) " + filter,
            Long.class,
            org,
            selectedRole,
            selectedRole,
            pattern,
            pattern);
    var items =
        jdbc.query(
            """
            select p.*, coalesce(g.unexpired,0) as unexpired,
              coalesce(g.expired,0) as expired, coalesce(g.revoked,0) as revoked
            from (select h.id,h.display_name,h.email,h.enabled,m.role
            """
                + filter
                + " order by lower(h.display_name),h.id limit 20 offset ?) p "
                + """
left join (
  select account_id,
    sum(case when revoked_at is null and expires_at>? then 1 else 0 end) as unexpired,
    sum(case when revoked_at is null and expires_at<=? then 1 else 0 end) as expired,
    sum(case when revoked_at is not null then 1 else 0 end) as revoked
  from approval_grants where organization_id=?
    and account_id in (select h.id
"""
                + filter
                + " order by lower(h.display_name),h.id limit 20 offset ?) "
                + """
                    group by account_id
                  ) g on g.account_id=p.id order by lower(p.display_name),p.id
                  """,
            (rs, n) ->
                new InventoryItem(
                    rs.getObject("id", UUID.class),
                    rs.getString("display_name"),
                    rs.getString("email"),
                    rs.getString("role"),
                    rs.getBoolean("enabled"),
                    human.id().equals(rs.getObject("id", UUID.class)),
                    new Counts(
                        rs.getLong("unexpired"), rs.getLong("expired"), rs.getLong("revoked"))),
            org,
            selectedRole,
            selectedRole,
            pattern,
            pattern,
            page * 20,
            OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
            org,
            org,
            selectedRole,
            selectedRole,
            pattern,
            pattern,
            page * 20);
    return new Inventory(org, at, items, page, 20, total);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Grants grants(UUID org, UUID account, HumanIdentity human, int page, Status status) {
    authorize(org, human);
    validatePage(page);
    if (status == null) throw new IllegalArgumentException("Invalid grant filters");
    var members =
        jdbc.query(
            """
            select h.id,h.display_name,h.email,h.enabled,m.role from human_accounts h
            join organization_memberships m on m.account_id=h.id
            where m.organization_id=? and h.id=?
            """,
            (rs, n) ->
                new Member(
                    account,
                    rs.getString("display_name"),
                    rs.getString("email"),
                    rs.getString("role"),
                    rs.getBoolean("enabled"),
                    account.equals(human.id())),
            org,
            account);
    if (members.isEmpty()) throw new EntityNotFoundException("Member not found");
    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var timestamp = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    String classified =
        """
        from (select g.id,g.organization_id,g.account_id,g.team_id,t.name as team_name,
          g.action_type,g.risk_level,g.expires_at,g.revoked_at,
          case when g.revoked_at is not null then 'REVOKED'
            when g.expires_at<=? then 'EXPIRED' else 'UNEXPIRED' end as lifecycle
          from approval_grants g left join teams t
            on t.id=g.team_id and t.organization_id=g.organization_id
          where g.organization_id=? and g.account_id=?) scoped
        where (?='ALL' or lifecycle=?)
        """;
    long total =
        jdbc.queryForObject(
            "select count(*) " + classified,
            Long.class,
            timestamp,
            org,
            account,
            status.name(),
            status.name());
    var items =
        jdbc.query(
            "select * " + classified + " order by expires_at desc,id limit 20 offset ?",
            (rs, n) ->
                new Grant(
                    rs.getObject("id", UUID.class),
                    org,
                    account,
                    rs.getObject("team_id", UUID.class),
                    rs.getString("team_name"),
                    rs.getString("action_type"),
                    rs.getString("risk_level"),
                    instant(rs, "expires_at"),
                    instant(rs, "revoked_at"),
                    rs.getString("lifecycle")),
            timestamp,
            org,
            account,
            status.name(),
            status.name(),
            page * 20);
    return new Grants(org, at, members.getFirst(), items, page, 20, total);
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

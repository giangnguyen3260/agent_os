package com.agentos.governance.application;

import com.agentos.auth.HumanIdentity;
import com.agentos.governance.api.ApprovalCenterResponse;
import com.agentos.governance.api.ApprovalCenterResponse.Counts;
import com.agentos.governance.api.ApprovalCenterResponse.Item;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalCenterService {
  public enum Group {
    PENDING,
    NEEDS_INFORMATION,
    DECIDED
  }

  private final JdbcTemplate jdbc;

  public ApprovalCenterService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ApprovalCenterResponse list(UUID org, HumanIdentity human, Group group, int page) {
    if (human == null
        || jdbc.queryForObject(
                """
                select count(*) from organization_memberships m
                join human_accounts h on h.id=m.account_id
                join organizations o on o.id=m.organization_id
                where m.organization_id=? and h.id=? and h.enabled=true
                  and h.credential_version=? and m.role in ('ADMIN','MEMBER')
                """,
                Long.class,
                org,
                human.id(),
                human.credentialVersion())
            != 1L) {
      throw new AccessDeniedException("Request denied");
    }
    if (page < 0 || page > 100000 || group == null)
      throw new IllegalArgumentException("Invalid approval filters");
    Counts counts =
        jdbc.queryForObject(
            """
select coalesce(sum(case when status='PENDING' then 1 else 0 end),0),
  coalesce(sum(case when status='NEEDS_INFORMATION' then 1 else 0 end),0),
  coalesce(sum(case when status in ('APPROVED','REJECTED','EXPIRED','CANCELLED','INVALIDATED') then 1 else 0 end),0)
from approval_requests where organization_id=?
""",
            (rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            org);
    long total =
        switch (group) {
          case PENDING -> counts.pending();
          case NEEDS_INFORMATION -> counts.needsInformation();
          case DECIDED -> counts.decided();
        };
    // SQL fragments are a closed enum mapping; only values are bound from the request.
    String states =
        switch (group) {
          case PENDING -> "('PENDING')";
          case NEEDS_INFORMATION -> "('NEEDS_INFORMATION')";
          case DECIDED -> "('APPROVED','REJECTED','EXPIRED','CANCELLED','INVALIDATED')";
        };
    List<Item> items =
        jdbc.query(
            """
            select r.*,t.name as team_name,m.title as mission_title,c.title as task_title,
              a.id as requester_id,a.name as requester_name
            from (select * from approval_requests where organization_id=? and status in
            """
                + states
                + " order by created_at desc,id limit 20 offset ?) r "
                + """
join teams t on t.organization_id=r.organization_id and t.id=r.team_id
left join missions m on m.organization_id=r.organization_id and m.team_id=r.team_id and m.id=r.mission_id
left join tasks c on c.organization_id=r.organization_id and c.team_id=r.team_id and c.mission_id=r.mission_id and c.id=r.task_id
left join agents a on a.organization_id=r.organization_id and a.team_id=r.team_id and a.id=r.requested_by_agent_id
order by r.created_at desc,r.id
""",
            (rs, n) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("organization_id", UUID.class),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("team_name"),
                    rs.getObject("mission_id", UUID.class),
                    rs.getString("mission_title"),
                    rs.getObject("task_id", UUID.class),
                    rs.getString("task_title"),
                    rs.getObject("requester_id", UUID.class),
                    rs.getString("requester_name"),
                    rs.getString("title"),
                    rs.getString("action_type"),
                    rs.getString("risk_level"),
                    rs.getString("status"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
            org,
            page * 20);
    return new ApprovalCenterResponse(Instant.now(), items, page, 20, total, counts);
  }
}

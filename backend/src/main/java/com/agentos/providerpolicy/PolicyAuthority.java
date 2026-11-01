package com.agentos.providerpolicy;

import com.agentos.auth.HumanIdentity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class PolicyAuthority {
  private final JdbcTemplate jdbc;

  PolicyAuthority(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  boolean current(HumanIdentity actor) {
    return actor != null
        && actor.id() != null
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select count(*) > 0 from human_accounts where id=? and enabled=true and"
                    + " credential_version=?",
                Boolean.class,
                actor.id(),
                actor.credentialVersion()));
  }

  void require(HumanIdentity actor, UUID org) {
    if (actor == null) throw new PolicyFailure(401, "UNAUTHORIZED");
    if (!current(actor)
        || org == null
        || !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select count(*) > 0 from organization_memberships m join organizations o on"
                    + " o.id=m.organization_id where m.account_id=? and m.organization_id=? and"
                    + " m.role='ADMIN'",
                Boolean.class,
                actor.id(),
                org))) throw PolicyFailure.forbidden();
  }
}

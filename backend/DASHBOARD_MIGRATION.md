# V004 rollout

V004 adds durable task storage without seeding task rows or exposing mutation endpoints.
Task pending metadata columns are `pending_reason`, `waiting_since`, and `resolver`.
Tasks require an organization, team, and mission; the mission must belong to that same
organization and team. WAITING/BLOCKED rows require all three pending fields, with
nonblank reason/resolver. Other statuses require those fields to be null.

The migration also adds an organization/team composite foreign key to existing
missions. It deliberately rejects legacy missions pointing to a team in another
organization. It does not silently repair, delete, or reassign legacy data. Missions
with a null team remain valid, but cannot parent a team-scoped task.

Before rollout, run this read-only check against the target database:

```sql
select m.id, m.organization_id, m.team_id, t.organization_id as team_organization_id
from missions m
join teams t on t.id = m.team_id
where m.organization_id <> t.organization_id;
```

If rows are returned, stop rollout and have the data owner determine the correct
organization/team association before retrying. V004 must validate successfully
before the dashboard backend is deployed. Use the existing database backup and
migration change window procedures; constraint validation and index creation can
lock existing tables. No automatic data correction is included.

Dashboard pagination uses fixed size 20; accepted page numbers are 0 through
107374182 (inclusive), bounding offsets to signed integer range. Beyond the final
page, items is empty and total remains the matching row count. Capacity and
performance remain explicitly unavailable until their underlying systems exist.

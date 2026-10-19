# V005 Team Draft rollout

V005 adds nullable Draft configuration to existing teams, same-organization/team agent constraints and durable idempotency receipts. Valid legacy rows remain readable with nullable configuration; the migration does not populate sample business data or activate teams.

Before applying to an existing database, run this read-only ownership check:

```sql
select a.id, a.organization_id, a.team_id,
       t.organization_id as team_organization_id
from agents a
join teams t on t.id = a.team_id
where a.organization_id <> t.organization_id;
```

If rows are returned, stop rollout and have the data owner determine the correct ownership. Do not automatically delete or reassign agents to satisfy the new foreign key. Take a database backup and schedule the schema change according to the environment's release process; constraint validation may lock tables.

Fresh PostgreSQL migration is verified in Docker CI; this does not prove compatibility with every existing production dataset. New API requests reject excess budget precision before numeric storage can round it.

Receipts retain the original response permanently for the organization/account/key scope. Future retention or response-format changes must explicitly preserve the promised retry behavior. Do not prune receipts as routine cleanup without defining the changed retry guarantee. Limits are saved configuration; V005 does not implement an allocator or activate teams.

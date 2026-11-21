-- Immutable accepted content and explicit current selection. No execution authority.
create table mission_plan_acceptance_versions (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 plan_revision bigint not null,
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 schema_version integer not null check(schema_version=1),
 source_fingerprint varchar(64) not null check(length(source_fingerprint)=64),
 ready_fingerprint varchar(64) not null check(length(ready_fingerprint)=64),
 snapshot_json text not null check(length(snapshot_json)<=8388608),
 snapshot_sha256 varchar(64) not null check(length(snapshot_sha256)=64),
 primary key(organization_id,team_id,mission_id,plan_revision),
 unique(organization_id,actor_id,idempotency_key),
 foreign key(organization_id,team_id,mission_id,plan_revision)
 references accepted_mission_plans(organization_id,team_id,mission_id,plan_revision)
);
create table mission_plan_selections (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 plan_revision bigint not null,
 primary key(organization_id,team_id,mission_id),
 foreign key(organization_id,team_id,mission_id,plan_revision)
 references mission_plan_acceptance_versions(organization_id,team_id,mission_id,plan_revision)
);

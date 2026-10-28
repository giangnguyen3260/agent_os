-- Original accepted receipts are retained across subsequent intake and lifecycle changes.
create table mission_intake_receipts (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 event_id uuid not null unique,
 body_sha256 varchar(64) not null,
 original_response text not null,
 created_at timestamptz not null,
 primary key (organization_id,team_id,mission_id,actor_id,idempotency_key),
 foreign key (organization_id,team_id,mission_id)
 references missions(organization_id,team_id,id)
);

-- Configuration provenance only; retain existing task rows and owner pointers.
alter table tasks add column task_owner_revision integer not null default 0
 check (task_owner_revision >= 0);
create table task_owner_events (
 id uuid primary key,
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 task_id uuid not null,
 revision integer not null check (revision > 0),
 actor_id uuid not null references human_accounts(id),
 created_at timestamptz not null,
 before_owner_agent_id uuid references agents(id),
 after_owner_agent_id uuid references agents(id),
 before_json text not null,
 after_json text not null,
 foreign key (organization_id,team_id,mission_id,task_id)
 references tasks(organization_id,team_id,mission_id,id),
 unique (organization_id,team_id,mission_id,task_id,revision),
 unique (organization_id,team_id,mission_id,task_id,actor_id,id)
);
create table task_owner_receipts (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 task_id uuid not null,
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 event_id uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 created_at timestamptz not null,
 primary key (organization_id,team_id,mission_id,task_id,actor_id,idempotency_key),
 foreign key (organization_id,team_id,mission_id,task_id)
 references tasks(organization_id,team_id,mission_id,id),
 foreign key (organization_id,team_id,mission_id,task_id,actor_id,event_id)
 references task_owner_events(organization_id,team_id,mission_id,task_id,actor_id,id)
);

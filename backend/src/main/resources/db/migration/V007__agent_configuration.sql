-- Requested configuration only, without runtime grants or enforcement.
alter table agents add column configuration_revision integer not null default 0
 check (configuration_revision >= 0);
update agents set configuration_revision=1
 where exists (select 1 from agent_draft_config c where c.agent_id=agents.id);
alter table agent_draft_config add column concurrency integer check (concurrency between 1 and 1000);
alter table agent_draft_config add column max_tokens_per_run integer check (max_tokens_per_run between 1 and 2000000);
alter table agent_draft_config add column monthly_budget numeric(9,2) check (monthly_budget between 0 and 1000000);

alter table agents add constraint uq_agents_org_id unique (organization_id,id);

-- Snapshots are append-only: application code only inserts and reads them.
create table agent_configuration_versions (
 organization_id uuid not null references organizations(id),
 agent_id uuid not null,
 revision integer not null check (revision > 0),
 account_id uuid not null references human_accounts(id),
 created_at timestamptz not null default current_timestamp,
 original_response text not null,
 foreign key (organization_id,agent_id) references agents(organization_id,id),
 primary key (organization_id,agent_id,revision)
);
create table agent_configuration_receipts (
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 agent_id uuid not null,
 idempotency_key uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 foreign key (organization_id,agent_id) references agents(organization_id,id),
 primary key (organization_id,account_id,agent_id,idempotency_key)
);

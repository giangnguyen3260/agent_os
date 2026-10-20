-- Requested configuration only: these values grant no capabilities or enforcement.
create table agent_draft_config (
 agent_id uuid primary key references agents(id),
 description varchar(4000),
 responsibilities varchar(4000) not null,
 expected_input varchar(4000) not null,
 output_format varchar(4000) not null,
 acceptance_criteria varchar(4000) not null,
 model varchar(2000),
 skills varchar(2000),
 tools varchar(2000),
 limits varchar(2000)
);
create table agent_create_receipts (
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 primary key (organization_id, account_id, idempotency_key)
);
create index idx_agents_org_name_id on agents(organization_id, name, id);

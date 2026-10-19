alter table teams add column description text;
alter table teams add column manager_agent_id uuid;
alter table teams add column intake varchar(20) check (intake = 'MANUAL');
alter table teams add column concurrency integer check (concurrency between 1 and 1000);
alter table teams add column monthly_budget numeric(12,2) check (monthly_budget between 0 and 1000000);
alter table teams add column currency varchar(3) check (currency = 'USD');
alter table agents add constraint uq_agents_org_team_id unique (organization_id, team_id, id);
alter table agents add constraint fk_agents_org_team foreign key (organization_id, team_id) references teams (organization_id, id);
alter table teams add constraint fk_teams_manager foreign key (organization_id, id, manager_agent_id) references agents (organization_id, team_id, id);
create table team_create_receipts (
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 primary key (organization_id, account_id, idempotency_key)
);

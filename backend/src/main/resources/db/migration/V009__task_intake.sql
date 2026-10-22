alter table missions add column task_intake_version bigint not null default 0 check (task_intake_version >= 0);
alter table missions add column task_intake_paused boolean not null default false;
alter table tasks add column description text check (description is null or char_length(description) between 1 and 8000);
alter table tasks add column acceptance_criteria text check (acceptance_criteria is null or char_length(acceptance_criteria) between 1 and 4000);
alter table tasks add column priority varchar(10) check (priority in ('LOW','NORMAL','HIGH','URGENT'));
alter table tasks add column owner_agent_id uuid references agents(id);
alter table tasks add constraint uq_tasks_scope_id unique (organization_id,team_id,mission_id,id);
create table task_dependencies (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 task_id uuid not null,
 dependency_task_id uuid not null,
 primary key (organization_id,team_id,mission_id,task_id,dependency_task_id),
 check (task_id <> dependency_task_id),
 foreign key (organization_id,team_id,mission_id,task_id) references tasks(organization_id,team_id,mission_id,id),
 foreign key (organization_id,team_id,mission_id,dependency_task_id) references tasks(organization_id,team_id,mission_id,id)
);
create table task_create_receipts (
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 primary key (organization_id,account_id,idempotency_key)
);
create index idx_audit_events_org_task_created_id on audit_events(organization_id,task_id,created_at desc,id);

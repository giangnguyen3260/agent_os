-- Metadata ledger only. This migration does not grant permission or authorize execution.
create table approval_requests (
 id uuid primary key,
 organization_id uuid not null references organizations(id),
 team_id uuid not null,
 mission_id uuid,
 task_id uuid,
 requested_by_agent_id uuid,
 title varchar(255) not null check (char_length(trim(title)) between 1 and 255),
 action_type varchar(100) not null check (char_length(trim(action_type)) between 1 and 100),
 risk_level varchar(10) not null check (risk_level in ('LOW','MEDIUM','HIGH','CRITICAL')),
 status varchar(30) not null check (status in ('PENDING','NEEDS_INFORMATION','APPROVED','REJECTED','EXPIRED','CANCELLED','INVALIDATED')),
 created_at timestamptz not null default current_timestamp,
 updated_at timestamptz not null default current_timestamp,
 check (task_id is null or mission_id is not null),
 check (updated_at >= created_at),
 foreign key (organization_id,team_id) references teams(organization_id,id),
 foreign key (organization_id,team_id,mission_id) references missions(organization_id,team_id,id),
 foreign key (organization_id,team_id,mission_id,task_id) references tasks(organization_id,team_id,mission_id,id),
 foreign key (organization_id,requested_by_agent_id) references agents(organization_id,id)
);
create index idx_approvals_org_status_created_id on approval_requests(organization_id,status,created_at desc,id);

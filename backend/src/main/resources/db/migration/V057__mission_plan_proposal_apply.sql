-- One immutable reviewed full-DAG import receipt; never an execution authorization.
create table mission_plan_proposal_receipts (
 organization_id uuid not null references organizations(id),
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 command_sha256 varchar(64) not null check(length(command_sha256)=64),
 snapshot_json text not null check(octet_length(snapshot_json)<=131072),
 snapshot_sha256 varchar(64) not null check(length(snapshot_sha256)=64),
 response_json text not null check(octet_length(response_json)<=32768),
 response_sha256 varchar(64) not null check(length(response_sha256)=64),
 source_run_id uuid not null references execution_runs(id),
 created_at timestamptz not null default current_timestamp,
 primary key(organization_id,actor_id,idempotency_key),
 unique(organization_id,team_id,mission_id),
 foreign key(organization_id,team_id,mission_id) references missions(organization_id,team_id,id)
);

create table mission_plan_proposal_tasks (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 local_key varchar(40) not null,
 task_id uuid not null unique,
 primary key(organization_id,team_id,mission_id,local_key),
 foreign key(organization_id,team_id,mission_id) references mission_plan_proposal_receipts(organization_id,team_id,mission_id),
 foreign key(organization_id,team_id,mission_id,task_id) references tasks(organization_id,team_id,mission_id,id)
);

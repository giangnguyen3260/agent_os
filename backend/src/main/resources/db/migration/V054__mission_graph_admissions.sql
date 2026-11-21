-- Configuration admission is durable; no dispatch or paid effect is created here.
alter table execution_runs add constraint uq_graph_run_scope unique(organization_id,team_id,mission_id,task_id,id,plan_revision);
create table mission_graph_admissions (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 plan_revision bigint not null,
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 source_fingerprint varchar(64) not null check(length(source_fingerprint)=64),
 admitted_at timestamptz not null default current_timestamp,
 primary key(organization_id,team_id,mission_id),
 unique(organization_id,actor_id,idempotency_key),
 unique(organization_id,team_id,mission_id,plan_revision),
 foreign key(organization_id,team_id,mission_id,plan_revision) references mission_plan_acceptance_versions(organization_id,team_id,mission_id,plan_revision)
);
create table mission_graph_admission_runs (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 plan_revision bigint not null,
 task_id uuid not null,
 run_id uuid not null,
 is_root boolean not null,
 primary key(organization_id,team_id,mission_id,task_id),
 unique(run_id),
 foreign key(organization_id,team_id,mission_id,plan_revision) references mission_graph_admissions(organization_id,team_id,mission_id,plan_revision),
 foreign key(organization_id,team_id,mission_id,plan_revision,task_id) references accepted_mission_plan_tasks(organization_id,team_id,mission_id,plan_revision,task_id),
 foreign key(organization_id,team_id,mission_id,task_id,run_id,plan_revision) references execution_runs(organization_id,team_id,mission_id,task_id,id,plan_revision)
);
create table mission_graph_dependency_releases (
 run_id uuid primary key references mission_graph_admission_runs(run_id),
 evidence_json text not null check(length(evidence_json)<=1048576),
 evidence_sha256 varchar(64) not null check(length(evidence_sha256)=64),
 released_at timestamptz not null default current_timestamp
);

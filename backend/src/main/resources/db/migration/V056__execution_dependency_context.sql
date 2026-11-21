-- Existing admissions keep their original order-only execution contract.
alter table mission_graph_admissions add column dependency_context_required boolean not null default false;
alter table execution_start_dispatch add constraint uq_dispatch_context_scope unique(id,organization_id,run_id);
alter table mission_graph_admission_runs add constraint uq_context_admitted_run unique(organization_id,team_id,mission_id,plan_revision,run_id);
create table execution_dependency_contexts (
 dispatch_id uuid primary key references execution_openai_requests(dispatch_id),
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 run_id uuid not null,
 plan_revision bigint not null,
 schema_version integer not null check(schema_version=1),
 context_json text not null check(octet_length(context_json)<=8192),
 context_sha256 varchar(64) not null check(length(context_sha256)=64),
 request_sha256 varchar(64) not null check(length(request_sha256)=64),
 foreign key(organization_id,team_id,mission_id,plan_revision) references mission_graph_admissions(organization_id,team_id,mission_id,plan_revision),
 foreign key(run_id) references mission_graph_admission_runs(run_id),
 foreign key(dispatch_id,organization_id,run_id) references execution_start_dispatch(id,organization_id,run_id),
 foreign key(organization_id,team_id,mission_id,plan_revision,run_id) references mission_graph_admission_runs(organization_id,team_id,mission_id,plan_revision,run_id)
);

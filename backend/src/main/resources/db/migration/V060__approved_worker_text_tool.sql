-- Explicit human approval of bounded local preprocessing; never model-text authority.
create table worker_text_tool_approvals (
 organization_id uuid not null,
 run_id uuid not null,
 source_revision bigint not null check(source_revision>=0),
 idempotency_key uuid not null,
 actor_id uuid not null references human_accounts(id),
 actor_version bigint not null,
 grant_id uuid not null,
 input_text text not null check(octet_length(input_text)<=8192),
 input_sha256 varchar(64) not null,
 fingerprint varchar(64) not null,
 approved_at timestamptz not null,
 primary key(run_id,source_revision),
 unique(organization_id,actor_id,idempotency_key),
 foreign key(organization_id,run_id) references execution_runs(organization_id,id),
 foreign key(organization_id,grant_id) references text_tool_grants(organization_id,id)
);

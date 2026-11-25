create table execution_unsent_failure_evidence (
 organization_id uuid not null,
 run_id uuid not null,
 attempt_id uuid not null,
 fence bigint not null,
 dispatch_id uuid not null references execution_openai_requests(dispatch_id),
 request_sha256 varchar(64) not null,
 recorded_at timestamptz not null default current_timestamp,
 primary key(run_id,attempt_id),
 unique(dispatch_id),
 foreign key(organization_id,run_id) references execution_runs(organization_id,id),
 foreign key(run_id,attempt_id,fence) references execution_claims(run_id,attempt_id,fence)
);
create table unsent_run_recovery_receipts (
 organization_id uuid not null,
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 run_id uuid not null,
 source_revision bigint not null check(source_revision>=2),
 resulting_revision bigint not null,
 fingerprint varchar(64) not null,
 recorded_at timestamptz not null default current_timestamp,
 primary key(organization_id,actor_id,idempotency_key),
 unique(run_id,source_revision),
 foreign key(organization_id,run_id) references execution_runs(organization_id,id),
 check(resulting_revision=source_revision+1)
);

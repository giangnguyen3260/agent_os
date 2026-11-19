-- Durable admission for already accepted Runs; not a plan acceptance command.
create table execution_start_dispatch (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    run_id uuid not null references execution_runs(id),
    source_run_revision bigint not null check (source_run_revision >= 0),
    actor_id uuid not null references human_accounts(id),
    idempotency_key uuid not null,
    actor_credential_version bigint not null check (actor_credential_version >= 0),
    state varchar(12) not null check (state in ('QUEUED','CLAIMED','UNKNOWN','FINISHED')),
    created_at timestamptz not null default current_timestamp,
    unique (organization_id,actor_id,idempotency_key),
    unique (run_id,source_run_revision)
);
create index idx_execution_start_queue on execution_start_dispatch(state,created_at,id);

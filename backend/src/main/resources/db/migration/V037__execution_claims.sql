alter table execution_attempts add constraint uq_execution_attempt_run_id unique (run_id, id);

create table execution_claims (
    run_id uuid not null,
    attempt_id uuid not null,
    fence bigint not null check (fence > 0),
    worker_id uuid not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    deadline_at timestamptz not null,
    invalidated_at timestamptz,
    primary key (run_id, fence),
    foreign key (run_id, attempt_id) references execution_attempts (run_id, id),
    check (issued_at < expires_at and expires_at <= deadline_at),
    check (invalidated_at is null or invalidated_at >= issued_at)
);

create index idx_execution_claims_attempt on execution_claims (run_id, attempt_id);

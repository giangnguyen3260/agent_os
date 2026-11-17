create table execution_attempt_reconciliations (
    run_id uuid not null,
    attempt_id uuid not null,
    outcome varchar(12) not null check (outcome in ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    evidence_sha256 varchar(64) not null check (char_length(evidence_sha256) = 64),
    reconciled_at timestamptz not null,
    primary key (run_id, attempt_id),
    foreign key (run_id, attempt_id) references execution_attempts (run_id, id)
);

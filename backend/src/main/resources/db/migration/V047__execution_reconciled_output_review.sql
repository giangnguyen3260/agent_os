alter table execution_attempt_reconciliations add column output_sha256 varchar(64)
    check (output_sha256 is null or (outcome = 'SUCCEEDED' and char_length(output_sha256) = 64));

create table execution_reconciled_review_submissions (
    run_id uuid not null,
    attempt_id uuid not null,
    source_run_revision bigint not null check (source_run_revision >= 0),
    submitted_at timestamptz not null,
    primary key (run_id, attempt_id),
    foreign key (run_id, attempt_id) references execution_attempt_reconciliations (run_id, attempt_id)
);

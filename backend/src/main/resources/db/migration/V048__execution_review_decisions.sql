alter table execution_runs add column max_reworks integer check (max_reworks >= 0);

create table execution_review_decisions (
    run_id uuid not null,
    attempt_id uuid not null,
    reviewer_id uuid not null references human_accounts (id),
    source_run_revision bigint not null check (source_run_revision >= 0),
    decision varchar(10) not null check (decision in ('ACCEPT', 'REWORK')),
    reason varchar(1000),
    decided_at timestamptz not null,
    primary key (run_id, attempt_id),
    foreign key (run_id, attempt_id) references execution_reconciled_review_submissions (run_id, attempt_id),
    check (
        (decision = 'ACCEPT' and reason is null)
        or (decision = 'REWORK' and reason is not null and char_length(trim(reason)) > 0)
    )
);

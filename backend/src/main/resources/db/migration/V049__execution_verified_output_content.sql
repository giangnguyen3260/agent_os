create table execution_verified_output_content (
    run_id uuid not null,
    attempt_id uuid not null,
    output_sha256 varchar(64) not null check (char_length(output_sha256) = 64),
    content text not null check (char_length(content) between 1 and 262144),
    stored_at timestamptz not null,
    primary key (run_id, attempt_id),
    foreign key (run_id, attempt_id) references execution_attempt_reconciliations (run_id, attempt_id)
);

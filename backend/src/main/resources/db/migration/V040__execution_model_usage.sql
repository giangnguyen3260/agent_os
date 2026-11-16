alter table execution_runs add constraint uq_execution_run_organization
    unique (organization_id, id);

create table execution_model_usage (
    organization_id uuid not null,
    run_id uuid not null,
    action_id uuid not null,
    connection_id uuid not null,
    provider_request_sha256 varchar(64) not null check (char_length(provider_request_sha256) = 64),
    input_tokens bigint not null check (input_tokens >= 0),
    output_tokens bigint not null check (output_tokens >= 0),
    recorded_at timestamptz not null,
    primary key (run_id, action_id),
    unique (organization_id, connection_id, provider_request_sha256),
    foreign key (organization_id, run_id) references execution_runs (organization_id, id),
    foreign key (organization_id, connection_id) references provider_connections (organization_id, id),
    foreign key (run_id, action_id) references execution_budget_reservations (run_id, action_id)
);

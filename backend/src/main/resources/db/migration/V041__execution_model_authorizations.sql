alter table execution_budget_reservations add constraint uq_execution_budget_action_fence
    unique (run_id, action_id, attempt_id, fence);

create table execution_model_authorizations (
    organization_id uuid not null,
    run_id uuid not null,
    action_id uuid not null,
    attempt_id uuid not null,
    fence bigint not null,
    agent_id uuid not null,
    connection_id uuid not null,
    credential_revision bigint not null check (credential_revision > 0),
    model_id varchar(256) not null check (char_length(model_id) > 0),
    request_sha256 varchar(64) not null check (char_length(request_sha256) = 64),
    authorized_at timestamptz not null,
    primary key (run_id, action_id),
    foreign key (organization_id, run_id) references execution_runs (organization_id, id),
    foreign key (organization_id, agent_id) references agents (organization_id, id),
    foreign key (organization_id, connection_id) references provider_connections (organization_id, id),
    foreign key (run_id, action_id, attempt_id, fence)
        references execution_budget_reservations (run_id, action_id, attempt_id, fence)
);

-- Built-in local text.metrics v1 only. No payloads or user-supplied executable catalog data.
create table text_tool_grants (
    id uuid primary key,
    organization_id uuid not null,
    team_id uuid not null,
    mission_id uuid not null,
    agent_id uuid not null,
    tool_version integer not null check (tool_version = 1),
    issued_by uuid not null references human_accounts(id),
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_by uuid references human_accounts(id),
    revoked_at timestamptz,
    unique (organization_id,id),
    foreign key (organization_id,team_id,mission_id) references missions(organization_id,team_id,id),
    foreign key (organization_id,team_id,agent_id) references agents(organization_id,team_id,id),
    check (expires_at > issued_at),
    check ((revoked_at is null and revoked_by is null) or (revoked_at >= issued_at and revoked_by is not null))
);
create index idx_text_tool_scope on text_tool_grants(organization_id,mission_id,agent_id,issued_at,id);
create table text_tool_grant_receipts (
    organization_id uuid not null references organizations(id),
    actor_id uuid not null references human_accounts(id),
    idempotency_key uuid not null,
    request_sha256 varchar(64) not null,
    result_json text not null,
    result_sha256 varchar(64) not null,
    primary key(organization_id,actor_id,idempotency_key)
);
create table text_tool_effects (
    organization_id uuid not null,
    run_id uuid not null,
    idempotency_key uuid not null,
    attempt_id uuid not null,
    fence bigint not null check (fence > 0),
    grant_id uuid not null,
    action_id uuid not null,
    request_sha256 varchar(64) not null,
    input_sha256 varchar(64) not null,
    utf8_bytes integer not null check (utf8_bytes between 0 and 8192),
    code_points integer not null check (code_points >= 0),
    lines integer not null check (lines >= 0),
    recorded_at timestamptz not null,
    primary key(run_id,idempotency_key),
    foreign key(organization_id,run_id) references execution_runs(organization_id,id),
    foreign key(run_id,attempt_id,fence) references execution_claims(run_id,attempt_id,fence),
    foreign key(organization_id,grant_id) references text_tool_grants(organization_id,id),
    foreign key(run_id,action_id) references execution_budget_reservations(run_id,action_id),
    unique(run_id,action_id)
);

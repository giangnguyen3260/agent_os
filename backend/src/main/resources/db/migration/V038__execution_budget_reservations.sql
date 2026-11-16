alter table execution_claims add constraint uq_execution_claim_attempt_fence
    unique (run_id, attempt_id, fence);

create table execution_budget_buckets (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    scope varchar(20) not null check (scope in ('ORGANIZATION', 'TEAM', 'AGENT', 'MISSION')),
    subject_id uuid not null,
    opens_at timestamptz not null,
    closes_at timestamptz not null,
    limit_microusd bigint not null check (limit_microusd >= 0),
    held_microusd bigint not null default 0 check (held_microusd >= 0),
    spent_microusd bigint not null default 0 check (spent_microusd >= 0),
    unique (organization_id, scope, subject_id, opens_at),
    check (opens_at < closes_at),
    check (scope <> 'ORGANIZATION' or organization_id = subject_id)
);

create table execution_budget_reservations (
    run_id uuid not null,
    action_id uuid not null,
    attempt_id uuid not null,
    fence bigint not null,
    reserved_microusd bigint not null check (reserved_microusd > 0),
    status varchar(10) not null check (status in ('HELD', 'UNKNOWN', 'SETTLED')),
    actual_microusd bigint check (actual_microusd >= 0),
    primary key (run_id, action_id),
    foreign key (run_id, attempt_id, fence) references execution_claims (run_id, attempt_id, fence),
    check (
        (status = 'SETTLED' and actual_microusd is not null)
        or (status <> 'SETTLED' and actual_microusd is null)
    )
);

create table execution_budget_reservation_buckets (
    run_id uuid not null,
    action_id uuid not null,
    bucket_id uuid not null references execution_budget_buckets (id),
    primary key (run_id, action_id, bucket_id),
    foreign key (run_id, action_id) references execution_budget_reservations (run_id, action_id)
);

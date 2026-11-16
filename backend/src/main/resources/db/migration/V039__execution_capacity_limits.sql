create table execution_capacity_limits (
    organization_id uuid not null references organizations (id),
    scope varchar(20) not null check (scope in ('ORGANIZATION', 'TEAM', 'AGENT', 'MISSION')),
    subject_id uuid not null,
    max_concurrent integer not null check (max_concurrent > 0),
    max_admissions integer not null check (max_admissions > 0),
    window_seconds integer not null check (window_seconds > 0),
    primary key (organization_id, scope, subject_id),
    check (scope <> 'ORGANIZATION' or organization_id = subject_id)
);

-- Existing reservations have no recorded admission time. Count them conservatively
-- from migration time for rate checks, preserving their status for concurrency.
alter table execution_budget_reservations
    add column admitted_at timestamptz not null default current_timestamp;

create index idx_execution_reservation_buckets_reverse
    on execution_budget_reservation_buckets (bucket_id, run_id, action_id);

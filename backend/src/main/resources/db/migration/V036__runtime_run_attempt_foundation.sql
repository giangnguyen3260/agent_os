-- The future acceptance command writes the plan snapshot and its graph in one transaction.
create table accepted_mission_plans (
    organization_id uuid not null,
    team_id uuid not null,
    mission_id uuid not null,
    plan_revision bigint not null check (plan_revision > 0),
    accepted_by uuid not null references human_accounts (id),
    accepted_at timestamptz not null default current_timestamp,
    primary key (organization_id, team_id, mission_id, plan_revision),
    foreign key (organization_id, team_id, mission_id)
        references missions (organization_id, team_id, id)
);

create table accepted_mission_plan_tasks (
    organization_id uuid not null,
    team_id uuid not null,
    mission_id uuid not null,
    plan_revision bigint not null,
    task_id uuid not null,
    owner_agent_id uuid not null,
    task_edit_revision integer not null check (task_edit_revision >= 0),
    task_owner_revision integer not null check (task_owner_revision >= 0),
    dependency_revision integer not null check (dependency_revision >= 0),
    primary key (organization_id, team_id, mission_id, plan_revision, task_id),
    foreign key (organization_id, team_id, mission_id, plan_revision)
        references accepted_mission_plans (organization_id, team_id, mission_id, plan_revision),
    foreign key (organization_id, team_id, mission_id, task_id)
        references tasks (organization_id, team_id, mission_id, id),
    foreign key (organization_id, owner_agent_id) references agents (organization_id, id)
);

create table accepted_mission_plan_dependencies (
    organization_id uuid not null,
    team_id uuid not null,
    mission_id uuid not null,
    plan_revision bigint not null,
    task_id uuid not null,
    dependency_task_id uuid not null,
    primary key (organization_id, team_id, mission_id, plan_revision, task_id, dependency_task_id),
    check (task_id <> dependency_task_id),
    foreign key (organization_id, team_id, mission_id, plan_revision, task_id)
        references accepted_mission_plan_tasks (organization_id, team_id, mission_id, plan_revision, task_id),
    foreign key (organization_id, team_id, mission_id, plan_revision, dependency_task_id)
        references accepted_mission_plan_tasks (organization_id, team_id, mission_id, plan_revision, task_id)
);

create table execution_runs (
    id uuid primary key,
    organization_id uuid not null,
    team_id uuid not null,
    mission_id uuid not null,
    task_id uuid not null,
    plan_revision bigint not null,
    revision bigint not null default 0 check (revision >= 0),
    status varchar(20) not null check (status in (
        'CREATED', 'BLOCKED', 'RUNNING', 'WAITING', 'IN_REVIEW', 'REWORK_REQUIRED',
        'COMPLETED', 'FAILED', 'CANCELLING', 'CANCELLED'
    )),
    wait_reason varchar(20),
    created_at timestamptz not null default current_timestamp,
    unique (organization_id, team_id, mission_id, task_id),
    foreign key (organization_id, team_id, mission_id, plan_revision, task_id)
        references accepted_mission_plan_tasks (organization_id, team_id, mission_id, plan_revision, task_id),
    check (
        (status = 'WAITING' and wait_reason is not null and wait_reason in ('INPUT', 'APPROVAL'))
        or (status <> 'WAITING' and wait_reason is null)
    )
);

create table execution_attempts (
    id uuid primary key,
    run_id uuid not null references execution_runs (id),
    attempt_number integer not null check (attempt_number > 0),
    status varchar(20) not null check (status in (
        'CREATED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_INPUT', 'SUCCEEDED',
        'FAILED', 'CANCELLING', 'CANCELLED', 'UNKNOWN'
    )),
    -- Unresolved attempts share slot zero, including UNKNOWN and safe waits.
    -- Terminal attempts occupy their own positive attempt number.
    outcome_slot integer not null,
    created_at timestamptz not null default current_timestamp,
    unique (run_id, attempt_number),
    unique (run_id, outcome_slot),
    check (
        (status in ('SUCCEEDED', 'FAILED', 'CANCELLED') and outcome_slot = attempt_number)
        or (status not in ('SUCCEEDED', 'FAILED', 'CANCELLED') and outcome_slot = 0)
    )
);

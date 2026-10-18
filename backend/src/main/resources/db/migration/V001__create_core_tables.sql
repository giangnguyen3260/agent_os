create table organizations (
    id uuid primary key,
    name varchar(255) not null,
    slug varchar(255) not null unique,
    description text,
    status varchar(50) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table teams (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    name varchar(255) not null,
    purpose text,
    status varchar(50) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_teams_organization_id on teams(organization_id);

create table agents (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    team_id uuid references teams(id),
    reports_to_agent_id uuid references agents(id),
    name varchar(255) not null,
    role varchar(255) not null,
    level varchar(50) not null,
    status varchar(50) not null,
    autonomy_level varchar(50) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_agents_organization_id on agents(organization_id);
create index idx_agents_team_id on agents(team_id);

create table missions (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    team_id uuid references teams(id),
    created_by_user_id uuid,
    manager_agent_id uuid references agents(id),
    title varchar(255) not null,
    goal text not null,
    status varchar(50) not null,
    priority varchar(50) not null,
    budget_limit numeric(12, 4),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_missions_organization_id on missions(organization_id);
create index idx_missions_team_id on missions(team_id);
create index idx_missions_status on missions(status);

create table audit_events (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    mission_id uuid references missions(id),
    task_id uuid,
    actor_type varchar(50) not null,
    actor_id uuid,
    event_type varchar(255) not null,
    payload text not null,
    created_at timestamptz not null
);

create index idx_audit_events_organization_id on audit_events(organization_id);
create index idx_audit_events_mission_id on audit_events(mission_id);
create index idx_audit_events_created_at on audit_events(created_at);

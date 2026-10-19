alter table teams add constraint uq_teams_org_id unique (organization_id, id);
alter table missions add constraint uq_missions_org_id unique (organization_id, id);
alter table missions add constraint uq_missions_org_team_id unique (organization_id, team_id, id);
alter table missions add constraint fk_missions_org_team foreign key (organization_id, team_id) references teams (organization_id, id);

create table tasks (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    team_id uuid not null,
    mission_id uuid not null,
    title varchar(255) not null,
    status varchar(50) not null check (status in ('DRAFT','READY','RUNNING','WAITING','BLOCKED','IN_REVIEW','COMPLETED','FAILED','CANCELLED')),
    pending_reason text,
    waiting_since timestamptz,
    resolver varchar(255),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    constraint fk_tasks_org_team foreign key (organization_id, team_id) references teams (organization_id, id),
    constraint fk_tasks_org_mission foreign key (organization_id, mission_id) references missions (organization_id, id),
    constraint fk_tasks_org_team_mission foreign key (organization_id, team_id, mission_id) references missions (organization_id, team_id, id),
    constraint ck_tasks_pending check (
        (status in ('WAITING','BLOCKED') and pending_reason is not null and length(trim(pending_reason)) > 0 and waiting_since is not null and resolver is not null and length(trim(resolver)) > 0)
        or (status not in ('WAITING','BLOCKED') and pending_reason is null and waiting_since is null and resolver is null)
    )
);
create index idx_teams_org_name_id on teams (organization_id, name, id);
create index idx_agents_org_team_status_level on agents (organization_id, team_id, status, level);
create index idx_tasks_org_status_created_id on tasks (organization_id, status, created_at, id);
create index idx_tasks_org_team_status_created_id on tasks (organization_id, team_id, status, created_at, id);
create index idx_tasks_org_team_mission on tasks (organization_id, team_id, mission_id);

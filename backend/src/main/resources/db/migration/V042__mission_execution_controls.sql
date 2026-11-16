create table mission_execution_controls (
    organization_id uuid not null,
    team_id uuid not null,
    mission_id uuid not null,
    revision bigint not null check (revision > 0),
    state varchar(20) not null check (state in ('OPEN', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED')),
    primary key (organization_id, team_id, mission_id),
    foreign key (organization_id, team_id, mission_id) references missions (organization_id, team_id, id)
);

-- Historical decisions predate the control revision and retain their original evidence.
alter table execution_model_authorizations
    add column control_revision bigint check (control_revision > 0);

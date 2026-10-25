-- Append-only settings history; no business seed or changes to earlier receipts.
create table team_settings_ledgers (
 organization_id uuid not null,
 team_id uuid not null,
 revision integer not null check (revision between 0 and 2147483647),
 primary key (organization_id, team_id),
 foreign key (organization_id, team_id) references teams(organization_id, id)
);
create table team_settings_events (
 organization_id uuid not null,
 team_id uuid not null,
 revision integer not null check (revision between 1 and 2147483647),
 event_id uuid not null,
 actor_id uuid not null references human_accounts(id),
 actor_name varchar(255) not null,
 recorded_at timestamptz not null,
 before_state text not null,
 after_state text not null,
 primary key (organization_id, team_id, revision),
 unique (organization_id, actor_id, event_id),
 unique (organization_id, team_id, actor_id, event_id),
 foreign key (organization_id, team_id) references teams(organization_id, id)
);
create table team_settings_receipts (
 organization_id uuid not null,
 actor_id uuid not null,
 idempotency_key uuid not null,
 team_id uuid not null,
 event_id uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 primary key (organization_id, actor_id, idempotency_key),
 foreign key (organization_id, team_id, actor_id, event_id)
 references team_settings_events(organization_id, team_id, actor_id, event_id),
 foreign key (organization_id, team_id) references teams(organization_id, id)
);

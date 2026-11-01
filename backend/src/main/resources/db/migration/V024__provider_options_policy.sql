create table provider_deployment_policy (
 id smallint primary key check (id = 1),
 revision bigint not null check (revision > 0),
 openai_enabled boolean not null,
 anthropic_enabled boolean not null,
 gemini_enabled boolean not null,
 created_at timestamptz not null,
 updated_at timestamptz not null,
 check (openai_enabled or anthropic_enabled or gemini_enabled),
 check (updated_at >= created_at)
);

create table provider_read_admission (
 organization_id uuid not null references organizations(id) on delete cascade,
 actor_id uuid not null references human_accounts(id) on delete cascade,
 recent_requests varchar(4096) not null,
 updated_at timestamptz not null,
 primary key (organization_id, actor_id)
);

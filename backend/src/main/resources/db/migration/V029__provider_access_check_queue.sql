-- Durable Access Check acceptance and dispatch intent. No worker grants or provider calls.
create table provider_access_checks (
    id uuid primary key,
    organization_id uuid not null,
    connection_id uuid not null,
    actor_id uuid not null references human_accounts(id),
    actor_credential_version bigint not null check (actor_credential_version >= 0),
    connection_revision bigint not null check (connection_revision > 0),
    credential_revision bigint not null check (
        credential_revision > 0 and credential_revision <= connection_revision
    ),
    policy_revision bigint not null check (policy_revision > 0),
    accepted_at timestamptz not null,
    queue_expires_at timestamptz not null,
    constraint provider_access_check_queue_window check (queue_expires_at > accepted_at),
    constraint provider_access_check_connection_fk foreign key (organization_id, connection_id)
        references provider_connections(organization_id, id),
    constraint provider_access_check_binding unique (
        organization_id, connection_id, id, connection_revision, credential_revision, policy_revision
    )
);

create index provider_access_checks_actor_window
    on provider_access_checks (organization_id, actor_id, accepted_at);
create index provider_access_checks_org_window
    on provider_access_checks (organization_id, accepted_at);

-- One outstanding dispatch intent per connection. Bind every revision to the accepted check.
create table provider_access_check_outbox (
    organization_id uuid not null,
    connection_id uuid not null,
    check_id uuid primary key,
    connection_revision bigint not null,
    credential_revision bigint not null,
    policy_revision bigint not null,
    constraint provider_access_check_outbox_connection_unique unique (organization_id, connection_id),
    constraint provider_access_check_outbox_binding_fk foreign key (
        organization_id, connection_id, check_id, connection_revision, credential_revision, policy_revision
    ) references provider_access_checks (
        organization_id, connection_id, id, connection_revision, credential_revision, policy_revision
    )
);

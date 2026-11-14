-- Check receipts pin an existing event without reserving it as a configuration mutation.
alter table provider_access_checks
    add constraint provider_access_check_receipt_scope unique (organization_id, connection_id, actor_id, id);

create table provider_access_check_receipts (
    organization_id uuid not null,
    connection_id uuid not null,
    actor_id uuid not null references human_accounts(id),
    idempotency_key uuid not null,
    command_id uuid not null unique,
    check_id uuid not null unique,
    snapshot_event_id uuid not null,
    creation_event_id uuid not null,
    digest_version varchar(32) not null check (digest_version = 'PROVIDER_COMMAND_V1'),
    key_version varchar(256) not null,
    command_digest varchar(44) not null,
    primary key (organization_id, actor_id, idempotency_key),
    foreign key (organization_id, connection_id, actor_id, check_id)
        references provider_access_checks(organization_id, connection_id, actor_id, id),
    foreign key (organization_id, connection_id, snapshot_event_id)
        references provider_connection_events(organization_id, connection_id, id),
    foreign key (organization_id, connection_id, creation_event_id)
        references provider_connection_events(organization_id, connection_id, id)
);

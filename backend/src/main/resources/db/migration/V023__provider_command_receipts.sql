alter table provider_connection_events add constraint provider_connection_events_scoped_id
 unique (organization_id,connection_id,id);
create table provider_command_receipts (
 organization_id uuid not null,
 actor_id uuid not null,
 idempotency_key uuid not null,
 command_id uuid not null,
 operation varchar(8) not null check (operation in ('CREATE','RENAME','ROTATE','ENABLE','DISABLE')),
 connection_id uuid not null,
 before_event_id uuid,
 after_event_id uuid not null,
 creation_event_id uuid not null,
 digest_version varchar(32) not null check (digest_version = 'PROVIDER_COMMAND_V1'),
 key_version varchar(256) not null,
 command_digest varchar(44) not null,
 recorded_at timestamptz not null,
 constraint provider_command_receipts_namespace_pk primary key (organization_id,actor_id,idempotency_key),
 constraint provider_command_receipts_command_unique unique (command_id),
 constraint provider_command_receipts_after_unique unique (after_event_id),
 constraint provider_command_receipts_organization_fk foreign key (organization_id) references organizations(id),
 constraint provider_command_receipts_actor_fk foreign key (actor_id) references human_accounts(id),
 constraint provider_command_receipts_creation_fk foreign key (organization_id,connection_id,creation_event_id)
 references provider_connection_events(organization_id,connection_id,id),
 constraint provider_command_receipts_before_fk foreign key (organization_id,connection_id,before_event_id)
 references provider_connection_events(organization_id,connection_id,id),
 constraint provider_command_receipts_after_fk foreign key (organization_id,connection_id,after_event_id)
 references provider_connection_events(organization_id,connection_id,id),
 constraint provider_command_receipts_before_shape check ((operation='CREATE' and before_event_id is null) or (operation<>'CREATE' and before_event_id is not null))
);

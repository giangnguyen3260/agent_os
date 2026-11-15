-- Agent model selection is configuration, not authority to invoke a provider.
alter table provider_model_discovery_entries add constraint provider_discovery_entry_binding_key
    unique (organization_id, connection_id, check_id, model_id);
create index provider_discovery_latest_check
    on provider_access_checks (organization_id, connection_id, check_kind, accepted_at desc, id desc);

create table agent_model_bindings (
    organization_id uuid not null,
    agent_id uuid not null,
    revision integer not null check (revision > 0),
    connection_id uuid,
    check_id uuid,
    model_id varchar(256),
    primary key (organization_id, agent_id),
    foreign key (organization_id, agent_id) references agents (organization_id, id),
    foreign key (organization_id, connection_id, check_id, model_id)
        references provider_model_discovery_entries (organization_id, connection_id, check_id, model_id),
    check (
        (connection_id is null and check_id is null and model_id is null)
        or (connection_id is not null and check_id is not null and model_id is not null)
    )
);

create table agent_model_binding_receipts (
    organization_id uuid not null,
    agent_id uuid not null,
    account_id uuid not null references human_accounts (id),
    idempotency_key uuid not null,
    body_sha256 varchar(64) not null,
    original_response text not null,
    primary key (organization_id, agent_id, account_id, idempotency_key),
    foreign key (organization_id, agent_id) references agents (organization_id, id)
);

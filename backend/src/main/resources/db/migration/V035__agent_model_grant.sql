-- A grant pins the monotonic binding revision reviewed by an administrator.
-- It is a prerequisite for a future claim-gated execution path.
create table agent_model_grants (
    organization_id uuid not null,
    agent_id uuid not null,
    revision integer not null check (revision > 0),
    binding_revision integer not null check (binding_revision > 0),
    enabled boolean not null,
    primary key (organization_id, agent_id),
    foreign key (organization_id, agent_id) references agent_model_bindings (organization_id, agent_id)
);

create table agent_model_grant_receipts (
    organization_id uuid not null,
    agent_id uuid not null,
    account_id uuid not null references human_accounts (id),
    idempotency_key uuid not null,
    expected_revision integer not null check (expected_revision between 0 and 2147483646),
    expected_binding_revision integer not null check (expected_binding_revision > 0),
    requested_enabled boolean not null,
    response_can_grant boolean not null,
    primary key (organization_id, agent_id, account_id, idempotency_key),
    foreign key (organization_id, agent_id) references agent_model_grants (organization_id, agent_id)
);

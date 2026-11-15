-- Share the outstanding-check and quota boundaries with Access Check.
alter table provider_access_checks add column check_kind varchar(15) default 'ACCESS_CHECK' not null;
alter table provider_access_checks add constraint provider_check_kind
    check (check_kind in ('ACCESS_CHECK', 'MODEL_DISCOVERY'));
alter table provider_access_checks add constraint provider_check_dispatch_identity unique (
    organization_id, connection_id, id, check_kind,
    worker_owner_id, dispatch_token, started_at, dispatch_deadline
);
alter table provider_access_checks add constraint provider_check_outcome_identity unique (
    organization_id, connection_id, id, check_kind, outcome
);

create table provider_model_discovery_pages (
    organization_id uuid not null,
    connection_id uuid not null,
    check_id uuid not null,
    check_kind varchar(15) default 'MODEL_DISCOVERY' not null check (check_kind = 'MODEL_DISCOVERY'),
    page_ordinal integer not null check (page_ordinal between 1 and 10),
    worker_owner_id uuid not null,
    dispatch_token uuid not null,
    started_at timestamptz not null,
    dispatch_deadline timestamptz not null,
    authorized_at timestamptz not null,
    request_deadline timestamptz not null,
    primary key (check_id, page_ordinal),
    constraint provider_discovery_page_dispatch_fk foreign key (
        organization_id, connection_id, check_id, check_kind,
        worker_owner_id, dispatch_token, started_at, dispatch_deadline
    ) references provider_access_checks (
        organization_id, connection_id, id, check_kind,
        worker_owner_id, dispatch_token, started_at, dispatch_deadline
    ),
    constraint provider_discovery_page_window check (
        authorized_at >= started_at
        and request_deadline > authorized_at
        and request_deadline <= dispatch_deadline
        and request_deadline <= authorized_at + interval '10' second
    )
);

create table provider_model_discovery_snapshots (
    organization_id uuid not null,
    connection_id uuid not null,
    check_id uuid primary key,
    check_kind varchar(15) default 'MODEL_DISCOVERY' not null check (check_kind = 'MODEL_DISCOVERY'),
    outcome varchar(10) default 'ACCESSIBLE' not null check (outcome = 'ACCESSIBLE'),
    catalogue_state varchar(9) not null check (catalogue_state in ('COMPLETE', 'TRUNCATED')),
    unique (organization_id, connection_id, check_id),
    constraint provider_discovery_snapshot_result_fk foreign key (
        organization_id, connection_id, check_id, check_kind, outcome
    ) references provider_access_checks (organization_id, connection_id, id, check_kind, outcome)
);

create table provider_model_discovery_entries (
    organization_id uuid not null,
    connection_id uuid not null,
    check_id uuid not null,
    model_ordinal integer not null check (model_ordinal between 1 and 1000),
    model_id varchar(256) not null check (char_length(trim(model_id)) > 0),
    display_name varchar(255),
    primary key (check_id, model_ordinal),
    unique (check_id, model_id),
    constraint provider_discovery_entry_snapshot_fk foreign key (organization_id, connection_id, check_id)
        references provider_model_discovery_snapshots (organization_id, connection_id, check_id)
);

-- An acceptance event records scope and time without credential or dispatch authority data.
create table provider_access_check_events (
    check_id uuid primary key,
    organization_id uuid not null,
    connection_id uuid not null,
    actor_id uuid not null,
    event_type varchar(8) not null check (event_type = 'ACCEPTED'),
    occurred_at timestamptz not null,
    foreign key (organization_id, connection_id, actor_id, check_id)
        references provider_access_checks(organization_id, connection_id, actor_id, id)
);

insert into provider_access_check_events
    (check_id, organization_id, connection_id, actor_id, event_type, occurred_at)
select c.id, c.organization_id, c.connection_id, c.actor_id, 'ACCEPTED', c.accepted_at
from provider_access_checks c
join provider_access_check_receipts r on r.check_id = c.id;

alter table provider_access_checks add column worker_owner_id uuid;
alter table provider_access_checks add column dispatch_token uuid unique;
alter table provider_access_checks add column started_at timestamptz;
alter table provider_access_checks add column dispatch_deadline timestamptz;
alter table provider_access_checks add column authorized_at timestamptz;
alter table provider_access_checks add column completed_at timestamptz;
alter table provider_access_checks add column outcome varchar(10);
alter table provider_access_checks add column error_code varchar(32);

alter table provider_access_checks add constraint provider_access_check_dispatch_shape check (
    (worker_owner_id is null and dispatch_token is null and started_at is null
        and dispatch_deadline is null and authorized_at is null)
    or (worker_owner_id is not null and dispatch_token is not null and started_at is not null
        and dispatch_deadline is not null and started_at >= accepted_at
        and started_at < queue_expires_at and dispatch_deadline > started_at
        and (authorized_at is null or (authorized_at >= started_at and authorized_at < dispatch_deadline)))
);

alter table provider_access_checks add constraint provider_access_check_result_shape check (
    (completed_at is null and outcome is null and error_code is null)
    or (completed_at is not null and completed_at >= accepted_at
        and (started_at is null or completed_at >= started_at) and outcome is not null
        and ((outcome = 'ACCESSIBLE' and error_code is null and authorized_at is not null)
            or (outcome in ('DENIED','FAILED','UNKNOWN','SUPERSEDED') and error_code is not null)))
);

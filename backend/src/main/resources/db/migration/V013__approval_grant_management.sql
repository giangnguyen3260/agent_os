-- Restricted management ledger; legacy grants deliberately receive no invented provenance.
alter table approval_grants add constraint uq_approval_grants_scope_id unique(organization_id,account_id,id);
create table approval_grant_ledgers (
 organization_id uuid not null references organizations(id), account_id uuid not null references human_accounts(id),
 revision integer not null check(revision between 0 and 2147483647), primary key(organization_id,account_id)
);
create table approval_grant_management_events (
 id uuid primary key, organization_id uuid not null references organizations(id), account_id uuid not null references human_accounts(id),
 grant_id uuid not null, actor_id uuid not null references human_accounts(id), actor_name varchar(255) not null,
 operation varchar(6) not null check(operation in ('ISSUE','REVOKE')), reason text not null,
 revision integer not null check(revision>0), recorded_at timestamptz not null, expires_at timestamptz not null, revoked_at timestamptz,
 foreign key(organization_id,account_id,grant_id) references approval_grants(organization_id,account_id,id),
 unique(organization_id,account_id,revision), unique(organization_id,actor_id,id),
 check((operation='ISSUE' and revoked_at is null) or (operation='REVOKE' and revoked_at=recorded_at))
);
create index idx_grant_management_history on approval_grant_management_events(organization_id,account_id,grant_id,recorded_at desc,id desc);
create table approval_grant_management_receipts (
 organization_id uuid not null, actor_id uuid not null, idempotency_key uuid not null,
 fingerprint varchar(64) not null, event_id uuid not null,
 primary key(organization_id,actor_id,idempotency_key),
 foreign key(organization_id,actor_id,event_id) references approval_grant_management_events(organization_id,actor_id,id)
);

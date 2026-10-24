-- Restricted role provenance is retained independently of live membership.
create table member_role_ledgers (
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 revision integer not null check(revision between 0 and 2147483647),
 primary key(organization_id,account_id)
);
create table member_role_events (
 id uuid primary key,
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 actor_id uuid not null references human_accounts(id),
 actor_name varchar(255) not null,
 before_role varchar(6) not null check(before_role in ('ADMIN','MEMBER')),
 after_role varchar(6) not null check(after_role in ('ADMIN','MEMBER')),
 reason text not null,
 revision integer not null check(revision>0),
 recorded_at timestamptz not null,
 check(before_role<>after_role),
 unique(organization_id,account_id,revision),
 unique(organization_id,actor_id,id)
);
create index idx_member_role_history on member_role_events(organization_id,account_id,recorded_at desc,id desc);
create table member_role_receipts (
 organization_id uuid not null references organizations(id),
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 fingerprint varchar(64) not null,
 event_id uuid not null,
 primary key(organization_id,actor_id,idempotency_key),
 foreign key(organization_id,actor_id,event_id) references member_role_events(organization_id,actor_id,id)
);

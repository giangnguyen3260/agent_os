create table human_accounts (
 id uuid primary key,
 email varchar(254) not null unique,
 display_name varchar(255) not null,
 password_hash varchar(100) not null,
 enabled boolean not null default true,
 created_at timestamptz not null default current_timestamp
);
create table organization_memberships (
 account_id uuid not null references human_accounts(id),
 organization_id uuid not null references organizations(id),
 role varchar(20) not null check (role in ('ADMIN', 'MEMBER')),
 primary key (account_id, organization_id)
);
create table authentication_attempts (
 account_key varchar(64) primary key,
 failures integer not null default 0,
 window_start timestamptz not null default current_timestamp
);
create table authentication_audit (
 id uuid primary key,
 account_id uuid references human_accounts(id),
 event_type varchar(40) not null,
 created_at timestamptz not null default current_timestamp
);
create table spring_session (
 primary_id char(36) primary key,
 session_id char(36) not null,
 creation_time bigint not null,
 last_access_time bigint not null,
 max_inactive_interval integer not null,
 expiry_time bigint not null,
 principal_name varchar(100)
);
create unique index spring_session_ix1 on spring_session(session_id);
create index spring_session_ix2 on spring_session(expiry_time);
create index spring_session_ix3 on spring_session(principal_name);
create table spring_session_attributes (
 session_primary_id char(36) not null references spring_session(primary_id) on delete cascade,
 attribute_name varchar(200) not null,
 attribute_bytes bytea not null,
 primary key (session_primary_id, attribute_name)
);

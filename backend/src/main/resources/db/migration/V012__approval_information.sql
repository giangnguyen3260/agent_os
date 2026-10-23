-- Append-only information exchange; no action payload or runtime state changes.
alter table approval_requests add column information_revision bigint not null default 0
 check(information_revision between 0 and 9007199254740991);
create table approval_information_entries (
 id uuid primary key, approval_id uuid not null references approval_requests(id),
 revision bigint not null check(revision between 1 and 40),
 kind varchar(8) not null check(kind in ('QUESTION','ANSWER')),
 actor_id uuid not null references human_accounts(id), actor_name varchar(255) not null,
 text varchar(2000) not null, evidence varchar(1000), created_at timestamptz not null,
 question_id uuid references approval_information_entries(id),
 unique(approval_id,revision),
 check((kind='QUESTION' and evidence is null and question_id is null) or
       (kind='ANSWER' and question_id is not null))
);
create table approval_information_receipts (
 organization_id uuid not null references organizations(id), actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null, fingerprint varchar(64) not null,
 approval_id uuid not null references approval_requests(id), revision bigint not null,
 status varchar(20) not null check(status in ('PENDING','NEEDS_INFORMATION')),
 entry_id uuid not null references approval_information_entries(id), operation varchar(8) not null check(operation in ('QUESTION','ANSWER')),
 team_id uuid not null, action_type varchar(100) not null, risk_level varchar(10) not null,
 foreign key(organization_id,team_id) references teams(organization_id,id),
 primary key(organization_id,actor_id,idempotency_key)
);

-- Trusted internal producers only; deliberately no grant or snapshot public writer.
alter table approval_requests add column requester_human_id uuid references human_accounts(id);
create table approval_action_heads (
 action_id uuid primary key, organization_id uuid not null references organizations(id),
 team_id uuid not null, revision bigint not null check(revision>0),
 payload_sha256 varchar(64) not null, active boolean not null default true,
 foreign key(organization_id,team_id) references teams(organization_id,id)
);
create table approval_snapshots (
 approval_id uuid primary key references approval_requests(id), action_id uuid not null references approval_action_heads(action_id),
 version bigint not null check(version>0), head_revision bigint not null check(head_revision>0),
 canonical_payload text not null, payload_sha256 varchar(64) not null,
 expires_at timestamptz not null, invalidated_at timestamptz
);
create table approval_grants (
 id uuid primary key, organization_id uuid not null references organizations(id), team_id uuid not null,
 account_id uuid not null references human_accounts(id), action_type varchar(100) not null,
 risk_level varchar(10) not null check(risk_level in ('LOW','MEDIUM','HIGH','CRITICAL')),
 expires_at timestamptz not null, revoked_at timestamptz,
 foreign key(organization_id,team_id) references teams(organization_id,id)
);
create index idx_approval_grants_scope on approval_grants(organization_id,account_id,team_id,action_type,risk_level);
create table approval_decisions (
 id uuid primary key, approval_id uuid not null unique references approval_requests(id),
 status varchar(10) not null check(status in ('APPROVED','REJECTED')),
 actor_id uuid not null references human_accounts(id), actor_name varchar(255) not null,
 reason varchar(2000), snapshot_version bigint not null, snapshot_sha256 varchar(64) not null,
 decided_at timestamptz not null
);
create table approval_receipts (
 organization_id uuid not null references organizations(id), actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null, fingerprint varchar(64) not null,
 decision_id uuid not null references approval_decisions(id),
 team_id uuid not null, action_type varchar(100) not null, risk_level varchar(10) not null,
 foreign key(organization_id,team_id) references teams(organization_id,id),
 primary key(organization_id,actor_id,idempotency_key)
);

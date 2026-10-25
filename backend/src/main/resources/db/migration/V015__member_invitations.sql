-- Restricted invitation capability and provenance. No raw token or message is persisted.
create table organization_invitations (
 id uuid primary key, organization_id uuid not null references organizations(id),
 email varchar(254) not null, status varchar(8) not null check(status in ('PENDING','ACCEPTED','REVOKED')),
 revision integer not null check(revision>0), generation integer not null check(generation>0),
 issuer_id uuid not null references human_accounts(id), issuer_version bigint not null,
 token_digest varchar(64) unique, created_at timestamptz not null, expires_at timestamptz not null,
 last_requested_at timestamptz not null, accepted_at timestamptz, revoked_at timestamptz,
 unique(organization_id,id), check(expires_at>created_at),
 check((status='PENDING' and accepted_at is null and revoked_at is null) or
 (status='ACCEPTED' and accepted_at is not null and revoked_at is null and token_digest is null) or
 (status='REVOKED' and revoked_at is not null and accepted_at is null and token_digest is null))
);
create index idx_invitation_list on organization_invitations(organization_id,created_at desc,id desc);
create index idx_invitation_email on organization_invitations(organization_id,email,status,expires_at);
create table invitation_mail_jobs (
 id uuid primary key, invitation_id uuid not null references organization_invitations(id),
 generation integer not null check(generation>0), state varchar(9) not null check(state in ('QUEUED','SENT','FAILED','CANCELLED')),
 attempts integer not null default 0 check(attempts between 0 and 5), available_at timestamptz not null,
 unique(invitation_id,generation)
);
create index idx_invitation_mail_pending on invitation_mail_jobs(state,available_at);
create table invitation_events (
 id uuid primary key, organization_id uuid not null, invitation_id uuid not null,
 actor_id uuid not null references human_accounts(id), actor_name varchar(255) not null,
 operation varchar(20) not null check(operation in ('CREATE_INVITATION','RESEND_INVITATION','REVOKE_INVITATION','ACCEPT_INVITATION')),
 revision integer not null check(revision>0), recorded_at timestamptz not null,
 email varchar(254) not null, expires_at timestamptz not null,
 membership_created boolean, role_revision integer check(role_revision>0),
 foreign key(organization_id,invitation_id) references organization_invitations(organization_id,id),
 unique(organization_id,actor_id,id), unique(invitation_id,revision)
);
create index idx_invitation_history on invitation_events(invitation_id,recorded_at desc,id desc);
create table invitation_receipts (
 organization_id uuid not null, actor_id uuid not null, idempotency_key uuid not null,
 fingerprint varchar(64) not null, event_id uuid not null,
 primary key(organization_id,actor_id,idempotency_key),
 foreign key(organization_id,actor_id,event_id) references invitation_events(organization_id,actor_id,id)
);
create table invitation_email_locks (email_digest varchar(64) primary key);

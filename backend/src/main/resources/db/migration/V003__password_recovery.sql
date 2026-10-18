alter table human_accounts add column credential_version bigint not null default 0;
create table recovery_rate_buckets (
 email_digest varchar(64) primary key,
 requested_at timestamptz not null
);
create table password_reset_tokens (
 token_digest varchar(64) primary key,
 account_id uuid not null references human_accounts(id) on delete cascade,
 expires_at timestamptz not null
);
create index password_reset_account_ix on password_reset_tokens(account_id);
create table recovery_mail_jobs (
 id uuid primary key,
 account_id uuid not null references human_accounts(id) on delete cascade,
 kind varchar(20) not null default 'RESET' check (kind in ('RESET','PASSWORD_CHANGED')),
 state varchar(12) not null default 'PENDING' check (state in ('PENDING','SENT','FAILED')),
 attempts integer not null default 0,
 available_at timestamptz not null default current_timestamp
);
create index recovery_mail_pending_ix on recovery_mail_jobs(state, available_at);

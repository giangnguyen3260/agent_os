create table mission_execution_control_receipts (
 organization_id uuid not null,
 team_id uuid not null,
 mission_id uuid not null,
 actor_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 action varchar(20) not null check(action in ('PAUSE','RESUME','REQUEST_CANCEL','ACK_CANCEL')),
 command_sha256 varchar(64) not null check(length(command_sha256)=64),
 source_fingerprint varchar(64) not null check(length(source_fingerprint)=64),
 source_control_revision bigint not null check(source_control_revision>0),
 result_control_revision bigint not null check(result_control_revision=source_control_revision+1),
 snapshot_json text not null check(octet_length(snapshot_json)<=2097152),
 snapshot_sha256 varchar(64) not null check(length(snapshot_sha256)=64),
 response_json text not null check(octet_length(response_json)<=65536),
 response_sha256 varchar(64) not null check(length(response_sha256)=64),
 created_at timestamptz not null default current_timestamp,
 primary key(organization_id,actor_id,idempotency_key),
 foreign key(organization_id,team_id,mission_id) references missions(organization_id,team_id,id)
);
create table execution_pause_unsent_releases (
 dispatch_id uuid primary key references execution_start_dispatch(id),
 run_id uuid not null,
 attempt_id uuid not null,
 fence bigint not null,
 source_run_revision bigint not null check(source_run_revision>=0),
 result_run_revision bigint not null check(result_run_revision=source_run_revision+1),
 created_at timestamptz not null default current_timestamp,
 foreign key(run_id,attempt_id,fence) references execution_claims(run_id,attempt_id,fence)
);

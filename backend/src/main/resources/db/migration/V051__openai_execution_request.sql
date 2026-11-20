-- Immutable prepared request for one admitted dispatch. No provider credentials.
create table execution_openai_requests (
    dispatch_id uuid primary key references execution_start_dispatch(id),
    connection_id uuid not null references provider_connections(id),
    credential_revision bigint not null check (credential_revision > 0),
    model_id varchar(256) not null,
    request_body bytea not null check (octet_length(request_body) between 1 and 32768),
    preflight_sha256 varchar(64) not null check (char_length(preflight_sha256)=64),
    request_sha256 varchar(64) not null check (char_length(request_sha256)=64),
    max_output_tokens integer not null check (max_output_tokens between 1 and 4096),
    maximum_microusd bigint not null check (maximum_microusd > 0),
    input_rate bigint not null check (input_rate > 0),
    output_rate bigint not null check (output_rate > 0)
);

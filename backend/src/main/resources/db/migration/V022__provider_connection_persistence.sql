create table provider_credential_versions (
 organization_id uuid not null references organizations(id),
 connection_id uuid not null,
 provider varchar(20) not null check (provider in ('OPENAI','ANTHROPIC','GOOGLE_GEMINI')),
 credential_revision bigint not null check (credential_revision > 0),
 external_locator varchar(256) not null,
 external_key_version varchar(256) not null,
 hmac_key_version varchar(256) not null,
 hmac_domain_version varchar(2) not null check (hmac_domain_version = 'v2'),
 verifier varchar(44) not null,
 created_at timestamptz not null,
 primary key (organization_id, connection_id, provider, credential_revision)
);
create table provider_connections (
 id uuid primary key,
 organization_id uuid not null references organizations(id),
 provider varchar(20) not null,
 mode varchar(24) not null,
 name varchar(400) not null,
 workspace varchar(256),
 state varchar(8) not null check (state in ('ENABLED','DISABLED')),
 configuration_revision bigint not null check (configuration_revision > 0),
 credential_revision bigint not null check (credential_revision > 0),
 created_at timestamptz not null,
 updated_at timestamptz not null,
 unique (organization_id,id),
 check (credential_revision <= configuration_revision),
 check ((provider='OPENAI' and mode='OPENAI_API_KEY' and workspace is null)
 or (provider='ANTHROPIC' and mode='ANTHROPIC_API_KEY')
 or (provider='GOOGLE_GEMINI' and mode='GEMINI_API_KEY' and workspace is null)),
 constraint provider_connections_current_credential_fk foreign key (organization_id,id,provider,credential_revision)
 references provider_credential_versions(organization_id,connection_id,provider,credential_revision)
);
create index provider_connections_page on provider_connections(organization_id,created_at desc,id desc);
create table provider_connection_events (
 id uuid primary key,
 organization_id uuid not null,
 connection_id uuid not null,
 configuration_revision bigint not null check (configuration_revision > 0),
 credential_revision bigint not null check (credential_revision > 0),
 kind varchar(8) not null check (kind in ('CREATE','RENAME','ROTATE','ENABLE','DISABLE')),
 provider varchar(20) not null,
 mode varchar(24) not null,
 name varchar(400) not null,
 workspace varchar(256),
 state varchar(8) not null check (state in ('ENABLED','DISABLED')),
 actor_id uuid not null references human_accounts(id),
 created_at timestamptz not null,
 unique (organization_id,connection_id,configuration_revision),
 foreign key (organization_id,connection_id) references provider_connections(organization_id,id)
);

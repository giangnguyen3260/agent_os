-- Storage foundation only: no producer, success seed, trigger, function or runtime grant.
-- Operator prerequisite: tables belong to a separate migration principal; runtime
-- identities are neither owners nor superusers and cannot assume the owner role.
-- agentos_provider_evidence_reader: SELECT only on versions and these three tables.
-- agentos_provider_evidence_publisher: no generic evidence DML until a separately
-- reviewed trusted publication protocol exists. Neither runtime role may UPDATE,
-- DELETE or TRUNCATE credential versions (including column grants/inherited roles).
-- Privileged migration/test owners intentionally retain corruption-fixture access.

alter table provider_credential_versions add constraint provider_credential_versions_evidence_binding
 unique (organization_id, connection_id, provider, credential_revision,
         external_locator, external_key_version, hmac_key_version, hmac_domain_version);

-- A trusted operator advances/replaces this fence on observer replacement/restore.
-- No FK from health: retaining old observations must not prevent invalidation.
-- Readers must match all three publisher fields to the current row. Existence of
-- a row does not grant publication authority or attest an external check.
create table provider_evidence_publishers (
 publisher_id uuid primary key,
 publisher_epoch bigint not null check (publisher_epoch > 0),
 publisher_fence uuid not null,
 unique (publisher_fence)
);

create table provider_credential_integrity (
 organization_id uuid not null,
 connection_id uuid not null,
 provider varchar(20) not null,
 credential_revision bigint not null check (credential_revision > 0),
 external_locator varchar(256) not null,
 external_key_version varchar(256) not null,
 hmac_key_version varchar(256) not null,
 hmac_domain_version varchar(2) not null,
 state varchar(17) not null check (state in ('VERIFIED_AT_WRITE','FAILED')),
 recorded_at timestamp(6) with time zone not null,
 publication_revision bigint not null check (publication_revision > 0),
 primary key (organization_id, connection_id, provider, credential_revision),
 constraint provider_credential_integrity_binding_fk foreign key
  (organization_id, connection_id, provider, credential_revision,
   external_locator, external_key_version, hmac_key_version, hmac_domain_version)
  references provider_credential_versions
  (organization_id, connection_id, provider, credential_revision,
   external_locator, external_key_version, hmac_key_version, hmac_domain_version),
 -- The supported UTC civil range also rejects PostgreSQL +/-infinity without
 -- PostgreSQL-only isfinite(), keeping the retained H2 migration path portable.
 check (recorded_at >= cast('0001-01-01 00:00:00+00' as timestamp with time zone)
    and recorded_at <= cast('9999-12-31 23:59:59.999999+00' as timestamp with time zone))
);

create table provider_credential_health (
 organization_id uuid not null,
 connection_id uuid not null,
 provider varchar(20) not null,
 credential_revision bigint not null check (credential_revision > 0),
 external_locator varchar(256) not null,
 external_key_version varchar(256) not null,
 hmac_key_version varchar(256) not null,
 hmac_domain_version varchar(2) not null,
 state varchar(11) not null check (state in ('USABLE','UNAVAILABLE')),
 observed_at timestamp(6) with time zone not null,
 published_at timestamp(6) with time zone not null,
 valid_until timestamp(6) with time zone not null,
 publication_revision bigint not null check (publication_revision > 0),
 publisher_id uuid not null,
 publisher_epoch bigint not null check (publisher_epoch > 0),
 publisher_fence uuid not null,
 primary key (organization_id, connection_id, provider, credential_revision),
 constraint provider_credential_health_binding_fk foreign key
  (organization_id, connection_id, provider, credential_revision,
   external_locator, external_key_version, hmac_key_version, hmac_domain_version)
  references provider_credential_versions
  (organization_id, connection_id, provider, credential_revision,
   external_locator, external_key_version, hmac_key_version, hmac_domain_version),
 check (observed_at >= cast('0001-01-01 00:00:00+00' as timestamp with time zone)
    and observed_at <= cast('9999-12-31 23:59:59.999999+00' as timestamp with time zone)),
 check (published_at >= cast('0001-01-01 00:00:00+00' as timestamp with time zone)
    and published_at <= cast('9999-12-31 23:59:59.999999+00' as timestamp with time zone)),
 check (valid_until >= cast('0001-01-01 00:00:00+00' as timestamp with time zone)
    and valid_until <= cast('9999-12-31 23:59:59.999999+00' as timestamp with time zone)),
 check (observed_at <= published_at and published_at < valid_until
    and valid_until <= observed_at + interval '60' second)
);

-- Pending guarded publisher responsibilities (not implemented by this DDL):
-- authenticated prepared provenance; sticky FAILED; active-version revalidation;
-- exact binding/current-fence checks; parent-first locks; captured-revision CAS;
-- strictly increasing observations/nonregressing publication time; overflow and
-- expired/future checks using a post-lock DB clock. No grants should bypass these.

-- Internal query only. Requires a reviewed least-privilege read boundary before HTTP use.
-- Bind organization, connection, then the single page-level database clock.
select exists (
  select 1
  from public.provider_connections c
  join public.provider_credential_versions v
    on (v.organization_id,v.connection_id,v.provider,v.credential_revision)
     = (c.organization_id,c.id,c.provider,c.credential_revision)
  join public.provider_credential_integrity i
    on i.organization_id=v.organization_id and
       i.connection_id=v.connection_id and
       i.provider=v.provider and
       i.credential_revision=v.credential_revision and
       i.external_locator=v.external_locator and
       i.external_key_version=v.external_key_version and
       i.hmac_key_version=v.hmac_key_version and
       i.hmac_domain_version=v.hmac_domain_version
  join public.provider_credential_health h
    on h.organization_id=v.organization_id and
       h.connection_id=v.connection_id and
       h.provider=v.provider and
       h.credential_revision=v.credential_revision and
       h.external_locator=v.external_locator and
       h.external_key_version=v.external_key_version and
       h.hmac_key_version=v.hmac_key_version and
       h.hmac_domain_version=v.hmac_domain_version
  join public.provider_evidence_publishers f
    on (f.publisher_id,f.publisher_epoch,f.publisher_fence)
     = (h.publisher_id,h.publisher_epoch,h.publisher_fence)
  join agentos_evidence_private.integrity_provenance q
    on (q.organization_id,q.connection_id,q.provider,q.credential_revision)
     = (i.organization_id,i.connection_id,i.provider,i.credential_revision)
  join agentos_evidence_private.integrity_assertions s on s.assertion_id=q.assertion_id
  join agentos_evidence_private.publication_attempts a on a.attempt_id=s.attempt_id
  cross join (select cast(? as uuid) as org, cast(? as uuid) as connection,
                     cast(? as timestamp with time zone) as page_clock) p
  where c.organization_id=p.org and c.id=p.connection
    and agentos_evidence_private.valid_binding(v.external_locator,v.external_key_version,
        v.hmac_key_version,v.hmac_domain_version,v.verifier)
    and i.state='VERIFIED_AT_WRITE' and i.publication_revision=1
    and q.publication_revision=i.publication_revision
    and s.kind='PREPARE' and s.outcome='PREPARED_VERIFIED' and s.protocol_version=1
    and (s.organization_id,s.connection_id,s.provider,s.credential_revision,
         s.external_locator,s.external_key_version,s.hmac_key_version,s.hmac_domain_version)
      = (v.organization_id,v.connection_id,v.provider,v.credential_revision,
         v.external_locator,v.external_key_version,v.hmac_key_version,v.hmac_domain_version)
    and a.kind=s.kind and a.consumed and not a.integrity_present
    and (a.organization_id,a.connection_id,a.provider,a.credential_revision,
         a.external_locator,a.external_key_version,a.hmac_key_version,a.hmac_domain_version,
         a.publisher_id,a.publisher_epoch,a.publisher_fence,a.login_name,a.login_oid,a.observed_at)
      = (s.organization_id,s.connection_id,s.provider,s.credential_revision,
         s.external_locator,s.external_key_version,s.hmac_key_version,s.hmac_domain_version,
         s.publisher_id,s.publisher_epoch,s.publisher_fence,s.login_name,s.login_oid,s.captured_at)
    and a.verifier=v.verifier
    and s.accepted_at=i.recorded_at and s.captured_at<=s.accepted_at
    and s.accepted_at<a.deadline and s.accepted_at<=p.page_clock
    and h.state='USABLE' and h.publication_revision>0
    and pg_catalog.isfinite(p.page_clock)
    and h.observed_at<=h.published_at and h.published_at<=p.page_clock
    and p.page_clock<h.valid_until and h.valid_until<=h.observed_at+interval '60 seconds'
)

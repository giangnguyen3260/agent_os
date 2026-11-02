-- Operator-installed PostgreSQL 16 guarded publication. Not a Flyway migration.
-- Trusted LOGIN assertions are not cryptographic proof of external work.
-- Requires unchanged V025 and provider-evidence-roles.sql; no LOGIN/password creation.
BEGIN;
DO $install$
BEGIN
 IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='agentos_provider_evidence_owner')
 OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname='agentos_evidence_private') THEN
  RAISE EXCEPTION 'Publication installation requires fresh reviewed owner and schema';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='agentos_provider_evidence_publisher'
   AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit
   AND NOT rolreplication AND NOT rolbypassrls) THEN
  RAISE EXCEPTION 'Reviewed base publisher capability required';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles r
 ON r.oid=m.member WHERE r.rolname='agentos_provider_evidence_publisher')
 OR pg_catalog.has_schema_privilege('agentos_provider_evidence_publisher','public','CREATE')
 OR EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname IN ('organizations','provider_connections',
 'provider_credential_versions','provider_evidence_publishers','provider_credential_integrity',
 'provider_credential_health') AND
 (c.relowner=(SELECT r.oid FROM pg_catalog.pg_roles r WHERE r.rolname='agentos_provider_evidence_publisher')
 OR pg_catalog.has_table_privilege('agentos_provider_evidence_publisher',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
 OR pg_catalog.has_any_column_privilege('agentos_provider_evidence_publisher',c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
  RAISE EXCEPTION 'Publisher retains unreviewed effective privileges';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname='agentos_provider_evidence_reader'
 AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit
 AND NOT rolreplication AND NOT rolbypassrls) THEN
  RAISE EXCEPTION 'Reviewed base reader capability required';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles r
 ON r.oid=m.member WHERE r.rolname='agentos_provider_evidence_reader')
 OR pg_catalog.has_schema_privilege('agentos_provider_evidence_reader','public','CREATE')
 OR EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname IN ('organizations','provider_connections',
 'provider_credential_versions','provider_evidence_publishers','provider_credential_integrity',
 'provider_credential_health') AND
 (c.relowner=(SELECT r.oid FROM pg_catalog.pg_roles r WHERE r.rolname='agentos_provider_evidence_reader')
 OR pg_catalog.has_table_privilege('agentos_provider_evidence_reader',c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
 OR pg_catalog.has_any_column_privilege('agentos_provider_evidence_reader',c.oid,'INSERT,UPDATE,REFERENCES'))) THEN
  RAISE EXCEPTION 'Reader retains unreviewed effective privileges';
 END IF;
 -- PUBLIC CREATE is unsafe even when it is unrelated to the capability roles.
 IF EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n,
 pg_catalog.aclexplode(COALESCE(n.nspacl,pg_catalog.acldefault('n',n.nspowner))) a
 WHERE n.nspname='public' AND a.grantee=0 AND a.privilege_type='CREATE') THEN
  RAISE EXCEPTION 'Untrusted public schema CREATE must be removed by operator';
 END IF;
END
$install$;
CREATE ROLE agentos_provider_evidence_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
 NOREPLICATION NOBYPASSRLS NOINHERIT;
CREATE SCHEMA agentos_evidence_private AUTHORIZATION agentos_provider_evidence_owner;
REVOKE ALL ON SCHEMA agentos_evidence_private FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agentos_provider_evidence_owner
 REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO agentos_provider_evidence_owner;
GRANT SELECT(id), UPDATE(id), REFERENCES(id) ON public.organizations TO agentos_provider_evidence_owner;
GRANT SELECT(id,organization_id,provider,credential_revision), UPDATE(id)
 ON public.provider_connections TO agentos_provider_evidence_owner;
GRANT SELECT(organization_id,connection_id,provider,credential_revision,external_locator,
 external_key_version,hmac_key_version,hmac_domain_version,verifier)
 ON public.provider_credential_versions TO agentos_provider_evidence_owner;
GRANT SELECT, UPDATE(publisher_id), REFERENCES(publisher_id) ON public.provider_evidence_publishers
 TO agentos_provider_evidence_owner;
GRANT SELECT,INSERT,UPDATE ON public.provider_credential_integrity,public.provider_credential_health
 TO agentos_provider_evidence_owner;
SET LOCAL ROLE agentos_provider_evidence_owner;
CREATE TABLE agentos_evidence_private.publisher_principals (
 publisher_id pg_catalog.uuid PRIMARY KEY REFERENCES public.provider_evidence_publishers(publisher_id),
 login_name pg_catalog.name NOT NULL, login_oid pg_catalog.oid NOT NULL,
 organization_id pg_catalog.uuid NOT NULL REFERENCES public.organizations(id),
 enabled pg_catalog.bool NOT NULL,
 UNIQUE(login_name,login_oid,organization_id)
);
CREATE TABLE agentos_evidence_private.publisher_permissions (
 publisher_id pg_catalog.uuid NOT NULL REFERENCES agentos_evidence_private.publisher_principals(publisher_id),
 provider pg_catalog.text NOT NULL CHECK(provider IN ('OPENAI','ANTHROPIC','GOOGLE_GEMINI')),
 kind pg_catalog.text NOT NULL CHECK(kind IN ('HEALTH','PREPARE','INTEGRITY_FAILURE')),
 PRIMARY KEY(publisher_id,provider,kind)
);
CREATE TABLE agentos_evidence_private.publication_attempts (
 attempt_id pg_catalog.uuid PRIMARY KEY, kind pg_catalog.text NOT NULL
  CHECK(kind IN ('HEALTH','PREPARE','INTEGRITY_FAILURE')),
 login_name pg_catalog.name NOT NULL, login_oid pg_catalog.oid NOT NULL,
 publisher_id pg_catalog.uuid NOT NULL, publisher_epoch pg_catalog.int8 NOT NULL CHECK(publisher_epoch>0),
 publisher_fence pg_catalog.uuid NOT NULL,
 organization_id pg_catalog.uuid NOT NULL, connection_id pg_catalog.uuid NOT NULL,
 provider pg_catalog.text NOT NULL CHECK(provider IN ('OPENAI','ANTHROPIC','GOOGLE_GEMINI')),
 credential_revision pg_catalog.int8 NOT NULL CHECK(credential_revision>0),
 external_locator pg_catalog.text NOT NULL, external_key_version pg_catalog.text NOT NULL,
 hmac_key_version pg_catalog.text NOT NULL, hmac_domain_version pg_catalog.text NOT NULL,
 verifier pg_catalog.text NOT NULL,
 integrity_present pg_catalog.bool NOT NULL, integrity_revision pg_catalog.int8,
 health_present pg_catalog.bool NOT NULL, health_revision pg_catalog.int8,
 observed_at pg_catalog.timestamptz(6) NOT NULL, deadline pg_catalog.timestamptz(6) NOT NULL,
 capture_xid pg_catalog.xid8 NOT NULL, consumed pg_catalog.bool NOT NULL,
 CHECK(integrity_present=(integrity_revision IS NOT NULL)),
 CHECK(health_present=(health_revision IS NOT NULL)),
 CHECK(integrity_revision>0), CHECK(health_revision>0),
 CHECK(observed_at>='0001-01-01 00:00:00+00'::pg_catalog.timestamptz
  AND deadline<='9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz
  AND observed_at<deadline AND deadline<=observed_at+INTERVAL '60 seconds')
);
CREATE INDEX publication_attempts_org_deadline ON agentos_evidence_private.publication_attempts
 (organization_id,deadline,attempt_id);
CREATE TABLE agentos_evidence_private.integrity_assertions (
 assertion_id pg_catalog.uuid PRIMARY KEY,
 attempt_id pg_catalog.uuid NOT NULL UNIQUE REFERENCES agentos_evidence_private.publication_attempts(attempt_id),
 kind pg_catalog.text NOT NULL CHECK(kind IN ('PREPARE','INTEGRITY_FAILURE')),
 organization_id pg_catalog.uuid NOT NULL, connection_id pg_catalog.uuid NOT NULL,
 provider pg_catalog.text NOT NULL, credential_revision pg_catalog.int8 NOT NULL,
 external_locator pg_catalog.text NOT NULL, external_key_version pg_catalog.text NOT NULL,
 hmac_key_version pg_catalog.text NOT NULL, hmac_domain_version pg_catalog.text NOT NULL,
 login_name pg_catalog.name NOT NULL, login_oid pg_catalog.oid NOT NULL,
 publisher_id pg_catalog.uuid NOT NULL, publisher_epoch pg_catalog.int8 NOT NULL,
 publisher_fence pg_catalog.uuid NOT NULL,
 captured_at pg_catalog.timestamptz(6) NOT NULL, accepted_at pg_catalog.timestamptz(6) NOT NULL,
 outcome pg_catalog.text NOT NULL CHECK(outcome IN ('PREPARED_VERIFIED','CONFIRMED_MISMATCH')),
 protocol_version pg_catalog.int4 NOT NULL CHECK(protocol_version=1),
 UNIQUE(organization_id,connection_id,provider,credential_revision,kind),
 CHECK((kind='PREPARE' AND outcome='PREPARED_VERIFIED') OR
       (kind='INTEGRITY_FAILURE' AND outcome='CONFIRMED_MISMATCH')),
 CHECK(captured_at>='0001-01-01 00:00:00+00'::pg_catalog.timestamptz
  AND accepted_at<='9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz
  AND captured_at<=accepted_at)
);
CREATE TABLE agentos_evidence_private.integrity_provenance (
 organization_id pg_catalog.uuid NOT NULL, connection_id pg_catalog.uuid NOT NULL,
 provider pg_catalog.text NOT NULL, credential_revision pg_catalog.int8 NOT NULL,
 publication_revision pg_catalog.int8 NOT NULL CHECK(publication_revision>0),
 assertion_id pg_catalog.uuid NOT NULL UNIQUE REFERENCES agentos_evidence_private.integrity_assertions(assertion_id),
 PRIMARY KEY(organization_id,connection_id,provider,credential_revision,publication_revision)
);

-- Helper privilege checks never authorize CURRENT_USER or membership alone.
CREATE FUNCTION agentos_evidence_private.authority(p_org pg_catalog.uuid,
 p_provider pg_catalog.text,p_kind pg_catalog.text)
RETURNS TABLE(id pg_catalog.uuid,epoch pg_catalog.int8,fence pg_catalog.uuid,login_oid pg_catalog.oid)
LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $authority$
 SELECT r.publisher_id,p.publisher_epoch,p.publisher_fence,u.oid
 FROM agentos_evidence_private.publisher_principals r
 JOIN public.provider_evidence_publishers p ON p.publisher_id=r.publisher_id
 JOIN agentos_evidence_private.publisher_permissions g ON g.publisher_id=r.publisher_id
 JOIN pg_catalog.pg_roles u ON u.rolname=SESSION_USER AND u.oid=r.login_oid
 WHERE r.login_name=SESSION_USER AND r.enabled AND u.rolcanlogin AND NOT u.rolsuper
 AND NOT u.rolcreatedb AND NOT u.rolcreaterole AND NOT u.rolreplication AND NOT u.rolbypassrls
 AND r.organization_id=p_org AND g.provider=p_provider AND g.kind=p_kind
$authority$;
CREATE FUNCTION agentos_evidence_private.valid_binding(p_locator pg_catalog.text,
 p_external pg_catalog.text,p_hmac pg_catalog.text,p_domain pg_catalog.text,p_verifier pg_catalog.text)
RETURNS pg_catalog.bool LANGUAGE plpgsql IMMUTABLE
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $binding$
DECLARE decoded pg_catalog.bytea;
BEGIN
 IF NOT COALESCE(pg_catalog.char_length(p_locator) BETWEEN 1 AND 256
 AND p_locator ~ '^[A-Za-z0-9_./:@-]+$'
 AND pg_catalog.char_length(p_external) BETWEEN 1 AND 256
 AND p_external ~ '^[A-Za-z0-9_./:@-]+$'
 AND pg_catalog.char_length(p_hmac) BETWEEN 1 AND 256
 AND p_hmac ~ '^[A-Za-z0-9_./:@-]+$' AND p_domain='v2'
 AND p_verifier ~ '^[A-Za-z0-9+/]{43}=$',false) THEN RETURN false; END IF;
 decoded:=pg_catalog.decode(p_verifier,'base64');
 RETURN pg_catalog.octet_length(decoded)=32 AND pg_catalog.encode(decoded,'base64')=p_verifier;
EXCEPTION WHEN data_exception THEN RETURN false;
END
$binding$;
CREATE FUNCTION agentos_evidence_private.capture_attempt(p_kind pg_catalog.text,
 p_org pg_catalog.uuid,p_connection pg_catalog.uuid,p_provider pg_catalog.text,p_revision pg_catalog.int8)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text, attempt_id pg_catalog.uuid,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, verifier pg_catalog.text,
 observed_at pg_catalog.timestamptz, deadline pg_catalog.timestamptz) LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $capture$
DECLARE
 auth record; locked_id pg_catalog.uuid; v record; c record; i public.provider_credential_integrity%ROWTYPE;
 h public.provider_credential_health%ROWTYPE; start_at pg_catalog.timestamptz;
 end_at pg_catalog.timestamptz; token pg_catalog.uuid; n pg_catalog.int8;
BEGIN
 status:='REJECTED'; reason:='AUTHORITY';
 IF pg_catalog.current_setting('transaction_isolation')<>'read committed'
 OR pg_catalog.current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION USING ERRCODE='25000', MESSAGE='Publication transaction unavailable';
 END IF;
 SELECT a.* INTO auth FROM agentos_evidence_private.authority(p_org,p_provider,p_kind) a;
 IF NOT FOUND THEN RETURN NEXT; RETURN; END IF;
 locked_id:=auth.id;
 reason:='ATTEMPT';
 IF p_org IS NULL OR p_connection IS NULL OR p_revision IS NULL OR p_revision<=0
 OR p_provider IS NULL OR p_provider NOT IN ('OPENAI','ANTHROPIC','GOOGLE_GEMINI') THEN
  RETURN NEXT; RETURN;
 END IF;
 PERFORM o.id FROM public.organizations o WHERE o.id=p_org FOR UPDATE;
 SELECT x.id,x.organization_id,x.provider,x.credential_revision INTO c
 FROM public.provider_connections x WHERE x.organization_id=p_org AND x.id=p_connection FOR UPDATE;
 SELECT x.organization_id,x.connection_id,x.provider,x.credential_revision,x.external_locator,
 x.external_key_version,x.hmac_key_version,x.hmac_domain_version,x.verifier INTO v
 FROM public.provider_credential_versions x WHERE x.organization_id=p_org AND x.connection_id=p_connection
 AND x.provider=p_provider AND x.credential_revision=p_revision;
 -- No version locking SELECT or version UPDATE privilege.
 PERFORM p.publisher_id FROM public.provider_evidence_publishers p WHERE p.publisher_id=locked_id FOR SHARE;
 IF NOT FOUND THEN reason:='AUTHORITY'; RETURN NEXT; RETURN; END IF;
 PERFORM r.publisher_id FROM agentos_evidence_private.publisher_principals r WHERE r.publisher_id=locked_id FOR SHARE;
 IF NOT FOUND THEN reason:='AUTHORITY'; RETURN NEXT; RETURN; END IF;
 PERFORM g.publisher_id FROM agentos_evidence_private.publisher_permissions g
 WHERE g.publisher_id=locked_id AND g.provider=p_provider AND g.kind=p_kind FOR SHARE;
 IF NOT FOUND THEN reason:='AUTHORITY'; RETURN NEXT; RETURN; END IF;
 reason:='AUTHORITY';
 SELECT a.* INTO auth FROM agentos_evidence_private.authority(p_org,p_provider,p_kind) a;
 IF NOT FOUND OR auth.id IS DISTINCT FROM locked_id THEN RETURN NEXT; RETURN; END IF;
 reason:='BINDING';
 IF c.id IS NULL OR c.provider IS DISTINCT FROM p_provider OR v.organization_id IS NULL
 OR (p_kind<>'INTEGRITY_FAILURE' AND c.credential_revision IS DISTINCT FROM p_revision)
 OR NOT agentos_evidence_private.valid_binding(v.external_locator,v.external_key_version,
 v.hmac_key_version,v.hmac_domain_version,v.verifier) THEN RETURN NEXT; RETURN; END IF;
 SELECT x.* INTO i FROM public.provider_credential_integrity x WHERE x.organization_id=p_org
 AND x.connection_id=p_connection AND x.provider=p_provider AND x.credential_revision=p_revision FOR UPDATE;
 SELECT x.* INTO h FROM public.provider_credential_health x WHERE x.organization_id=p_org
 AND x.connection_id=p_connection AND x.provider=p_provider AND x.credential_revision=p_revision FOR UPDATE;
 -- Count only attempts with BOTH authenticated assertion and exact provenance as pinned.
 SELECT pg_catalog.count(*) INTO n FROM agentos_evidence_private.publication_attempts a
 WHERE a.organization_id=p_org AND NOT EXISTS
 (SELECT 1 FROM agentos_evidence_private.integrity_assertions s
 JOIN agentos_evidence_private.integrity_provenance q ON q.assertion_id=s.assertion_id
 AND q.organization_id=s.organization_id AND q.connection_id=s.connection_id
 AND q.provider=s.provider AND q.credential_revision=s.credential_revision
 WHERE s.attempt_id=a.attempt_id AND a.consumed AND s.kind=a.kind
 AND ROW(s.organization_id,s.connection_id,s.provider,s.credential_revision)
 =ROW(a.organization_id,a.connection_id,a.provider,a.credential_revision));
 start_at:=pg_catalog.clock_timestamp();
 reason:='TIME';
 IF NOT pg_catalog.isfinite(start_at) OR start_at<'0001-01-01 00:00:00+00'::pg_catalog.timestamptz
 OR start_at>'9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz THEN RETURN NEXT; RETURN; END IF;
 reason:='EXHAUSTED';
 IF start_at>'9999-12-31 23:58:59.999999+00'::pg_catalog.timestamptz THEN RETURN NEXT; RETURN; END IF;
 end_at:=start_at+INTERVAL '60 seconds';
 reason:='CAPACITY'; IF n>=4096 THEN RETURN NEXT; RETURN; END IF;
 token:=pg_catalog.gen_random_uuid();
 INSERT INTO agentos_evidence_private.publication_attempts VALUES
 (token,p_kind,SESSION_USER,auth.login_oid,auth.id,auth.epoch,auth.fence,p_org,p_connection,
 p_provider,p_revision,v.external_locator,v.external_key_version,v.hmac_key_version,
 v.hmac_domain_version,v.verifier,i.publication_revision IS NOT NULL,i.publication_revision,
 h.publication_revision IS NOT NULL,h.publication_revision,start_at,end_at,
 pg_catalog.pg_current_xact_id(),false);
 status:='ACCEPTED'; reason:='NONE'; attempt_id:=token; organization_id:=p_org;
 connection_id:=p_connection; provider:=p_provider; credential_revision:=p_revision;
 external_locator:=v.external_locator; external_key_version:=v.external_key_version;
 hmac_key_version:=v.hmac_key_version; hmac_domain_version:=v.hmac_domain_version;
 verifier:=v.verifier; observed_at:=start_at; deadline:=end_at;
 RETURN NEXT;
END
$capture$;
CREATE FUNCTION agentos_evidence_private.publish_attempt(p_kind pg_catalog.text,
 p_attempt pg_catalog.uuid,p_outcome pg_catalog.text,p_present pg_catalog.bool,p_deadline pg_catalog.timestamptz)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, publisher_id pg_catalog.uuid,
 publisher_epoch pg_catalog.int8, publisher_fence pg_catalog.uuid,
 assertion_kind pg_catalog.text, publication_revision pg_catalog.int8, state pg_catalog.text,
 recorded_at pg_catalog.timestamptz, observed_at pg_catalog.timestamptz,
 published_at pg_catalog.timestamptz, valid_until pg_catalog.timestamptz) LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $publish$
DECLARE
 a agentos_evidence_private.publication_attempts%ROWTYPE;
 auth record; locked_id pg_catalog.uuid; v record; c record; i public.provider_credential_integrity%ROWTYPE;
 h public.provider_credential_health%ROWTYPE; now_at pg_catalog.timestamptz;
 until_at pg_catalog.timestamptz; next_rev pg_catalog.int8; target_state pg_catalog.text;
 assertion pg_catalog.uuid;
BEGIN
 status:='REJECTED'; reason:='AUTHORITY';
 IF pg_catalog.current_setting('transaction_isolation')<>'read committed'
 OR pg_catalog.current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION USING ERRCODE='25000', MESSAGE='Publication transaction unavailable';
 END IF;
 -- General authority before token existence; missing handles cannot reveal a foreign scope.
 IF NOT EXISTS(SELECT 1 FROM agentos_evidence_private.publisher_principals r
 JOIN pg_catalog.pg_roles u ON u.oid=r.login_oid AND u.rolname=r.login_name
 WHERE r.login_name=SESSION_USER AND r.enabled AND u.rolcanlogin AND NOT u.rolsuper
 AND NOT u.rolcreatedb AND NOT u.rolcreaterole AND NOT u.rolreplication AND NOT u.rolbypassrls) THEN
  RETURN NEXT; RETURN;
 END IF;
 SELECT x.* INTO a FROM agentos_evidence_private.publication_attempts x WHERE x.attempt_id=p_attempt;
 IF a.attempt_id IS NULL THEN reason:='ATTEMPT'; RETURN NEXT; RETURN; END IF;
 SELECT x.* INTO auth FROM agentos_evidence_private.authority(a.organization_id,a.provider,p_kind) x;
 IF NOT FOUND THEN RETURN NEXT; RETURN; END IF;
 locked_id:=auth.id;
 PERFORM o.id FROM public.organizations o WHERE o.id=a.organization_id FOR UPDATE;
 SELECT x.id,x.organization_id,x.provider,x.credential_revision INTO c
 FROM public.provider_connections x WHERE x.organization_id=a.organization_id AND x.id=a.connection_id FOR UPDATE;
 SELECT x.organization_id,x.connection_id,x.provider,x.credential_revision,x.external_locator,
 x.external_key_version,x.hmac_key_version,x.hmac_domain_version,x.verifier INTO v
 FROM public.provider_credential_versions x WHERE x.organization_id=a.organization_id
 AND x.connection_id=a.connection_id AND x.provider=a.provider AND x.credential_revision=a.credential_revision;
 PERFORM p.publisher_id FROM public.provider_evidence_publishers p WHERE p.publisher_id=locked_id FOR SHARE;
 IF NOT FOUND THEN reason:='AUTHORITY'; RETURN NEXT; RETURN; END IF;
 PERFORM r.publisher_id FROM agentos_evidence_private.publisher_principals r WHERE r.publisher_id=locked_id FOR SHARE;
 IF NOT FOUND THEN reason:='AUTHORITY'; RETURN NEXT; RETURN; END IF;
 PERFORM g.publisher_id FROM agentos_evidence_private.publisher_permissions g
 WHERE g.publisher_id=locked_id AND g.provider=a.provider AND g.kind=p_kind FOR SHARE;
 IF NOT FOUND THEN reason:='AUTHORITY'; RETURN NEXT; RETURN; END IF;
 SELECT x.* INTO auth FROM agentos_evidence_private.authority(a.organization_id,a.provider,p_kind) x;
 IF NOT FOUND THEN RETURN NEXT; RETURN; END IF;
 IF auth.id IS DISTINCT FROM locked_id THEN RETURN NEXT; RETURN; END IF;
 IF ROW(auth.id,auth.epoch,auth.fence) IS DISTINCT FROM
 ROW(a.publisher_id,a.publisher_epoch,a.publisher_fence) THEN RETURN NEXT; RETURN; END IF;
 SELECT x.* INTO i FROM public.provider_credential_integrity x WHERE x.organization_id=a.organization_id
 AND x.connection_id=a.connection_id AND x.provider=a.provider AND x.credential_revision=a.credential_revision FOR UPDATE;
 SELECT x.* INTO h FROM public.provider_credential_health x WHERE x.organization_id=a.organization_id
 AND x.connection_id=a.connection_id AND x.provider=a.provider AND x.credential_revision=a.credential_revision FOR UPDATE;
 SELECT x.* INTO a FROM agentos_evidence_private.publication_attempts x WHERE x.attempt_id=p_attempt FOR UPDATE;
 reason:='ATTEMPT';
 IF a.attempt_id IS NULL OR a.consumed OR a.kind IS DISTINCT FROM p_kind
 OR a.login_name IS DISTINCT FROM SESSION_USER OR a.login_oid IS DISTINCT FROM auth.login_oid
 OR a.capture_xid=pg_catalog.pg_current_xact_id()
 OR (p_kind='HEALTH' AND (p_outcome IS NULL OR p_outcome NOT IN ('USABLE','UNAVAILABLE')
 OR p_present IS NULL OR (NOT p_present AND p_deadline IS NOT NULL)
 OR (p_present AND (p_deadline IS NULL OR NOT pg_catalog.isfinite(p_deadline))))) THEN
  RETURN NEXT; RETURN;
 END IF;
 reason:='BINDING';
 IF c.id IS NULL OR c.provider IS DISTINCT FROM a.provider OR v.organization_id IS NULL
 OR (p_kind<>'INTEGRITY_FAILURE' AND c.credential_revision IS DISTINCT FROM a.credential_revision)
 OR ROW(v.organization_id,v.connection_id,v.provider,v.credential_revision,v.external_locator,
 v.external_key_version,v.hmac_key_version,v.hmac_domain_version,v.verifier) IS DISTINCT FROM
 ROW(a.organization_id,a.connection_id,a.provider,a.credential_revision,a.external_locator,
 a.external_key_version,a.hmac_key_version,a.hmac_domain_version,a.verifier)
 OR NOT agentos_evidence_private.valid_binding(v.external_locator,v.external_key_version,
 v.hmac_key_version,v.hmac_domain_version,v.verifier) THEN RETURN NEXT; RETURN; END IF;
 -- All lock waits have completed. TIME precedes every sticky/CAS/MAX decision.
 now_at:=pg_catalog.clock_timestamp(); until_at:=a.deadline;
 IF p_kind='HEALTH' AND p_present THEN until_at:=LEAST(until_at,p_deadline); END IF;
 reason:='TIME';
 IF NOT pg_catalog.isfinite(now_at) OR NOT pg_catalog.isfinite(a.observed_at)
 OR NOT pg_catalog.isfinite(a.deadline) OR NOT pg_catalog.isfinite(until_at)
 OR a.observed_at<'0001-01-01 00:00:00+00'::pg_catalog.timestamptz
 OR now_at>'9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz
 OR a.deadline>'9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz
 OR a.observed_at>now_at OR now_at>=until_at OR a.deadline<=a.observed_at
 OR (p_kind='HEALTH' AND p_present AND (p_deadline<'0001-01-01 00:00:00+00'::pg_catalog.timestamptz
 OR p_deadline>'9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz))
 OR (p_kind<>'HEALTH' AND i.publication_revision IS NOT NULL AND now_at<i.recorded_at)
 OR (p_kind='HEALTH' AND h.publication_revision IS NOT NULL AND
 (a.observed_at<=h.observed_at OR now_at<h.published_at)) THEN RETURN NEXT; RETURN; END IF;
 reason:='EXHAUSTED';
 IF a.observed_at>'9999-12-31 23:58:59.999999+00'::pg_catalog.timestamptz THEN RETURN NEXT; RETURN; END IF;
 reason:='TIME'; IF a.deadline>a.observed_at+INTERVAL '60 seconds' THEN RETURN NEXT; RETURN; END IF;
 IF p_kind='HEALTH' THEN
  reason:='INTEGRITY_FAILED';
  IF i.state='FAILED' AND p_outcome='USABLE' THEN RETURN NEXT; RETURN; END IF;
  reason:='CONFLICT';
  IF a.health_present IS DISTINCT FROM (h.publication_revision IS NOT NULL)
  OR a.health_revision IS DISTINCT FROM h.publication_revision
  OR h.publication_revision<=0 THEN RETURN NEXT; RETURN; END IF;
  reason:='EXHAUSTED';
  IF h.publication_revision=9223372036854775807 THEN RETURN NEXT; RETURN; END IF;
  next_rev:=COALESCE(h.publication_revision,0)+1; target_state:=p_outcome;
  IF h.publication_revision IS NULL THEN
   INSERT INTO public.provider_credential_health VALUES
   (a.organization_id,a.connection_id,a.provider,a.credential_revision,a.external_locator,
   a.external_key_version,a.hmac_key_version,a.hmac_domain_version,target_state,
   a.observed_at,now_at,until_at,next_rev,a.publisher_id,a.publisher_epoch,a.publisher_fence);
  ELSE
   UPDATE public.provider_credential_health x SET state=target_state,observed_at=a.observed_at,
   published_at=now_at,valid_until=until_at,publication_revision=next_rev,
   publisher_id=a.publisher_id,publisher_epoch=a.publisher_epoch,publisher_fence=a.publisher_fence
   WHERE x.organization_id=a.organization_id AND x.connection_id=a.connection_id
   AND x.provider=a.provider AND x.credential_revision=a.credential_revision;
  END IF;
 ELSE
  IF i.state='FAILED' THEN
   IF p_kind='INTEGRITY_FAILURE' THEN status:='ALREADY_FAILED'; reason:='NONE';
   ELSE reason:='INTEGRITY_FAILED'; END IF;
   RETURN NEXT; RETURN;
  END IF;
  reason:='CONFLICT';
  IF p_kind='PREPARE' THEN
   IF i.publication_revision IS NOT NULL OR a.integrity_present THEN RETURN NEXT; RETURN; END IF;
   target_state:='VERIFIED_AT_WRITE';
  ELSE
   IF (a.integrity_present AND i.publication_revision IS NULL)
   OR a.integrity_revision>i.publication_revision OR i.publication_revision<=0 THEN RETURN NEXT; RETURN; END IF;
   target_state:='FAILED';
  END IF;
  reason:='EXHAUSTED';
  IF i.publication_revision=9223372036854775807 THEN RETURN NEXT; RETURN; END IF;
  next_rev:=COALESCE(i.publication_revision,0)+1;
  IF i.publication_revision IS NULL THEN
   INSERT INTO public.provider_credential_integrity VALUES
   (a.organization_id,a.connection_id,a.provider,a.credential_revision,a.external_locator,
   a.external_key_version,a.hmac_key_version,a.hmac_domain_version,target_state,now_at,next_rev);
  ELSE
   UPDATE public.provider_credential_integrity x SET state=target_state,recorded_at=now_at,
   publication_revision=next_rev WHERE x.organization_id=a.organization_id
   AND x.connection_id=a.connection_id AND x.provider=a.provider AND x.credential_revision=a.credential_revision;
  END IF;
  assertion:=pg_catalog.gen_random_uuid();
  INSERT INTO agentos_evidence_private.integrity_assertions VALUES
  (assertion,a.attempt_id,p_kind,a.organization_id,a.connection_id,a.provider,a.credential_revision,
  a.external_locator,a.external_key_version,a.hmac_key_version,a.hmac_domain_version,a.login_name,a.login_oid,
  a.publisher_id,a.publisher_epoch,a.publisher_fence,a.observed_at,now_at,
  CASE WHEN p_kind='PREPARE' THEN 'PREPARED_VERIFIED' ELSE 'CONFIRMED_MISMATCH' END,1);
  INSERT INTO agentos_evidence_private.integrity_provenance VALUES
  (a.organization_id,a.connection_id,a.provider,a.credential_revision,next_rev,assertion);
 END IF;
 UPDATE agentos_evidence_private.publication_attempts x SET consumed=true WHERE x.attempt_id=a.attempt_id;
 status:='ACCEPTED'; reason:='NONE'; organization_id:=a.organization_id; connection_id:=a.connection_id;
 provider:=a.provider; credential_revision:=a.credential_revision; external_locator:=a.external_locator;
 external_key_version:=a.external_key_version; hmac_key_version:=a.hmac_key_version;
 hmac_domain_version:=a.hmac_domain_version; publisher_id:=a.publisher_id; publisher_epoch:=a.publisher_epoch;
 publisher_fence:=a.publisher_fence; assertion_kind:=p_kind; publication_revision:=next_rev; state:=target_state;
 IF p_kind='HEALTH' THEN observed_at:=a.observed_at; published_at:=now_at; valid_until:=until_at;
 ELSE recorded_at:=now_at; END IF;
 RETURN NEXT;
END
$publish$;
CREATE FUNCTION agentos_evidence_private.begin_health(p_organization_id pg_catalog.uuid,
 p_connection_id pg_catalog.uuid,p_provider pg_catalog.text,p_credential_revision pg_catalog.int8)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text, attempt_id pg_catalog.uuid,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, verifier pg_catalog.text,
 observed_at pg_catalog.timestamptz, deadline pg_catalog.timestamptz) LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $entry$
 SELECT * FROM agentos_evidence_private.capture_attempt('HEALTH',p_organization_id,
 p_connection_id,p_provider,p_credential_revision)
$entry$;
CREATE FUNCTION agentos_evidence_private.begin_preparation_assertion(p_organization_id pg_catalog.uuid,
 p_connection_id pg_catalog.uuid,p_provider pg_catalog.text,p_credential_revision pg_catalog.int8)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text, attempt_id pg_catalog.uuid,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, verifier pg_catalog.text,
 observed_at pg_catalog.timestamptz, deadline pg_catalog.timestamptz) LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $entry$
 SELECT * FROM agentos_evidence_private.capture_attempt('PREPARE',p_organization_id,
 p_connection_id,p_provider,p_credential_revision)
$entry$;
CREATE FUNCTION agentos_evidence_private.begin_integrity_verification(p_organization_id pg_catalog.uuid,
 p_connection_id pg_catalog.uuid,p_provider pg_catalog.text,p_credential_revision pg_catalog.int8)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text, attempt_id pg_catalog.uuid,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, verifier pg_catalog.text,
 observed_at pg_catalog.timestamptz, deadline pg_catalog.timestamptz) LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $entry$
 SELECT * FROM agentos_evidence_private.capture_attempt('INTEGRITY_FAILURE',p_organization_id,
 p_connection_id,p_provider,p_credential_revision)
$entry$;
CREATE FUNCTION agentos_evidence_private.publish_health(p_attempt_id pg_catalog.uuid,p_outcome pg_catalog.text,component_deadline_present pg_catalog.bool,component_deadline pg_catalog.timestamptz)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, publisher_id pg_catalog.uuid,
 publisher_epoch pg_catalog.int8, publisher_fence pg_catalog.uuid,
 assertion_kind pg_catalog.text, publication_revision pg_catalog.int8, state pg_catalog.text,
 recorded_at pg_catalog.timestamptz, observed_at pg_catalog.timestamptz,
 published_at pg_catalog.timestamptz, valid_until pg_catalog.timestamptz) LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $entry$
 SELECT * FROM agentos_evidence_private.publish_attempt('HEALTH',p_attempt_id,p_outcome,component_deadline_present,component_deadline)
$entry$;
CREATE FUNCTION agentos_evidence_private.assert_prepared(p_attempt_id pg_catalog.uuid)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, publisher_id pg_catalog.uuid,
 publisher_epoch pg_catalog.int8, publisher_fence pg_catalog.uuid,
 assertion_kind pg_catalog.text, publication_revision pg_catalog.int8, state pg_catalog.text,
 recorded_at pg_catalog.timestamptz, observed_at pg_catalog.timestamptz,
 published_at pg_catalog.timestamptz, valid_until pg_catalog.timestamptz) LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $entry$
 SELECT * FROM agentos_evidence_private.publish_attempt('PREPARE',p_attempt_id,NULL,false,NULL)
$entry$;
CREATE FUNCTION agentos_evidence_private.publish_integrity_failure(p_attempt_id pg_catalog.uuid)
RETURNS TABLE(status pg_catalog.text, reason pg_catalog.text,
 organization_id pg_catalog.uuid, connection_id pg_catalog.uuid, provider pg_catalog.text,
 credential_revision pg_catalog.int8, external_locator pg_catalog.text,
 external_key_version pg_catalog.text, hmac_key_version pg_catalog.text,
 hmac_domain_version pg_catalog.text, publisher_id pg_catalog.uuid,
 publisher_epoch pg_catalog.int8, publisher_fence pg_catalog.uuid,
 assertion_kind pg_catalog.text, publication_revision pg_catalog.int8, state pg_catalog.text,
 recorded_at pg_catalog.timestamptz, observed_at pg_catalog.timestamptz,
 published_at pg_catalog.timestamptz, valid_until pg_catalog.timestamptz) LANGUAGE sql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $entry$
 SELECT * FROM agentos_evidence_private.publish_attempt('INTEGRITY_FAILURE',p_attempt_id,NULL,false,NULL)
$entry$;
-- Operator-only bounded housekeeping; no publisher EXECUTE grant.
CREATE FUNCTION agentos_evidence_private.cleanup_attempts(p_org pg_catalog.uuid,p_batch pg_catalog.int4)
RETURNS pg_catalog.int8 LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,agentos_evidence_private,pg_temp AS $cleanup$
DECLARE t pg_catalog.timestamptz; removed pg_catalog.int8;
BEGIN
 IF p_org IS NULL OR p_batch IS NULL OR p_batch<1 OR p_batch>4096
 OR pg_catalog.current_setting('transaction_isolation')<>'read committed'
 OR pg_catalog.current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION USING ERRCODE='22023',MESSAGE='Cleanup unavailable';
 END IF;
 PERFORM o.id FROM public.organizations o WHERE o.id=p_org FOR UPDATE;
 t:=pg_catalog.clock_timestamp();
 IF NOT pg_catalog.isfinite(t) OR t<'0001-01-01 00:00:00+00'::pg_catalog.timestamptz
 OR t>'9999-12-31 23:59:59.999999+00'::pg_catalog.timestamptz THEN RETURN 0; END IF;
 WITH candidates AS (
  SELECT a.attempt_id FROM agentos_evidence_private.publication_attempts a
  WHERE a.organization_id=p_org AND pg_catalog.isfinite(a.deadline)
  AND a.observed_at<=a.deadline AND a.deadline<='9999-12-31 23:54:59.999999+00'::pg_catalog.timestamptz
  AND a.deadline>='0001-01-01 00:00:00+00'::pg_catalog.timestamptz
  AND a.deadline<=t-INTERVAL '5 minutes'
  AND NOT EXISTS(SELECT 1 FROM agentos_evidence_private.integrity_assertions s WHERE s.attempt_id=a.attempt_id)
  ORDER BY a.deadline,a.attempt_id LIMIT p_batch FOR UPDATE OF a
 ), deleted AS (
  DELETE FROM agentos_evidence_private.publication_attempts a USING candidates c
  WHERE a.attempt_id=c.attempt_id RETURNING a.attempt_id
 ) SELECT pg_catalog.count(*) INTO removed FROM deleted;
 RETURN removed;
END
$cleanup$;
REVOKE ALL ON FUNCTION agentos_evidence_private.authority(uuid,text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.valid_binding(text,text,text,text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.capture_attempt(text,uuid,uuid,text,bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.publish_attempt(text,uuid,text,boolean,timestamp with time zone) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.cleanup_attempts(uuid,integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.begin_health(uuid,uuid,text,bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.begin_preparation_assertion(uuid,uuid,text,bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.begin_integrity_verification(uuid,uuid,text,bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.publish_health(uuid,text,boolean,timestamp with time zone) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.assert_prepared(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION agentos_evidence_private.publish_integrity_failure(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.begin_health(uuid,uuid,text,bigint) TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.begin_preparation_assertion(uuid,uuid,text,bigint) TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.begin_integrity_verification(uuid,uuid,text,bigint) TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.publish_health(uuid,text,boolean,timestamp with time zone) TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.assert_prepared(uuid) TO agentos_provider_evidence_publisher;
GRANT EXECUTE ON FUNCTION agentos_evidence_private.publish_integrity_failure(uuid) TO agentos_provider_evidence_publisher;
GRANT USAGE ON SCHEMA agentos_evidence_private TO agentos_provider_evidence_publisher;
RESET ROLE;
COMMIT;

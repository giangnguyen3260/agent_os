-- Operator-only PostgreSQL script, run after V025 as a deployment administrator.
-- No LOGINs, passwords, memberships or provider credentials are created.
-- Publisher remains intentionally powerless until guarded publication is reviewed.
BEGIN;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN
      ('agentos_provider_evidence_reader','agentos_provider_evidence_publisher')) THEN
    RAISE EXCEPTION 'Evidence capability roles already exist; inspect before changing';
  END IF;
END
$$;
CREATE ROLE agentos_provider_evidence_reader
  NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE agentos_provider_evidence_publisher
  NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO agentos_provider_evidence_reader;
GRANT SELECT ON public.provider_credential_versions, public.provider_evidence_publishers,
  public.provider_credential_integrity, public.provider_credential_health
  TO agentos_provider_evidence_reader;
COMMIT;

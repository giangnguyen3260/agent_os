-- Disposable Docker QC only. Apply after V024 and the existing QC bootstrap.
-- Intentionally does not provision policy or admission state. Not rerunnable.
BEGIN;
DO $$ BEGIN
 IF (SELECT count(*) FROM human_accounts WHERE email='qc@example.test') <> 1 THEN
  RAISE EXCEPTION 'Provider options fixture requires QC bootstrap hash';
 END IF;
 IF EXISTS (SELECT 1 FROM provider_deployment_policy)
    OR EXISTS (SELECT 1 FROM provider_read_admission) THEN
  RAISE EXCEPTION 'Provider options requires empty disposable policy/admission tables';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
VALUES ('7f240000-0000-4000-8000-000000000001','QC Provider options','qc-provider-options','ACTIVE',now(),now()),
       ('7f240000-0000-4000-8000-000000000002','QC Provider options foreign','qc-provider-options-foreign','ACTIVE',now(),now());
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT ('7f240100-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'provider-options-'||kind||'@example.test','Provider options '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES(1,'admin'),(2,'admin2'),(3,'member'),(4,'foreign'),(5,'stale')) r(n,kind)
WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT ('7f240100-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 CASE WHEN n=4 THEN '7f240000-0000-4000-8000-000000000002'::uuid
 ELSE '7f240000-0000-4000-8000-000000000001'::uuid END,
 CASE WHEN n=3 THEN 'MEMBER' ELSE 'ADMIN' END
FROM generate_series(1,5) n;
COMMIT;

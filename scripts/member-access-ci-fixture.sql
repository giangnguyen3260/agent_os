-- Disposable CI database only; run once with psql -v ON_ERROR_STOP=1.
-- Independent from all baseline/approval fixtures. Never use in production.
BEGIN;
CREATE FUNCTION pg_temp.ma_id(prefix text,n integer) RETURNS uuid LANGUAGE SQL IMMUTABLE AS
$$ SELECT (prefix||'0000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid $$;
DO $$ BEGIN
  IF (SELECT count(*) FROM human_accounts WHERE email='qc@example.test') <> 1 THEN
    RAISE EXCEPTION 'Disposable QC source account required';
  END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('50505050-5050-4050-8050-505050505050','Member Access QC','member-access-qc','ACTIVE',now(),now()),
('51515151-5151-4151-8151-515151515151','Foreign Member Access QC','foreign-member-access-qc','ACTIVE',now(),now());
INSERT INTO human_accounts(id,email,display_name,password_hash,enabled)
SELECT pg_temp.ma_id('5080',n),
CASE n WHEN 1 THEN 'member-access-admin@example.test'
 WHEN 2 THEN 'member-access-member@example.test'
 WHEN 3 THEN 'member-access-disabled@example.test'
 WHEN 99 THEN 'member-access-foreign@example.test'
 ELSE 'member-access-page-'||n||'@example.test' END,
CASE n WHEN 1 THEN 'Member Access Admin' WHEN 2 THEN 'Literal %_\ Member'
 WHEN 3 THEN 'Disabled Member' WHEN 99 THEN 'FOREIGN_MEMBER_PRIVATE'
 ELSE 'Page Member '||lpad(n::text,2,'0') END,
a.password_hash,n<>3 FROM human_accounts a CROSS JOIN
(SELECT generate_series(1,26) n UNION ALL SELECT 99) x WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT pg_temp.ma_id('5080',n),
CASE WHEN n=99 THEN '51515151-5151-4151-8151-515151515151'::uuid
 ELSE '50505050-5050-4050-8050-505050505050'::uuid END,
CASE WHEN n IN (1,99) THEN 'ADMIN' ELSE 'MEMBER' END
FROM (SELECT generate_series(1,26) n UNION ALL SELECT 99) x;
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
(pg_temp.ma_id('5010',1),'50505050-5050-4050-8050-505050505050','Member Access Archived Team','ARCHIVED',now(),now()),
(pg_temp.ma_id('5010',2),'51515151-5151-4151-8151-515151515151','FOREIGN_TEAM_PRIVATE','ACTIVE',now(),now());
INSERT INTO approval_grants(id,organization_id,team_id,account_id,action_type,risk_level,expires_at,revoked_at)
SELECT pg_temp.ma_id('5070',n),'50505050-5050-4050-8050-505050505050',pg_temp.ma_id('5010',1),
pg_temp.ma_id('5080',2),CASE WHEN n=1 THEN 'UNSUPPORTED_ACTION' ELSE 'PUBLISH_PREVIEW' END,'HIGH',
CASE WHEN n IN (25,26) THEN '2000-01-01T00:00:00Z'::timestamptz ELSE '2099-01-01T00:00:00Z'::timestamptz END,
CASE WHEN n IN (26,27) THEN '2001-01-01T00:00:00Z'::timestamptz ELSE null END
FROM generate_series(1,27) n;
INSERT INTO approval_grants(id,organization_id,team_id,account_id,action_type,risk_level,expires_at) VALUES
(pg_temp.ma_id('5070',98),'50505050-5050-4050-8050-505050505050',pg_temp.ma_id('5010',1),pg_temp.ma_id('5080',3),'PUBLISH_PREVIEW','HIGH','2099-01-01T00:00:00Z'),
(pg_temp.ma_id('5070',99),'51515151-5151-4151-8151-515151515151',pg_temp.ma_id('5010',2),pg_temp.ma_id('5080',2),'FOREIGN_ACTION_PRIVATE','HIGH','2099-01-01T00:00:00Z');
COMMIT;

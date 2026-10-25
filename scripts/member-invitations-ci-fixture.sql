-- Disposable PostgreSQL QC only, applied once after V015. No production seed.
-- Browser orgs 9090/9191; native orgs 9292/9393; history is retained in place.
-- Native accounts 901..930; browser actors 3/4/5 and recipients 101..130,
-- 201..230,301..330. No invitation/mail rows borrowed from prior features.
BEGIN;
CREATE FUNCTION pg_temp.inv_id(n integer) RETURNS uuid LANGUAGE SQL IMMUTABLE AS
$$ SELECT ('90900000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid $$;
DO $$ BEGIN
 IF (SELECT count(*) FROM human_accounts WHERE email='qc@example.test')<>1 THEN
 RAISE EXCEPTION 'Disposable qc@example.test password source required'; END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('90909090-9090-4090-8090-909090909090','Invitation QC','invitation-qc','ACTIVE',now(),now()),
('91919191-9191-4191-8191-919191919191','Invitation Foreign QC','invitation-foreign-qc','ACTIVE',now(),now()),
('92929292-9292-4292-8292-929292929292','Invitation Native QC','invitation-native-qc','ACTIVE',now(),now()),
('93939393-9393-4393-8393-939393939393','Invitation Native Foreign QC','invitation-native-foreign-qc','ACTIVE',now(),now());
INSERT INTO human_accounts(id,email,display_name,password_hash,enabled)
SELECT pg_temp.inv_id(n), 'invitation-'||CASE n WHEN 1 THEN 'admin' WHEN 2 THEN 'member'
 WHEN 3 THEN 'desktop' WHEN 4 THEN 'tablet' WHEN 5 THEN 'mobile' WHEN 6 THEN 'disabled'
 WHEN 99 THEN 'foreign' ELSE 'existing-'||n END||'@example.test',
'Invitation QC '||n,a.password_hash,n<>6 FROM human_accounts a CROSS JOIN
(SELECT generate_series(1,6) n UNION ALL SELECT 99 UNION ALL SELECT generate_series(101,130)
 UNION ALL SELECT generate_series(201,230) UNION ALL SELECT generate_series(301,330)
 UNION ALL SELECT generate_series(901,930)) x WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT pg_temp.inv_id(n),CASE WHEN n=99 THEN '91919191-9191-4191-8191-919191919191'::uuid
 ELSE '90909090-9090-4090-8090-909090909090'::uuid END,
CASE WHEN n IN (1,3,4,5,99,901,902) THEN 'ADMIN' ELSE 'MEMBER' END
FROM unnest(ARRAY[1,2,3,4,5,6,99,901,902]) n;
-- Preserve the original browser/foreign memberships, including admin 1 for
-- shared-session account-switch cases. Native receives additional org scope.
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT pg_temp.inv_id(n),'91919191-9191-4191-8191-919191919191','ADMIN'
FROM unnest(ARRAY[1,901,902]) n;
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT pg_temp.inv_id(n),'92929292-9292-4292-8292-929292929292',
CASE WHEN n IN (1,901,902) THEN 'ADMIN' ELSE 'MEMBER' END
FROM unnest(ARRAY[1,2,6,901,902]) n;
-- Both native issuers are valid in both native orgs for cross-org races and
-- reversed issuer/recipient human-row ordering.
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT pg_temp.inv_id(n),'93939393-9393-4393-8393-939393939393','ADMIN'
FROM unnest(ARRAY[1,901,902]) n;
INSERT INTO member_role_ledgers(organization_id,account_id,revision) VALUES
('90909090-9090-4090-8090-909090909090',pg_temp.inv_id(905),7),
('90909090-9090-4090-8090-909090909090',pg_temp.inv_id(906),2147483647),
('92929292-9292-4292-8292-929292929292',pg_temp.inv_id(905),7),
('92929292-9292-4292-8292-929292929292',pg_temp.inv_id(906),2147483647);
COMMIT;

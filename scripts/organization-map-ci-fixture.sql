-- Disposable QC organizations only, after the existing qc@example.test hash source.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Organization map fixture requires QC account';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('50700000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Map '||surface,'qc-map-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign'),(6,'empty')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'map-'||surface||'-'||kind||'@example.test','Map '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile'),('empty')) s(surface)
CROSS JOIN (VALUES('admin'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,o.id,CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'map-'||substring(o.slug from 8)||'-%@example.test'
WHERE o.slug IN ('qc-map-native','qc-map-desktop','qc-map-tablet','qc-map-mobile','qc-map-empty');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,created_at,updated_at)
SELECT ('50710000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('50700000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Map Team '||lpad(t::text,3,'0'),CASE WHEN t=4 THEN NULL ELSE 'Persisted map purpose' END,
 CASE t WHEN 2 THEN 'ACTIVE' WHEN 3 THEN 'ARCHIVED' ELSE 'DRAFT' END,
 CASE WHEN t=4 THEN NULL ELSE 'MANUAL' END,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,25) t;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('50720000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 ('50700000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 CASE WHEN a<=25 THEN ('50710000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid
 WHEN a=29 THEN ('50710000-0000-4000-8000-'||lpad((n*100+2)::text,12,'0'))::uuid ELSE NULL END,
 'Map Agent '||lpad(a::text,3,'0'),'Recorded reviewer',CASE WHEN a=1 THEN 'MANAGER' ELSE 'MID' END,
 CASE WHEN a=3 THEN 'SUSPENDED' ELSE 'DRAFT' END,'SUGGEST',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,29) a;
UPDATE teams t SET manager_agent_id=('50720000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid
FROM generate_series(1,5) n WHERE t.id=('50710000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid;
UPDATE agents a SET reports_to_agent_id=CASE
 WHEN seq=2 THEN ('50720000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid
 WHEN seq=3 THEN a.id
 WHEN seq=4 THEN '50720000-0000-4000-8000-000000005001'::uuid
 WHEN seq=5 THEN ('50720000-0000-4000-8000-'||lpad((n*1000+29)::text,12,'0'))::uuid
 WHEN seq=6 THEN ('50720000-0000-4000-8000-'||lpad((n*1000+7)::text,12,'0'))::uuid
 WHEN seq=7 THEN ('50720000-0000-4000-8000-'||lpad((n*1000+6)::text,12,'0'))::uuid END
FROM generate_series(1,4) n CROSS JOIN generate_series(2,7) seq
WHERE a.id=('50720000-0000-4000-8000-'||lpad((n*1000+seq)::text,12,'0'))::uuid;
COMMIT;

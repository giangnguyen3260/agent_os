-- Disposable isolated fixtures. No owner event, receipt or audit history is seeded.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Owner history fixture requires QC bootstrap hash';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('56000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Owner history '||surface,'qc-owner-history-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'owner-history-'||surface||'-'||kind||'@example.test',
 'Owner history '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('admin2'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,CASE WHEN a.email LIKE '%-other@%' THEN '56000000-0000-4000-8000-000000000005'::uuid ELSE o.id END,
 CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'owner-history-'||replace(o.slug,'qc-owner-history-','')||'-%@example.test'
WHERE o.slug IN ('qc-owner-history-native','qc-owner-history-desktop','qc-owner-history-tablet','qc-owner-history-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('56010000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('56000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Owner history Team '||t,'Actual saved configuration','ACTIVE','MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('56040000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 ('56000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('56010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 CASE WHEN n=5 THEN 'PRIVATE_OWNER_HISTORY_FOREIGN' ELSE 'Owner history Agent '||a END,
 'Reviewer','MID',CASE WHEN a=1 THEN 'DRAFT' ELSE 'ACTIVE' END,'SUGGEST',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) a;
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,task_intake_version,task_intake_paused,created_at,updated_at)
SELECT ('56020000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('56000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('56010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 'Owner history Mission 1','Actual scoped goal','CREATED','NORMAL',7,false,now(),now()
FROM generate_series(1,5) n;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority,owner_agent_id)
SELECT ('56030000-0000-4000-8000-'||lpad((n*1000+t)::text,12,'0'))::uuid,
 ('56000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('56010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('56020000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 'Owner history Task '||t,'DRAFT','Actual saved input','Actual saved acceptance','NORMAL',
 CASE WHEN t=1 THEN '56040000-0000-4000-8000-000000005001'::uuid ELSE NULL END
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
INSERT INTO task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)
SELECT ('56000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('56010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('56020000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('56030000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid,
 ('56030000-0000-4000-8000-'||lpad((n*1000+2)::text,12,'0'))::uuid
FROM generate_series(1,5) n;
COMMIT;

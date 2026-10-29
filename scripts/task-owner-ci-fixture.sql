-- Disposable real QC fixtures only; no production seed data or seeded owner changes.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Task owner fixture requires QC bootstrap hash';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('55000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Task owner '||surface,'qc-task-owner-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'task-owner-'||surface||'-'||kind||'@example.test',
 'Task owner '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('admin2'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,CASE WHEN a.email LIKE '%-other@%' THEN '55000000-0000-4000-8000-000000000005'::uuid ELSE o.id END,
 CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'task-owner-'||replace(o.slug,'qc-task-owner-','')||'-%@example.test'
WHERE o.slug IN ('qc-task-owner-native','qc-task-owner-desktop','qc-task-owner-tablet','qc-task-owner-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('55010000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('55000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Task owner Team '||t,'Actual saved configuration',CASE WHEN t=2 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
 'MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,3) t;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('55040000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 ('55000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('55010000-0000-4000-8000-'||lpad((n*100+CASE WHEN a=26 THEN 3 ELSE 1 END)::text,12,'0'))::uuid,
 CASE WHEN n=5 THEN CASE WHEN a=1 THEN 'PRIVATE_FOREIGN_OWNER' ELSE 'PRIVATE_FOREIGN_OWNER_'||a END
      WHEN a=26 THEN 'PRIVATE_DIFFERENT_TEAM_OWNER' WHEN a=27 THEN 'Task owner Ineligible'
      ELSE 'Task owner Agent '||lpad(a::text,2,'0') END,
 'Reviewer','MID',CASE WHEN a=27 THEN 'SUSPENDED' WHEN a%2=1 THEN 'DRAFT' ELSE 'ACTIVE' END,
 'SUGGEST',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,27) a;
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,task_intake_version,task_intake_paused,created_at,updated_at)
SELECT ('55020000-0000-4000-8000-'||lpad((n*100+m)::text,12,'0'))::uuid,
 ('55000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('55010000-0000-4000-8000-'||lpad((n*100+CASE WHEN m=4 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 'Task owner Mission '||m,'Actual scoped goal',CASE WHEN m=3 THEN 'COMPLETED' ELSE 'CREATED' END,
 'NORMAL',7,m=2,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,4) m;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority,owner_agent_id,task_owner_revision)
SELECT ('55030000-0000-4000-8000-'||lpad((n*1000+t)::text,12,'0'))::uuid,
 ('55000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('55010000-0000-4000-8000-'||lpad((n*100+CASE WHEN t=8 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 ('55020000-0000-4000-8000-'||lpad((n*100+CASE t WHEN 6 THEN 2 WHEN 7 THEN 3 WHEN 8 THEN 4 ELSE 1 END)::text,12,'0'))::uuid,
 'Task owner Draft '||t,CASE WHEN t=5 THEN 'READY' ELSE 'DRAFT' END,
 CASE WHEN t=10 THEN NULL ELSE E'Actual stored input\n<script>only text</script>' END,
 CASE WHEN t=10 THEN NULL ELSE 'Actual stored acceptance' END,
 CASE WHEN t=10 THEN NULL ELSE 'NORMAL' END,
 CASE WHEN t=2 THEN ('55040000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid
      WHEN t=3 THEN '55040000-0000-4000-8000-000000005001'::uuid
      WHEN t=4 THEN ('55040000-0000-4000-8000-'||lpad((n*1000+27)::text,12,'0'))::uuid ELSE NULL END,
 CASE WHEN t=9 THEN 2147483647 ELSE 0 END
FROM generate_series(1,5) n CROSS JOIN generate_series(1,10) t;
INSERT INTO task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)
SELECT ('55000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('55010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('55020000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('55030000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid,
 ('55030000-0000-4000-8000-'||lpad((n*1000+5)::text,12,'0'))::uuid
FROM generate_series(1,5) n;
COMMIT;

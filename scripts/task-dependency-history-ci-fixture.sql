-- Disposable dependency QC only. Legacy edges are actual configuration; no dependency event, receipt or audit history is seeded.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Task dependency history fixture requires QC bootstrap hash';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('58000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Task dependency history '||surface,'qc-task-dependency-history-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'task-dependency-history-'||surface||'-'||kind||'@example.test',
 'Task dependency history '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('admin2'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,CASE WHEN a.email LIKE '%-other@%' THEN '58000000-0000-4000-8000-000000000005'::uuid ELSE o.id END,
 CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'task-dependency-history-'||replace(o.slug,'qc-task-dependency-history-','')||'-%@example.test'
WHERE o.slug IN ('qc-task-dependency-history-native','qc-task-dependency-history-desktop','qc-task-dependency-history-tablet','qc-task-dependency-history-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('58010000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('58000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Task dependency history Team '||t,'Actual saved configuration','ACTIVE','MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('58040000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 ('58000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('58010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 CASE WHEN n=5 THEN 'PRIVATE_TASK_DEPENDENCY_HISTORY_FOREIGN' ELSE 'Task dependency history Agent '||a END,
 'Reviewer','MID',CASE WHEN a=1 THEN 'DRAFT' ELSE 'ACTIVE' END,'SUGGEST',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) a;
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,task_intake_version,task_intake_paused,created_at,updated_at)
SELECT ('58020000-0000-4000-8000-'||lpad((n*100+m)::text,12,'0'))::uuid,
 ('58000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('58010000-0000-4000-8000-'||lpad((n*100+m)::text,12,'0'))::uuid,
 'Task dependency history Mission '||m,'Actual scoped goal','CREATED','NORMAL',7,false,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) m;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority,owner_agent_id,pending_reason,waiting_since,resolver)
SELECT ('58030000-0000-4000-8000-'||lpad((n*1000+t)::text,12,'0'))::uuid,
 ('58000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('58010000-0000-4000-8000-'||lpad((n*100+CASE WHEN t=36 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 ('58020000-0000-4000-8000-'||lpad((n*100+CASE WHEN t=36 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 CASE WHEN n=5 THEN 'PRIVATE_TASK_DEPENDENCY_HISTORY_FOREIGN' WHEN t=11 THEN '' ELSE 'Task dependency history Task '||t END,
 CASE t WHEN 4 THEN 'COMPLETED' WHEN 12 THEN 'RUNNING' WHEN 13 THEN 'WAITING' WHEN 14 THEN 'BLOCKED'
 WHEN 15 THEN 'IN_REVIEW' WHEN 16 THEN 'FAILED' WHEN 17 THEN 'CANCELLED' WHEN 18 THEN 'READY' ELSE 'DRAFT' END,
 CASE WHEN t=11 THEN NULL ELSE 'Actual saved input' END,
 CASE WHEN t=11 THEN NULL ELSE 'Actual saved acceptance' END,
 CASE WHEN t=11 THEN NULL ELSE 'NORMAL' END,
 CASE WHEN t=1 THEN '58040000-0000-4000-8000-000000005001'::uuid ELSE NULL END,
 CASE WHEN t IN (13,14) THEN 'Awaiting actual QC input' ELSE NULL END,
 CASE WHEN t IN (13,14) THEN now() ELSE NULL END,
 CASE WHEN t IN (13,14) THEN 'Task dependency history QC' ELSE NULL END
FROM generate_series(1,5) n CROSS JOIN generate_series(1,36) t;
INSERT INTO task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)
SELECT ('58000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('58010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('58020000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('58030000-0000-4000-8000-'||lpad((n*1000+edges.source)::text,12,'0'))::uuid,
 ('58030000-0000-4000-8000-'||lpad((n*1000+edges.target)::text,12,'0'))::uuid
FROM generate_series(1,5) n CROSS JOIN (VALUES (1,2),(2,3),(3,4),(8,1)) edges(source,target);
COMMIT;

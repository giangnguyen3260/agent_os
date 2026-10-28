-- Isolated disposable QC data; never production seed data.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Intake history fixture needs QC hash source';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('54000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Intake history '||surface,'qc-intake-history-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'intake-history-'||surface||'-'||kind||'@example.test',
 'Intake history '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('admin2'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,CASE WHEN a.email LIKE '%-other@%' THEN '54000000-0000-4000-8000-000000000005'::uuid ELSE o.id END,
 CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'intake-history-'||substring(o.slug from 19)||'-%@example.test'
WHERE o.slug IN ('qc-intake-history-native','qc-intake-history-desktop','qc-intake-history-tablet','qc-intake-history-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('54010000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('54000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Intake history Team '||t,'Actual intake policy',CASE WHEN t=2 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
 'MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,task_intake_version,task_intake_paused,created_at,updated_at)
SELECT ('54020000-0000-4000-8000-'||lpad((n*100+m)::text,12,'0'))::uuid,
 ('54000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('54010000-0000-4000-8000-'||lpad((n*100+CASE WHEN m=5 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 'Intake history Mission '||m,'Actual scoped goal',CASE WHEN m=3 THEN 'COMPLETED' ELSE 'CREATED' END,
 'NORMAL',CASE WHEN m=4 THEN 9223372036854775807 ELSE 7 END,m=2,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,5) m;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority)
SELECT ('54030000-0000-4000-8000-'||lpad((n*1000+t)::text,12,'0'))::uuid,
 ('54000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('54010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('54020000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 'Intake history Draft '||t,'DRAFT','Actual saved input','Actual saved acceptance','NORMAL'
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
COMMIT;

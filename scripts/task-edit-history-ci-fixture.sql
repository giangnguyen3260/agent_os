-- Isolated disposable draft editing data, never production seeds.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Task draft edit fixture needs QC hash source';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('52000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Task history '||surface,'qc-task-history-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'task-history-'||surface||'-'||kind||'@example.test',
 'Task history '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('admin2'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,o.id,CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'task-history-'||substring(o.slug from 17)||'-%@example.test'
WHERE o.slug IN ('qc-task-history-native','qc-task-history-desktop','qc-task-history-tablet','qc-task-history-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('52010000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('52000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Task history Team '||t,'Actual saved input',CASE WHEN t=2 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
 'MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('52040000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('52000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('52010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 'Task history Owner','Reviewer','MID','ACTIVE','SUGGEST',now(),now()
FROM generate_series(1,5) n;
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,task_intake_version,task_intake_paused,created_at,updated_at)
SELECT ('52020000-0000-4000-8000-'||lpad((n*100+m)::text,12,'0'))::uuid,
 ('52000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('52010000-0000-4000-8000-'||lpad((n*100+CASE WHEN m=4 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 'Task history Mission '||m,'Real mission input',CASE WHEN m=2 THEN 'REVIEW' ELSE 'CREATED' END,
 'NORMAL',7,m=3,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,4) m;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,description,acceptance_criteria,priority,owner_agent_id,pending_reason,waiting_since,resolver,task_edit_revision)
SELECT ('52030000-0000-4000-8000-'||lpad((n*1000+t)::text,12,'0'))::uuid,
 ('52000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('52010000-0000-4000-8000-'||lpad((n*100+CASE WHEN t=8 THEN 2 ELSE 1 END)::text,12,'0'))::uuid,
 ('52020000-0000-4000-8000-'||lpad((n*100+CASE t WHEN 6 THEN 2 WHEN 7 THEN 3 WHEN 8 THEN 4 ELSE 1 END)::text,12,'0'))::uuid,
 'Task history Draft '||t,
 CASE t WHEN 3 THEN 'READY' WHEN 4 THEN 'WAITING' WHEN 5 THEN 'RUNNING' WHEN 10 THEN 'COMPLETED' WHEN 11 THEN 'CANCELLED' WHEN 12 THEN 'BLOCKED' WHEN 13 THEN 'IN_REVIEW' WHEN 14 THEN 'FAILED' ELSE 'DRAFT' END,
 CASE WHEN t=2 THEN NULL ELSE E'Persisted input\n<script>only text</script>' END,
 CASE WHEN t=2 THEN NULL ELSE 'Persisted acceptance criteria' END,
 CASE WHEN t=2 THEN NULL ELSE 'NORMAL' END,
 CASE WHEN t=1 THEN ('52040000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid ELSE NULL END,
 CASE WHEN t IN(4,12) THEN 'Recorded pending reason' ELSE NULL END,
 CASE WHEN t IN(4,12) THEN now() ELSE NULL END,
 CASE WHEN t IN(4,12) THEN 'Recorded resolver' ELSE NULL END,
 CASE WHEN t=9 THEN 2147483647 ELSE 0 END
FROM generate_series(1,5) n CROSS JOIN generate_series(1,14) t;
INSERT INTO task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id)
SELECT ('52000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('52010000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('52020000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('52030000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid,
 ('52030000-0000-4000-8000-'||lpad((n*1000+3)::text,12,'0'))::uuid
FROM generate_series(1,5) n;
-- Explicit synthetic provenance for read-only pagination; never production seed data.
INSERT INTO task_edit_events(id,organization_id,team_id,mission_id,task_id,revision,actor_id,created_at,before_json,after_json)
SELECT gen_random_uuid(),t.organization_id,t.team_id,t.mission_id,t.id,r,a.id,
 timestamptz '2026-01-01 00:00:00+00' + r * interval '1 minute',
 json_build_object('title',CASE WHEN r=1 THEN t.title ELSE 'History revision '||(r-1) END,
 'description',CASE WHEN r=1 THEN NULL ELSE E'Historical input\n<script>literal text</script>' END,
 'acceptanceCriteria',CASE WHEN r=1 THEN NULL ELSE 'Historical acceptance' END,
 'priority',CASE WHEN r=1 THEN NULL ELSE 'HIGH' END)::text,
 json_build_object('title','History revision '||r,'description',E'Historical input\n<script>literal text</script>',
 'acceptanceCriteria','Historical acceptance','priority','HIGH')::text
FROM tasks t JOIN organizations o ON o.id=t.organization_id
JOIN human_accounts a ON a.email='task-history-'||substring(o.slug from 17)||'-admin@example.test'
CROSS JOIN generate_series(1,25) r
WHERE o.slug IN('qc-task-history-native','qc-task-history-desktop','qc-task-history-tablet','qc-task-history-mobile')
AND t.id::text LIKE '%001';
UPDATE tasks SET task_edit_revision=25,title='History revision 25',
 description=E'Historical input\n<script>literal text</script>',acceptance_criteria='Historical acceptance',priority='HIGH'
WHERE id IN (SELECT task_id FROM task_edit_events WHERE organization_id::text LIKE '52000000-%');
-- Foreign-only viewers must not retain a local organization membership.
DELETE FROM organization_memberships WHERE account_id IN
 (SELECT id FROM human_accounts WHERE email LIKE 'task-history-%-other@example.test');
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'52000000-0000-4000-8000-000000000005'::uuid,'ADMIN'
FROM human_accounts WHERE email LIKE 'task-history-%-other@example.test';
COMMIT;

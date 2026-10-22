-- Disposable Create Task acceptance data; never a production seed.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Task Intake QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('task-create-admin@example.test'),('task-create-member@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('22222222-2323-4232-8232-222222222222','QC Task Intake','qc-task-intake','ACTIVE',now(),now()),
('23232323-2323-4323-8323-232323232323','QC Foreign Task Intake','qc-task-intake-foreign','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'22222222-2323-4232-8232-222222222222',CASE WHEN email='task-create-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('task-create-admin@example.test','task-create-member@example.test');
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('22100000-0000-4000-8000-000000000001','22222222-2323-4232-8232-222222222222','Task Intake Delivery','ACTIVE',now(),now()),
('22100000-0000-4000-8000-000000000002','22222222-2323-4232-8232-222222222222','Archived Task Team','ARCHIVED',now(),now()),
('22100000-0000-4000-8000-000000000003','22222222-2323-4232-8232-222222222222','Other Task Team','ACTIVE',now(),now()),
('23100000-0000-4000-8000-000000000001','23232323-2323-4323-8323-232323232323','Foreign Task Team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('22200000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'22222222-2323-4232-8232-222222222222',
'22100000-0000-4000-8000-000000000001','Task Owner '||lpad(n::text,2,'0'),'Delivery','WORKER',
CASE WHEN n%2=0 THEN 'ACTIVE' ELSE 'DRAFT' END,'SUGGEST',now(),now() FROM generate_series(1,25) n;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('22200000-0000-4000-8000-000000000026','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000001','SUSPENDED_OWNER_PRIVATE','Delivery','WORKER','SUSPENDED','SUGGEST',now(),now()),
('22200000-0000-4000-8000-000000000027','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000003','OTHER_TEAM_OWNER_PRIVATE','Delivery','WORKER','ACTIVE','SUGGEST',now(),now()),
('23200000-0000-4000-8000-000000000001','23232323-2323-4323-8323-232323232323','23100000-0000-4000-8000-000000000001','FOREIGN_OWNER_PRIVATE','Delivery','WORKER','ACTIVE','SUGGEST',now(),now());
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) VALUES
('22300000-0000-4000-8000-000000000001','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000001','Task Intake Main','PRIVATE_TASK_GOAL','RUNNING','NORMAL',now(),now()),
('22300000-0000-4000-8000-000000000002','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000001','Paused Task Intake','Private','RUNNING','NORMAL',now(),now()),
('22300000-0000-4000-8000-000000000003','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000001','Completed Task Intake','Private','COMPLETED','NORMAL',now(),now()),
('22300000-0000-4000-8000-000000000004','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000003','OTHER_TASK_MISSION_PRIVATE','Private','RUNNING','NORMAL',now(),now()),
('22300000-0000-4000-8000-000000000005','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000002','Archived Team Mission','Private','CREATED','NORMAL',now(),now()),
('23300000-0000-4000-8000-000000000001','23232323-2323-4323-8323-232323232323','23100000-0000-4000-8000-000000000001','FOREIGN_TASK_MISSION_PRIVATE','Private','RUNNING','NORMAL',now(),now());
UPDATE missions SET task_intake_paused=true WHERE id='22300000-0000-4000-8000-000000000002';
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)
SELECT ('22300000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000001',
'Task Intake '||(ARRAY['desktop','tablet','mobile'])[n-9],'Private','RUNNING','NORMAL',now(),now() FROM generate_series(10,12) n;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status)
SELECT ('22400000-0000-4000-8000-'||lpad((CASE WHEN m=1 THEN n ELSE m*100+n END)::text,12,'0'))::uuid,
'22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000001',
('22300000-0000-4000-8000-'||lpad(m::text,12,'0'))::uuid,'Dependency '||lpad(n::text,2,'0'),'DRAFT'
FROM generate_series(1,25) n CROSS JOIN (VALUES(1),(10),(11),(12)) missions(m);
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status) VALUES
('22400000-0000-4000-8000-000000000026','22222222-2323-4232-8232-222222222222','22100000-0000-4000-8000-000000000003','22300000-0000-4000-8000-000000000004','OTHER_DEPENDENCY_PRIVATE','DRAFT'),
('23400000-0000-4000-8000-000000000001','23232323-2323-4323-8323-232323232323','23100000-0000-4000-8000-000000000001','23300000-0000-4000-8000-000000000001','FOREIGN_DEPENDENCY_PRIVATE','DRAFT');

-- Disposable CI-only workspace history, never a product seed or migration.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Team Workspace QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('workspace-team-admin@example.test'),('workspace-team-member@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('14141414-1414-4414-8414-141414141414','QC Team Workspace','qc-team-workspace','ACTIVE',now(),now()),
('15151515-1515-4515-8515-151515151515','QC Foreign Workspace','qc-team-workspace-foreign','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'14141414-1414-4414-8414-141414141414',CASE WHEN email='workspace-team-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('workspace-team-admin@example.test','workspace-team-member@example.test');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at) VALUES
('e1000000-0000-4000-8000-000000000001','14141414-1414-4414-8414-141414141414','Workspace Delivery','Review delivery','ACTIVE','MANUAL',4,100,'USD',now(),now()),
('e1000000-0000-4000-8000-000000000002','14141414-1414-4414-8414-141414141414','Workspace Empty',null,'DRAFT',null,null,null,null,now(),now()),
('f1000000-0000-4000-8000-000000000001','15151515-1515-4515-8515-151515151515','Foreign private workspace',null,'ACTIVE',null,null,null,null,now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('e2000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'14141414-1414-4414-8414-141414141414',
'e1000000-0000-4000-8000-000000000001','Workspace Agent '||lpad(n::text,2,'0'),CASE WHEN n=1 THEN 'Manager' ELSE 'Reviewer' END,
CASE WHEN n=1 THEN 'MANAGER' ELSE 'MID' END,CASE WHEN n%2=0 THEN 'DRAFT' ELSE 'ACTIVE' END,'SUGGEST',
TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*interval '1 second',now() FROM generate_series(1,24) n;
UPDATE teams SET manager_agent_id='e2000000-0000-4000-8000-000000000001' WHERE id='e1000000-0000-4000-8000-000000000001';
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) VALUES
('e3000000-0000-4000-8000-000000000001','14141414-1414-4414-8414-141414141414','e1000000-0000-4000-8000-000000000001','Workspace mission','Verify persisted read lanes','RUNNING','NORMAL',now(),now()),
('f3000000-0000-4000-8000-000000000001','15151515-1515-4515-8515-151515151515','f1000000-0000-4000-8000-000000000001','Foreign private mission','Private','RUNNING','NORMAL',now(),now());
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver,created_at,updated_at)
SELECT ('e4000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'14141414-1414-4414-8414-141414141414',
'e1000000-0000-4000-8000-000000000001','e3000000-0000-4000-8000-000000000001','Workspace task '||lpad(n::text,2,'0'),
CASE WHEN n<=7 THEN (ARRAY['DRAFT','READY','RUNNING','IN_REVIEW','COMPLETED','FAILED','CANCELLED'])[n] WHEN n%2=0 THEN 'WAITING' ELSE 'BLOCKED' END,
CASE WHEN n>=8 THEN CASE WHEN n%2=0 THEN 'Await user input ' ELSE 'Await dependency ' END||n ELSE NULL END,
CASE WHEN n>=8 THEN TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*interval '1 minute' ELSE NULL END,
CASE WHEN n>=8 THEN 'Workspace operator' ELSE NULL END,
TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*interval '1 second',now() FROM generate_series(1,31) n;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status) VALUES
('f4000000-0000-4000-8000-000000000001','15151515-1515-4515-8515-151515151515','f1000000-0000-4000-8000-000000000001','f3000000-0000-4000-8000-000000000001','Foreign private task','RUNNING');
INSERT INTO audit_events(id,organization_id,mission_id,task_id,actor_type,actor_id,event_type,payload,created_at)
SELECT ('e5000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'14141414-1414-4414-8414-141414141414',
'e3000000-0000-4000-8000-000000000001','e4000000-0000-4000-8000-000000000001','HUMAN',null,'WORKSPACE_TASK_EVENT',
'PRIVATE_WORKSPACE_PAYLOAD_DO_NOT_RETURN',TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*interval '1 second' FROM generate_series(1,25) n;
-- Inconsistent cross-team references must not leak through an OR-based activity filter.
INSERT INTO audit_events(id,organization_id,mission_id,task_id,actor_type,event_type,payload,created_at) VALUES
('e6000000-0000-4000-8000-000000000001','14141414-1414-4414-8414-141414141414','e3000000-0000-4000-8000-000000000001','f4000000-0000-4000-8000-000000000001','HUMAN','FOREIGN_MIXED_EVENT','PRIVATE_FOREIGN_PAYLOAD',now()),
('f5000000-0000-4000-8000-000000000001','15151515-1515-4515-8515-151515151515','f3000000-0000-4000-8000-000000000001','f4000000-0000-4000-8000-000000000001','HUMAN','FOREIGN_EVENT','PRIVATE_FOREIGN_PAYLOAD',now());

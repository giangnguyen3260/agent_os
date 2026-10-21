-- Disposable Mission directory acceptance data, not a production seed.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Mission Directory QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('mission-directory-admin@example.test'),('mission-directory-member@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('18181818-1818-4818-8818-181818181818','QC Mission Directory','qc-mission-directory','ACTIVE',now(),now()),
('19191919-1919-4919-8919-191919191919','Foreign Mission Directory','qc-mission-directory-foreign','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'18181818-1818-4818-8818-181818181818',CASE WHEN email='mission-directory-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('mission-directory-admin@example.test','mission-directory-member@example.test');
INSERT INTO teams(id,organization_id,name,purpose,status,created_at,updated_at)
SELECT ('18100000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'18181818-1818-4818-8818-181818181818',
CASE WHEN n=1 THEN 'Directory Delivery' WHEN n=2 THEN 'Directory Archived' WHEN n=3 THEN 'Directory Other' ELSE 'Directory Team '||lpad(n::text,2,'0') END,
'Directory verification',CASE WHEN n=2 THEN 'ARCHIVED' WHEN n=3 THEN 'DRAFT' ELSE 'ACTIVE' END,
TIMESTAMPTZ '2026-02-01 00:00:00+00'+n*interval '1 second',now() FROM generate_series(1,25) n;
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('19100000-0000-4000-8000-000000000001','19191919-1919-4919-8919-191919191919','Foreign Private Team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('18200000-0000-4000-8000-000000000001','18181818-1818-4818-8818-181818181818','18100000-0000-4000-8000-000000000001','Directory Manager','Manager','MANAGER','ACTIVE','SUGGEST',now(),now()),
('18200000-0000-4000-8000-000000000002','18181818-1818-4818-8818-181818181818','18100000-0000-4000-8000-000000000003','Other Team Manager','Manager','MANAGER','DRAFT','SUGGEST',now(),now()),
('19200000-0000-4000-8000-000000000001','19191919-1919-4919-8919-191919191919','19100000-0000-4000-8000-000000000001','FOREIGN_PRIVATE_MANAGER','Manager','MANAGER','ACTIVE','SUGGEST',now(),now());
INSERT INTO missions(id,organization_id,team_id,manager_agent_id,title,goal,status,priority,created_at,updated_at)
SELECT ('18300000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'18181818-1818-4818-8818-181818181818',
CASE WHEN n=27 THEN NULL WHEN n=26 THEN '18100000-0000-4000-8000-000000000003'::uuid ELSE '18100000-0000-4000-8000-000000000001'::uuid END,
CASE WHEN n=27 THEN '19200000-0000-4000-8000-000000000001'::uuid WHEN n=26 THEN '18200000-0000-4000-8000-000000000002'::uuid ELSE '18200000-0000-4000-8000-000000000001'::uuid END,
CASE WHEN n=3 THEN 'Mission Rate%_!' WHEN n=27 THEN 'Unassigned legacy mission' ELSE 'Directory Mission '||lpad(n::text,2,'0') END,
'PRIVATE_DIRECTORY_GOAL_DO_NOT_PROJECT',
(ARRAY['CREATED','PLANNING','READY','RUNNING','WAITING_APPROVAL','REVIEW','COMPLETED','FAILED','CANCELLED'])[(n-1)%9+1],
(ARRAY['LOW','NORMAL','HIGH','URGENT'])[(n-1)%4+1],
TIMESTAMPTZ '2026-02-01 00:00:00+00'+n*interval '1 second',now() FROM generate_series(1,27) n;
INSERT INTO missions(id,organization_id,team_id,manager_agent_id,title,goal,status,priority,created_at,updated_at) VALUES
('19300000-0000-4000-8000-000000000001','19191919-1919-4919-8919-191919191919','19100000-0000-4000-8000-000000000001','19200000-0000-4000-8000-000000000001','FOREIGN_PRIVATE_MISSION','Private','RUNNING','NORMAL',now(),now());
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)
SELECT ('18400000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'18181818-1818-4818-8818-181818181818',
'18100000-0000-4000-8000-000000000001','18300000-0000-4000-8000-000000000025','Directory task '||n,state,
CASE WHEN state IN ('WAITING','BLOCKED') THEN 'Private task reason' ELSE NULL END,
CASE WHEN state IN ('WAITING','BLOCKED') THEN now() ELSE NULL END,
CASE WHEN state IN ('WAITING','BLOCKED') THEN 'Private resolver' ELSE NULL END
FROM (VALUES (1,'RUNNING'),(2,'WAITING'),(3,'BLOCKED'),(4,'COMPLETED'),(5,'COMPLETED'),(6,'IN_REVIEW'),(7,'FAILED')) x(n,state);
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status) VALUES
('18400000-0000-4000-8000-000000000008','18181818-1818-4818-8818-181818181818','18100000-0000-4000-8000-000000000003','18300000-0000-4000-8000-000000000026','Other task','COMPLETED'),
('19400000-0000-4000-8000-000000000001','19191919-1919-4919-8919-191919191919','19100000-0000-4000-8000-000000000001','19300000-0000-4000-8000-000000000001','Foreign task','COMPLETED');

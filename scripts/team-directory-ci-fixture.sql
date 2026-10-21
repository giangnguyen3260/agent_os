-- Disposable directory acceptance data; never a production seed or migration.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Team Directory QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('team-directory-admin@example.test'),('team-directory-member@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('12121212-1212-4212-8212-121212121212','QC Team Directory','qc-team-directory','ACTIVE',now(),now()),
('13131313-1313-4313-8313-131313131313','QC Foreign Team Directory','qc-team-directory-foreign','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'12121212-1212-4212-8212-121212121212',CASE WHEN email='team-directory-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('team-directory-admin@example.test','team-directory-member@example.test');
INSERT INTO teams(id,organization_id,name,purpose,status,created_at,updated_at)
SELECT ('a1000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'12121212-1212-4212-8212-121212121212',
CASE WHEN n=25 THEN 'Team 100%_ready!'||chr(92) ELSE 'Team directory '||lpad(n::text,2,'0') END,
CASE WHEN n%2=0 THEN 'Build services' ELSE 'Review products' END,
(ARRAY['DRAFT','ACTIVE','ARCHIVED'])[(n-1)%3+1],TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*interval '1 second',now()
FROM generate_series(1,25) n;
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('b1000000-0000-4000-8000-000000000001','13131313-1313-4313-8313-131313131313','Foreign Private Team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('a2000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'12121212-1212-4212-8212-121212121212',
'a1000000-0000-4000-8000-000000000025','Directory Agent '||n,CASE WHEN n=1 THEN 'Manager' ELSE 'Reviewer' END,
CASE WHEN n=1 THEN 'MANAGER' ELSE 'MID' END,'ACTIVE','SUGGEST',now(),now() FROM generate_series(1,3) n;
UPDATE teams SET manager_agent_id='a2000000-0000-4000-8000-000000000001' WHERE id='a1000000-0000-4000-8000-000000000025';
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) VALUES
('a3000000-0000-4000-8000-000000000001','12121212-1212-4212-8212-121212121212','a1000000-0000-4000-8000-000000000025','Directory mission','Verify actual task counts','RUNNING','NORMAL',now(),now()),
('b3000000-0000-4000-8000-000000000001','13131313-1313-4313-8313-131313131313','b1000000-0000-4000-8000-000000000001','Foreign private mission','Private','RUNNING','NORMAL',now(),now());
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver)
SELECT ('a4000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'12121212-1212-4212-8212-121212121212',
'a1000000-0000-4000-8000-000000000025','a3000000-0000-4000-8000-000000000001','Team directory task '||n,state,
CASE WHEN state IN ('WAITING','BLOCKED') THEN 'Await directory evidence' ELSE NULL END,
CASE WHEN state IN ('WAITING','BLOCKED') THEN now()-interval '1 hour' ELSE NULL END,
CASE WHEN state IN ('WAITING','BLOCKED') THEN 'Team directory admin' ELSE NULL END
FROM (VALUES (1,'RUNNING'),(2,'RUNNING'),(3,'WAITING'),(4,'BLOCKED'),(5,'IN_REVIEW'),(6,'COMPLETED')) x(n,state);
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver) VALUES
('b4000000-0000-4000-8000-000000000001','13131313-1313-4313-8313-131313131313','b1000000-0000-4000-8000-000000000001','b3000000-0000-4000-8000-000000000001','Foreign private task','WAITING','Private',now(),'Private');

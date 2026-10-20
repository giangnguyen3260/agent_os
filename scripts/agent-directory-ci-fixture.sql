-- Disposable CI-only data; never a production migration or application seed.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Directory QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('directory-admin@example.test'),('directory-member@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('88888888-8888-4888-8888-888888888888','QC Directory Organization','qc-directory','ACTIVE',now(),now()),
('99999999-9999-4999-8999-999999999999','QC Foreign Directory','qc-directory-foreign','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'88888888-8888-4888-8888-888888888888',CASE WHEN email='directory-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('directory-admin@example.test','directory-member@example.test');
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('81000000-0000-4000-8000-000000000001','88888888-8888-4888-8888-888888888888','Directory Product','ACTIVE',now(),now()),
('91000000-0000-4000-8000-000000000001','99999999-9999-4999-8999-999999999999','Foreign Private Team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,configuration_revision,created_at,updated_at)
SELECT ('82000000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'88888888-8888-4888-8888-888888888888',
CASE WHEN n%2=0 THEN NULL ELSE '81000000-0000-4000-8000-000000000001'::uuid END,
CASE WHEN n=25 THEN 'Directory 100%_ready!'||chr(92) ELSE 'Directory agent '||lpad(n::text,2,'0') END,
CASE WHEN n%2=0 THEN 'Builder' ELSE 'Reviewer' END,'MID',
(ARRAY['DRAFT','ACTIVE','DEGRADED','SUSPENDED','ARCHIVED'])[(n-1)%5+1],'SUGGEST',1,
TIMESTAMPTZ '2026-01-01 00:00:00+00' + n * interval '1 second',now()
FROM generate_series(1,25) n;
INSERT INTO agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria,concurrency)
SELECT id,'Review changes','Specification','Evidence','Reproducible findings',2 FROM agents WHERE organization_id='88888888-8888-4888-8888-888888888888';
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('92000000-0000-4000-8000-000000000001','99999999-9999-4999-8999-999999999999','91000000-0000-4000-8000-000000000001','Foreign Private Agent','Private','MID','DRAFT','SUGGEST',now(),now());

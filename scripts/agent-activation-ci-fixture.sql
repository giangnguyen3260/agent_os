-- Disposable activation preflight QC data, never a production migration.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,x.display_name,a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('activation-admin@example.test','Activation Admin QC'),('activation-member@example.test','Activation Member QC')) x(email,display_name)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('77777777-7777-4777-8777-777777777777','QC Activation Organization','qc-activation-organization','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'77777777-7777-4777-8777-777777777777',CASE WHEN email='activation-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('activation-admin@example.test','activation-member@example.test');
INSERT INTO teams(id,organization_id,name,status,concurrency,monthly_budget,currency,created_at,updated_at) VALUES
('71000000-0000-4000-8000-000000000001','77777777-7777-4777-8777-777777777777','Activation configured team','ACTIVE',4,100,'USD',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,configuration_revision,created_at,updated_at)
SELECT ('72000000-0000-4000-8000-00000000000'||n)::uuid,'77777777-7777-4777-8777-777777777777',
'71000000-0000-4000-8000-000000000001','Activation '||surface,'Reviewer','MID','DRAFT','SUGGEST',1,now(),now()
FROM (VALUES (1,'desktop'),(2,'tablet'),(3,'mobile')) x(n,surface);
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,configuration_revision,created_at,updated_at) VALUES
('73000000-0000-4000-8000-000000000001','77777777-7777-4777-8777-777777777777','71000000-0000-4000-8000-000000000001','Activation readonly agent','Reviewer','MID','ACTIVE','SUGGEST',1,now(),now()),
('74000000-0000-4000-8000-000000000001','77777777-7777-4777-8777-777777777777','71000000-0000-4000-8000-000000000001','Activation native probe','Reviewer','MID','DRAFT','SUGGEST',1,now(),now());
INSERT INTO agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria)
SELECT id,'Review proposed changes','Specification','Findings with evidence','Every finding is reproducible'
FROM agents WHERE organization_id='77777777-7777-4777-8777-777777777777';

-- Disposable Studio QC fixtures only, never application migrations.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,x.display_name,a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('studio-admin@example.test','Studio Admin QC'),('studio-member@example.test','Studio Member QC')) x(email,display_name)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee','QC Studio Organization','qc-studio-organization','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',CASE WHEN email='studio-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('studio-admin@example.test','studio-member@example.test');
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('d1111111-1111-4111-8111-111111111111','eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee','Studio team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,configuration_revision,created_at,updated_at)
SELECT ('d2000000-0000-4000-8000-00000000000'||n)::uuid,'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
'd1111111-1111-4111-8111-111111111111','Studio '||surface,'Reviewer','MID','DRAFT','SUGGEST',1,now(),now()
FROM (VALUES (1,'desktop'),(2,'tablet'),(3,'mobile')) x(n,surface);
INSERT INTO agents(id,organization_id,name,role,level,status,autonomy_level,configuration_revision,created_at,updated_at) VALUES
('d3000000-0000-4000-8000-000000000001','eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee','Studio active agent','Reviewer','MID','ACTIVE','SUGGEST',1,now(),now()),
('d4000000-0000-4000-8000-000000000001','eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee','Studio race probe','Reviewer','MID','DRAFT','SUGGEST',1,now(),now());
INSERT INTO agent_draft_config(agent_id,description,responsibilities,expected_input,output_format,acceptance_criteria,model,skills,tools,limits)
SELECT id,'Studio fixture','Review proposed changes','Specification','Findings with evidence','Every finding is reproducible',NULL,'Review',NULL,NULL
FROM agents WHERE organization_id='eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';

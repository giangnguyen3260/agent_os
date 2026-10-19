-- Disposable CI fixtures only; not an application migration.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,x.display_name,a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('team-admin@example.test','Team Admin QC'),('team-member@example.test','Team Member QC')) x(email,display_name)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('cccccccc-cccc-4ccc-8ccc-cccccccccccc','QC Team Organization','qc-team-organization','ACTIVE',now(),now()),
('ffffffff-ffff-4fff-8fff-ffffffffffff','QC Hidden Team Organization','qc-hidden-team-organization','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',CASE WHEN email='team-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email IN ('team-admin@example.test','team-member@example.test');
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('c1111111-1111-4111-8111-111111111111','cccccccc-cccc-4ccc-8ccc-cccccccccccc','Existing busy team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('c2000000-0000-4000-8000-00000000000'||n)::uuid,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
'QC '||surface||' manager','Manager','MANAGER','ACTIVE','MANAGED',now(),now()
FROM (VALUES (1,'desktop'),(2,'tablet'),(3,'mobile')) x(n,surface);
INSERT INTO agents(id,organization_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('c3000000-0000-4000-8000-00000000000'||n)::uuid,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
'QC '||surface||' worker','Developer','SENIOR','DRAFT','SUGGEST',now(),now()
FROM (VALUES (1,'desktop'),(2,'tablet'),(3,'mobile')) x(n,surface);
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('c4444444-4444-4444-8444-444444444444','cccccccc-cccc-4ccc-8ccc-cccccccccccc','c1111111-1111-4111-8111-111111111111','QC assigned agent','Developer','MID','ACTIVE','SUGGEST',now(),now()),
('c6666666-6666-4666-8666-666666666666','cccccccc-cccc-4ccc-8ccc-cccccccccccc',NULL,'QC suspended agent','Developer','MID','SUSPENDED','SUGGEST',now(),now()),
('f5555555-5555-4555-8555-555555555555','ffffffff-ffff-4fff-8fff-ffffffffffff',NULL,'QC foreign secret agent','Manager','MANAGER','ACTIVE','MANAGED',now(),now());

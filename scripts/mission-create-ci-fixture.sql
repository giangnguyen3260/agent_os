-- Disposable CI data only; never production bootstrap.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Mission QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('mission-admin@example.test'),('mission-member@example.test'),('mission-desktop@example.test'),('mission-tablet@example.test'),('mission-mobile@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('16161616-1616-4616-8616-161616161616','QC Mission Workspace','qc-mission-create','ACTIVE',now(),now()),
('17171717-1717-4717-8717-171717171717','QC Mission Foreign','qc-mission-foreign','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'16161616-1616-4616-8616-161616161616',CASE WHEN email='mission-member@example.test' THEN 'MEMBER' ELSE 'ADMIN' END
FROM human_accounts WHERE email LIKE 'mission-%@example.test';
INSERT INTO teams(id,organization_id,name,purpose,status,created_at,updated_at) VALUES
('16100000-0000-4000-8000-000000000001','16161616-1616-4616-8616-161616161616','Mission Delivery','Mission creation QC','ACTIVE',now(),now()),
('16100000-0000-4000-8000-000000000002','16161616-1616-4616-8616-161616161616','Mission Archived','Archived QC','ARCHIVED',now(),now()),
('16100000-0000-4000-8000-000000000003','16161616-1616-4616-8616-161616161616','Mission Other Team','Scope QC','DRAFT',now(),now()),
('17100000-0000-4000-8000-000000000001','17171717-1717-4717-8717-171717171717','Foreign Mission Team','Private','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('16200000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'16161616-1616-4616-8616-161616161616',
'16100000-0000-4000-8000-000000000001','Mission Manager '||lpad(n::text,2,'0'),'Manager','MANAGER',
CASE WHEN n=1 THEN 'ACTIVE' ELSE 'DRAFT' END,'SUGGEST',now(),now() FROM generate_series(1,23) n;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('16200000-0000-4000-8000-000000000024','16161616-1616-4616-8616-161616161616','16100000-0000-4000-8000-000000000003','Other Team Manager','Manager','MANAGER','ACTIVE','SUGGEST',now(),now()),
('16200000-0000-4000-8000-000000000025','16161616-1616-4616-8616-161616161616','16100000-0000-4000-8000-000000000001','Suspended Manager','Manager','MANAGER','SUSPENDED','SUGGEST',now(),now()),
('16200000-0000-4000-8000-000000000026','16161616-1616-4616-8616-161616161616','16100000-0000-4000-8000-000000000001','Team Contributor','Reviewer','MID','ACTIVE','SUGGEST',now(),now());

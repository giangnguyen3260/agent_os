-- Disposable GitHub Actions data only. Never run as a production migration.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(), 'dashboard-qc@example.test', 'Dashboard QC', password_hash
FROM human_accounts WHERE email = 'qc@example.test';

INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','QC Dashboard Alpha','qc-dashboard-alpha','ACTIVE',now(),now()),
('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','QC Dashboard Hidden','qc-dashboard-hidden','ACTIVE',now(),now()),
('dddddddd-dddd-4ddd-8ddd-dddddddddddd','QC Dashboard Empty','qc-dashboard-empty','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id, organization_id, 'MEMBER' FROM human_accounts
CROSS JOIN (VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid),('dddddddd-dddd-4ddd-8ddd-dddddddddddd'::uuid)) AS organizations(organization_id)
WHERE email='dashboard-qc@example.test';

INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('a1111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','Engineering','ACTIVE',now(),now()),
('a2222222-2222-4222-8222-222222222222','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','Research','ACTIVE',now(),now()),
('b1111111-1111-4111-8111-111111111111','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','Hidden team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('a3333333-3333-4333-8333-333333333333','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','a1111111-1111-4111-8111-111111111111','QC Manager','Manager','MANAGER','ACTIVE','MANAGED',now(),now());
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) VALUES
('a4444444-4444-4444-8444-444444444444','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','a1111111-1111-4111-8111-111111111111','Dashboard mission','Test persisted tasks','RUNNING','NORMAL',now(),now()),
('b4444444-4444-4444-8444-444444444444','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','b1111111-1111-4111-8111-111111111111','Hidden mission','Must not leak','RUNNING','NORMAL',now(),now());
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver) VALUES
('a5555555-5555-4555-8555-555555555551','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','a1111111-1111-4111-8111-111111111111','a4444444-4444-4444-8444-444444444444','Implement runtime API','RUNNING',null,null,null),
('a5555555-5555-4555-8555-555555555552','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','a1111111-1111-4111-8111-111111111111','a4444444-4444-4444-8444-444444444444','Budget approval task','WAITING','Cần phê duyệt ngân sách',now(),'Quản trị viên'),
('a5555555-5555-4555-8555-555555555553','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','a1111111-1111-4111-8111-111111111111','a4444444-4444-4444-8444-444444444444','Dependency task','BLOCKED','Chờ API phụ thuộc',now(),'Team Manager'),
('a5555555-5555-4555-8555-555555555554','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','a1111111-1111-4111-8111-111111111111','a4444444-4444-4444-8444-444444444444','Review security spec','IN_REVIEW',null,null,null),
('b5555555-5555-4555-8555-555555555551','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','b1111111-1111-4111-8111-111111111111','b4444444-4444-4444-8444-444444444444','Secret task title','WAITING','Secret pending reason',now(),'Hidden resolver');

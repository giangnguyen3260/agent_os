-- Isolated disposable Approval Center metadata, never a production seed.
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),x.email,'Approval QC',a.password_hash FROM human_accounts a
CROSS JOIN (VALUES ('approval-admin@example.test'),('approval-member@example.test')) x(email)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('26262626-2626-4626-8626-262626262626','Approval QC','approval-qc','ACTIVE',now(),now()),
('27272727-2727-4727-8727-272727272727','Foreign Approval QC','foreign-approval-qc','ACTIVE',now(),now()),
('28282828-2828-4828-8828-282828282828','Empty Approval QC','empty-approval-qc','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,o.id,CASE WHEN a.email='approval-admin@example.test' THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts a CROSS JOIN organizations o WHERE a.email IN ('approval-admin@example.test','approval-member@example.test')
AND o.id IN ('26262626-2626-4626-8626-262626262626','28282828-2828-4828-8828-282828282828');
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
('26100000-0000-4000-8000-000000000001','26262626-2626-4626-8626-262626262626','Approval Delivery','ACTIVE',now(),now()),
('26100000-0000-4000-8000-000000000002','26262626-2626-4626-8626-262626262626','Moved Team','ACTIVE',now(),now()),
('27100000-0000-4000-8000-000000000001','27272727-2727-4727-8727-272727272727','Foreign Team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
('26200000-0000-4000-8000-000000000001','26262626-2626-4626-8626-262626262626','26100000-0000-4000-8000-000000000001','Approval Requester','Specialist','SPECIALIST','DRAFT','SUGGEST',now(),now()),
('26200000-0000-4000-8000-000000000002','26262626-2626-4626-8626-262626262626','26100000-0000-4000-8000-000000000002','MOVED_REQUESTER_PRIVATE','Specialist','SPECIALIST','DRAFT','SUGGEST',now(),now());
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) VALUES
('26300000-0000-4000-8000-000000000001','26262626-2626-4626-8626-262626262626','26100000-0000-4000-8000-000000000001','Approval Mission','PRIVATE_APPROVAL_GOAL','WAITING_APPROVAL','NORMAL',now(),now());
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver) VALUES
('26400000-0000-4000-8000-000000000001','26262626-2626-4626-8626-262626262626','26100000-0000-4000-8000-000000000001','26300000-0000-4000-8000-000000000001','Approval Task','WAITING','Recorded waiting reason',now(),'QC Operator');
INSERT INTO approval_requests(id,organization_id,team_id,mission_id,task_id,requested_by_agent_id,title,action_type,risk_level,status,created_at,updated_at)
SELECT ('26500000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'26262626-2626-4626-8626-262626262626',
'26100000-0000-4000-8000-000000000001','26300000-0000-4000-8000-000000000001','26400000-0000-4000-8000-000000000001',
CASE WHEN n=1 THEN '26200000-0000-4000-8000-000000000002'::uuid ELSE '26200000-0000-4000-8000-000000000001'::uuid END,
'Approval request '||lpad(n::text,2,'0'),'PUBLISH_PREVIEW','HIGH',
CASE WHEN n<=24 THEN 'PENDING' WHEN n<=27 THEN 'NEEDS_INFORMATION' ELSE (ARRAY['APPROVED','REJECTED','EXPIRED','CANCELLED','INVALIDATED'])[n-27] END,
TIMESTAMPTZ '2026-01-01 00:00:00+00',TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM generate_series(1,32) n;
INSERT INTO approval_requests(id,organization_id,team_id,title,action_type,risk_level,status) VALUES
('27500000-0000-4000-8000-000000000001','27272727-2727-4727-8727-272727272727','27100000-0000-4000-8000-000000000001','FOREIGN_APPROVAL_PRIVATE','PRIVATE_ACTION','CRITICAL','PENDING');

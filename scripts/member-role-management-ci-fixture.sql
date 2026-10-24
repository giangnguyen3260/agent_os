-- Disposable QC database only. Independent from all existing fixtures; never production.
-- Org 8080: main; 8181: foreign; 8282: reversed-account barrier.
-- Accounts 8080: 1 admin,2 MEMBER read baseline,3/4/5 desktop/tablet/mobile ADMIN,
-- 6 disabled,7 removed,8 requester; 91/92 reversed-account actors; 901..930 native targets.
-- Viewport targets: 101..120 desktop,201..220 tablet,301..320 mobile.
-- All viewport targets start MEMBER; *02 retains two legacy approval grants.
-- Native-only 901/902 peer ADMINs, 904 demotion/Admin-waiter/explicit-grant actor; 903/905+ MEMBER targets.
-- Grants 8070: 1/2 legacy duplicate baseline,3 unsupported CRITICAL,4 expired,5 revoked,
-- 6 disabled recipient,7 removed recipient,8 self. Approval requests 8050:901..903 sealed pending.
BEGIN;
CREATE FUNCTION pg_temp.rm_id(prefix text,n integer) RETURNS uuid LANGUAGE SQL IMMUTABLE AS
$$ SELECT (prefix||'0000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid $$;
DO $$ BEGIN
 IF (SELECT count(*) FROM human_accounts WHERE email='qc@example.test')<>1 THEN
 RAISE EXCEPTION 'Disposable qc@example.test password source required'; END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('80808080-8080-4080-8080-808080808080','Role Management QC','role-management-qc','ACTIVE',now(),now()),
('81818181-8181-4181-8181-818181818181','Foreign Role Management QC','foreign-role-management-qc','ACTIVE',now(),now()),
('82828282-8282-4282-8282-828282828282','Reversed Accounts QC','reversed-role-management-qc','ACTIVE',now(),now());
INSERT INTO human_accounts(id,email,display_name,password_hash,enabled)
SELECT pg_temp.rm_id('8080',n),
CASE n WHEN 1 THEN 'role-management-admin@example.test' WHEN 2 THEN 'role-management-member@example.test'
 WHEN 3 THEN 'role-management-desktop@example.test' WHEN 4 THEN 'role-management-tablet@example.test'
 WHEN 5 THEN 'role-management-mobile@example.test' WHEN 6 THEN 'role-management-disabled@example.test'
 WHEN 7 THEN 'role-management-removed@example.test' WHEN 8 THEN 'role-management-requester@example.test'
 WHEN 91 THEN 'role-management-cross-low@example.test' WHEN 92 THEN 'role-management-cross-high@example.test'
 WHEN 99 THEN 'role-management-foreign@example.test'
 ELSE 'role-management-target-'||n||'@example.test' END,
'Role Management '||CASE n WHEN 1 THEN 'Admin' WHEN 2 THEN 'Member Baseline'
 WHEN 3 THEN 'Desktop Admin' WHEN 4 THEN 'Tablet Admin' WHEN 5 THEN 'Mobile Admin' ELSE 'Target '||n END,
a.password_hash,n<>6 FROM human_accounts a CROSS JOIN
(SELECT generate_series(1,8) n UNION ALL SELECT 91 UNION ALL SELECT 92 UNION ALL SELECT 99
 UNION ALL SELECT generate_series(101,120) UNION ALL SELECT generate_series(201,220)
 UNION ALL SELECT generate_series(301,320) UNION ALL SELECT generate_series(901,930)) x WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,CASE WHEN id=pg_temp.rm_id('8080',99) THEN '81818181-8181-4181-8181-818181818181'::uuid ELSE '80808080-8080-4080-8080-808080808080'::uuid END,
CASE WHEN id IN (pg_temp.rm_id('8080',1),pg_temp.rm_id('8080',3),pg_temp.rm_id('8080',4),pg_temp.rm_id('8080',5),pg_temp.rm_id('8080',91),pg_temp.rm_id('8080',92),pg_temp.rm_id('8080',901),pg_temp.rm_id('8080',902),pg_temp.rm_id('8080',904),pg_temp.rm_id('8080',910),pg_temp.rm_id('8080',99)) THEN 'ADMIN' ELSE 'MEMBER' END
FROM human_accounts WHERE email LIKE 'role-management-%@example.test' AND id<>pg_temp.rm_id('8080',7);
INSERT INTO organization_memberships(account_id,organization_id,role) VALUES
(pg_temp.rm_id('8080',91),'82828282-8282-4282-8282-828282828282','ADMIN'),
(pg_temp.rm_id('8080',92),'82828282-8282-4282-8282-828282828282','ADMIN');
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
(pg_temp.rm_id('8010',1),'80808080-8080-4080-8080-808080808080','Role Management Draft Team','DRAFT',now(),now()),
(pg_temp.rm_id('8010',2),'80808080-8080-4080-8080-808080808080','Role Management Active Team','ACTIVE',now(),now()),
(pg_temp.rm_id('8010',3),'80808080-8080-4080-8080-808080808080','Role Management Archived Team','ARCHIVED',now(),now()),
(pg_temp.rm_id('8010',99),'81818181-8181-4181-8181-818181818181','FOREIGN_ROLE_TEAM_PRIVATE','ACTIVE',now(),now()),
(pg_temp.rm_id('8010',92),'82828282-8282-4282-8282-828282828282','Reversed Account Team','DRAFT',now(),now());
INSERT INTO approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at,revoked_at)
SELECT pg_temp.rm_id('8070',n),'80808080-8080-4080-8080-808080808080',
pg_temp.rm_id('8080',CASE n WHEN 6 THEN 6 WHEN 7 THEN 7 WHEN 8 THEN 1 ELSE 2 END),pg_temp.rm_id('8010',CASE WHEN n=3 THEN 3 ELSE 1 END),
CASE WHEN n=3 THEN 'LEGACY_UNSUPPORTED' ELSE 'PUBLISH_PREVIEW' END,CASE WHEN n=3 THEN 'CRITICAL' ELSE 'HIGH' END,
CASE WHEN n=4 THEN '2000-01-01T00:00:00Z'::timestamptz ELSE '2099-01-01T00:00:00.123456Z'::timestamptz END,
CASE WHEN n=5 THEN '2020-01-01T00:00:00Z'::timestamptz ELSE null END FROM generate_series(1,8) n;
INSERT INTO approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at)
SELECT pg_temp.rm_id('8070',n+d),'80808080-8080-4080-8080-808080808080',pg_temp.rm_id('8080',n),pg_temp.rm_id('8010',1),'PUBLISH_PREVIEW','HIGH','2099-01-01T00:00:00Z'
FROM unnest(ARRAY[102,202,302]) n CROSS JOIN unnest(ARRAY[10000,20000]) d;
INSERT INTO approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at) VALUES
(pg_temp.rm_id('8070',904),'80808080-8080-4080-8080-808080808080',pg_temp.rm_id('8080',904),pg_temp.rm_id('8010',2),'PUBLISH_PREVIEW','HIGH','2099-01-01T00:00:00Z');
INSERT INTO approval_requests(id,organization_id,team_id,requester_human_id,title,action_type,risk_level,status)
SELECT pg_temp.rm_id('8050',n),'80808080-8080-4080-8080-808080808080',pg_temp.rm_id('8010',2),pg_temp.rm_id('8080',8),'Role Management Pending '||n,'PUBLISH_PREVIEW','HIGH','PENDING' FROM generate_series(901,903) n;
INSERT INTO approval_action_heads(action_id,organization_id,team_id,revision,payload_sha256)
SELECT pg_temp.rm_id('8060',n),'80808080-8080-4080-8080-808080808080',pg_temp.rm_id('8010',2),1,repeat('0',64) FROM generate_series(901,903) n;
-- Same canonical TreeMap representation as ApprovalDetailService and approval-detail fixture.
INSERT INTO approval_snapshots(approval_id,action_id,version,head_revision,canonical_payload,payload_sha256,expires_at)
SELECT r.id,pg_temp.rm_id('8060',n),1,1,c.payload,encode(sha256(convert_to(c.payload,'UTF8')),'hex'),'2099-01-01T00:00:00Z'
FROM approval_requests r CROSS JOIN LATERAL (SELECT right(r.id::text,12)::integer n) x
CROSS JOIN LATERAL (SELECT jsonb_build_object(
'id',r.id,'organization_id',r.organization_id,'team_id',r.team_id,'mission_id',r.mission_id,'task_id',r.task_id,
'requested_by_agent_id',r.requested_by_agent_id,'requester_human_id',r.requester_human_id,'action_type',r.action_type,'risk_level',r.risk_level,
'actionId',pg_temp.rm_id('8060',n),'version',1,'headRevision',1,'expiresAt','2099-01-01T00:00:00Z',
'parameters',jsonb_build_object('destination','internal-review','resourcePath','/release/role-management','visibility','TEAM'),
 'target','Staging preview /release/role-management','scope','Publish preview to internal review workspace only',
'effectSummary','Create one reviewable preview; no production deployment','estimatedCost','0.25','currency','USD') obj) j
CROSS JOIN LATERAL (SELECT '{'||string_agg(to_json(key)::text||':'||CASE WHEN key='parameters' THEN (SELECT '{'||string_agg(to_json(p.key)::text||':'||p.value::text,',' ORDER BY p.key COLLATE "C")||'}' FROM jsonb_each(value) p) ELSE value::text END,',' ORDER BY key COLLATE "C")||'}' payload FROM jsonb_each(j.obj)) c
WHERE r.organization_id='80808080-8080-4080-8080-808080808080';
UPDATE approval_action_heads h SET payload_sha256=s.payload_sha256 FROM approval_snapshots s WHERE h.action_id=s.action_id AND h.organization_id='80808080-8080-4080-8080-808080808080';
COMMIT;

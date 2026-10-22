-- Disposable QC only. Browser index + viewport offset (0/100/200):
-- 1 admin approve,2 member approve,3 admin reject,4 no grant,5 self(admin),
-- 6 expired,7 invalidated,8 no snapshot,9 information,10 legacy approved(no decision),
-- 11 ambiguous retry,12 stale head,13 corrupt digest,14 missing requester.
-- ALL 15..40 remain independent eligible browser requests.
-- 901..940 exclusively native probes; 940 is a recorded closed decision.
CREATE FUNCTION pg_temp.ad_id(prefix text,n integer) RETURNS uuid LANGUAGE SQL IMMUTABLE AS
$$ SELECT (prefix||'0000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid $$;
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT pg_temp.ad_id('3080',x.n),x.email,x.name,a.password_hash FROM human_accounts a
CROSS JOIN (VALUES (1,'approval-detail-admin@example.test','Approval Detail Admin'),
(2,'approval-detail-member@example.test','Approval Detail Member'),
(3,'approval-detail-requester@example.test','Approval Detail Requester')) x(n,email,name)
WHERE a.email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at) VALUES
('30303030-3030-4030-8030-303030303030','Approval Detail QC','approval-detail-qc','ACTIVE',now(),now()),
('31313131-3131-4131-8131-313131313131','Foreign Approval Detail QC','foreign-approval-detail-qc','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT pg_temp.ad_id('3080',n),'30303030-3030-4030-8030-303030303030',CASE WHEN n=1 THEN 'ADMIN' ELSE 'MEMBER' END FROM generate_series(1,3) n;
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at) VALUES
(pg_temp.ad_id('3010',1),'30303030-3030-4030-8030-303030303030','Approval Detail Team','ACTIVE',now(),now()),
(pg_temp.ad_id('3010',2),'31313131-3131-4131-8131-313131313131','Foreign Approval Detail Team','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at) VALUES
(pg_temp.ad_id('3020',1),'30303030-3030-4030-8030-303030303030',pg_temp.ad_id('3010',1),'Approval Detail Agent','Specialist','SPECIALIST','DRAFT','SUGGEST',now(),now());
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at) VALUES
(pg_temp.ad_id('3030',1),'30303030-3030-4030-8030-303030303030',pg_temp.ad_id('3010',1),'Approval Detail Mission','Private goal','WAITING_APPROVAL','NORMAL',now(),now());
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,pending_reason,waiting_since,resolver) VALUES
(pg_temp.ad_id('3040',1),'30303030-3030-4030-8030-303030303030',pg_temp.ad_id('3010',1),pg_temp.ad_id('3030',1),'Approval Detail Task','WAITING','Approval required',now(),'QC');
INSERT INTO approval_grants(id,organization_id,team_id,account_id,action_type,risk_level,expires_at)
SELECT pg_temp.ad_id('3070',n),'30303030-3030-4030-8030-303030303030',pg_temp.ad_id('3010',1),pg_temp.ad_id('3080',n),'PUBLISH_PREVIEW','HIGH','2099-01-01T00:00:00Z' FROM generate_series(1,2) n;
INSERT INTO approval_requests(id,organization_id,team_id,mission_id,task_id,requested_by_agent_id,requester_human_id,title,action_type,risk_level,status)
SELECT pg_temp.ad_id('3050',n),'30303030-3030-4030-8030-303030303030',pg_temp.ad_id('3010',1),pg_temp.ad_id('3030',1),pg_temp.ad_id('3040',1),pg_temp.ad_id('3020',1),
CASE WHEN n%100=14 THEN null WHEN n%100=5 THEN pg_temp.ad_id('3080',1) ELSE pg_temp.ad_id('3080',3) END,
'Approval Detail '||n,CASE WHEN n%100=4 THEN 'NO_GRANT' ELSE 'PUBLISH_PREVIEW' END,'HIGH',
CASE WHEN n%100=9 THEN 'NEEDS_INFORMATION' WHEN n%100=10 OR n=940 THEN 'APPROVED' ELSE 'PENDING' END
FROM (SELECT b+i n FROM unnest(ARRAY[0,100,200,900]) b CROSS JOIN generate_series(1,40) i) x;
INSERT INTO approval_requests(id,organization_id,team_id,title,action_type,risk_level,status) VALUES
(pg_temp.ad_id('3050',9999),'31313131-3131-4131-8131-313131313131',pg_temp.ad_id('3010',2),'FOREIGN_APPROVAL_PRIVATE','PUBLISH_PREVIEW','HIGH','PENDING');
INSERT INTO approval_action_heads(action_id,organization_id,team_id,revision,payload_sha256)
SELECT pg_temp.ad_id('3060',right(id::text,12)::integer),organization_id,team_id,1,repeat('0',64) FROM approval_requests WHERE organization_id='30303030-3030-4030-8030-303030303030';
INSERT INTO approval_snapshots(approval_id,action_id,version,head_revision,canonical_payload,payload_sha256,expires_at,invalidated_at)
SELECT r.id,pg_temp.ad_id('3060',n),1,1,c.payload,encode(sha256(convert_to(c.payload,'UTF8')),'hex'),
CASE WHEN n%100=6 THEN '2000-01-01T00:00:00Z'::timestamptz ELSE '2099-01-01T00:00:00Z'::timestamptz END,
CASE WHEN n%100=7 THEN now() ELSE null END
FROM approval_requests r CROSS JOIN LATERAL (SELECT right(r.id::text,12)::integer n) x
CROSS JOIN LATERAL (SELECT jsonb_build_object(
'id',r.id,'organization_id',r.organization_id,'team_id',r.team_id,'mission_id',r.mission_id,'task_id',r.task_id,
'requested_by_agent_id',r.requested_by_agent_id,'requester_human_id',r.requester_human_id,'action_type',r.action_type,'risk_level',r.risk_level,
'actionId',pg_temp.ad_id('3060',n),'version',1,'headRevision',1,
'expiresAt',CASE WHEN n%100=6 THEN '2000-01-01T00:00:00Z' ELSE '2099-01-01T00:00:00Z' END,
 'parameters',jsonb_build_object('destination','internal-review','resourcePath','/release/approval-detail','visibility','TEAM'),
 'target','Staging preview /release/approval-detail','scope','Publish preview to internal review workspace only',
'effectSummary','Create one reviewable preview; no production deployment','estimatedCost','0.25','currency','USD') obj) j
CROSS JOIN LATERAL (SELECT '{'||string_agg(to_json(key)::text||':'||CASE WHEN key='parameters' THEN (SELECT '{'||string_agg(to_json(p.key)::text||':'||p.value::text,',' ORDER BY p.key COLLATE "C")||'}' FROM jsonb_each(value) p) ELSE value::text END,',' ORDER BY key COLLATE "C")||'}' payload FROM jsonb_each(j.obj)) c
WHERE r.organization_id='30303030-3030-4030-8030-303030303030' AND n%100<>8;
UPDATE approval_action_heads h SET payload_sha256=s.payload_sha256 FROM approval_snapshots s WHERE h.action_id=s.action_id;
UPDATE approval_action_heads SET active=false WHERE right(action_id::text,12)::integer%100=7;
UPDATE approval_action_heads SET revision=2 WHERE right(action_id::text,12)::integer%100=12;
UPDATE approval_snapshots SET payload_sha256=repeat('0',64) WHERE right(approval_id::text,12)::integer%100=13;
INSERT INTO approval_decisions(id,approval_id,status,actor_id,actor_name,reason,snapshot_version,snapshot_sha256,decided_at)
SELECT gen_random_uuid(),approval_id,'APPROVED',pg_temp.ad_id('3080',1),'Approval Detail Admin',null,version,payload_sha256,now() FROM approval_snapshots WHERE right(approval_id::text,12)::integer=940;

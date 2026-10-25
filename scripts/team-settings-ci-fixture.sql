-- Disposable acceptance data only. Apply after V016 and the qc@example.test account.
-- Native and each responsive browser project have separate organizations and actors.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Team settings fixture requires qc@example.test password source';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('50600000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Settings '||surface,'qc-settings-'||surface,'ACTIVE',now(),now()
FROM (VALUES (1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'settings-'||surface||'-'||kind||'@example.test','Settings '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES ('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES ('admin'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,o.id,CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'settings-'||substring(o.slug from 13)||'-%@example.test'
WHERE o.slug IN ('qc-settings-native','qc-settings-desktop','qc-settings-tablet','qc-settings-mobile');
INSERT INTO teams(id,organization_id,name,purpose,description,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('50610000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('50600000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'Settings Team '||t,
 CASE WHEN t=4 THEN NULL ELSE 'Settings acceptance' END,NULL,
 CASE t WHEN 2 THEN 'ACTIVE' WHEN 3 THEN 'ARCHIVED' ELSE 'DRAFT' END,
 CASE WHEN t=4 THEN NULL ELSE 'MANUAL' END,CASE WHEN t=4 THEN NULL ELSE 4 END,
 CASE WHEN t=4 THEN NULL ELSE 100 END,CASE WHEN t=4 THEN NULL ELSE 'USD' END,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,5) t;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('50620000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 ('50600000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 CASE WHEN a<=3 THEN ('50610000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid ELSE NULL END,
 'Settings Agent '||lpad(a::text,3,'0'),'Reviewer',CASE WHEN a IN (1,4) THEN 'MANAGER' ELSE 'MID' END,
 'DRAFT','SUGGEST',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,30) a;
UPDATE teams t SET manager_agent_id=('50620000-0000-4000-8000-'||lpad((n*1000+1)::text,12,'0'))::uuid
FROM generate_series(1,5) n WHERE t.id=('50610000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid;
-- Terminal mission attribution is deliberately retained: member 003 cannot detach.
INSERT INTO missions(id,organization_id,team_id,manager_agent_id,title,goal,status,priority,created_at,updated_at)
SELECT gen_random_uuid(),organization_id,team_id,id,'Historical settings mission','Retain attribution','COMPLETED','NORMAL',now(),now()
FROM agents WHERE name='Settings Agent 003';
-- Immutable Studio snapshots survive a legal detach of member 002.
INSERT INTO agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria)
SELECT id,'Historical responsibilities','Input','Output','Acceptance' FROM agents WHERE name='Settings Agent 002';
INSERT INTO agent_configuration_versions(organization_id,agent_id,revision,account_id,original_response)
SELECT a.organization_id,a.id,1,h.id,'{"historicalTeamId":"'||a.team_id||'"}'
FROM agents a JOIN human_accounts h ON h.email='settings-native-admin@example.test'
WHERE a.organization_id='50600000-0000-4000-8000-000000000001' AND a.name='Settings Agent 002';
INSERT INTO agent_configuration_receipts(organization_id,account_id,agent_id,idempotency_key,body_sha256,original_response)
SELECT organization_id,account_id,agent_id,'50670000-0000-4000-8000-000000000001',repeat('a',64),original_response
FROM agent_configuration_versions WHERE agent_id='50620000-0000-4000-8000-000000001002';
UPDATE agents SET configuration_revision=1 WHERE name='Settings Agent 002';
COMMIT;

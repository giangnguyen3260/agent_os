-- Disposable isolated read-only Agent Permissions acceptance data; never production seeds.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Agent Permissions fixture requires QC account hash source';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('50800000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Permissions '||surface,'qc-permissions-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'permissions-'||surface||'-'||kind||'@example.test',
 'Permissions '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,o.id,CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'permissions-'||substring(o.slug from 16)||'-%@example.test'
WHERE o.slug IN ('qc-permissions-native','qc-permissions-desktop','qc-permissions-tablet','qc-permissions-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('50810000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('50800000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Permissions Team','Persisted team context','ACTIVE','MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n;
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,configuration_revision,created_at,updated_at)
SELECT ('50820000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 ('50800000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 CASE WHEN a=2 THEN NULL ELSE ('50810000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid END,
 'Permissions Agent '||a,'Recorded reviewer','MID',
 CASE a WHEN 3 THEN 'ACTIVE' WHEN 4 THEN 'SUSPENDED' WHEN 5 THEN 'ARCHIVED' ELSE 'DRAFT' END,
 'SUGGEST',0,now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,5) a;
INSERT INTO agent_draft_config(agent_id,responsibilities,expected_input,output_format,acceptance_criteria,
 model,skills,tools,limits,concurrency,max_tokens_per_run,monthly_budget)
SELECT ('50820000-0000-4000-8000-'||lpad((n*1000+a)::text,12,'0'))::uuid,
 'Review specification','Input','Output','Acceptance',
 CASE WHEN a=3 THEN NULL WHEN a=4 THEN '' ELSE 'requested-model' END,
 CASE WHEN a=3 THEN NULL WHEN a=4 THEN '   ' ELSE E'Opaque skill request\nNo grant inferred' END,
 CASE WHEN a=3 THEN NULL WHEN a=4 THEN '' ELSE E'Read document; Write Figma\n<script>not executable</script>' END,
 NULL,2,1000,25
FROM generate_series(1,5) n CROSS JOIN (VALUES(1),(3),(4),(5)) x(a);
-- A real human approval grant is deliberately NOT Agent authority.
INSERT INTO approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at)
SELECT gen_random_uuid(),'50800000-0000-4000-8000-000000000001',id,
 '50810000-0000-4000-8000-000000000101','PUBLISH_PREVIEW','LOW',now()+interval '1 day'
FROM human_accounts WHERE email='permissions-native-member@example.test';
COMMIT;

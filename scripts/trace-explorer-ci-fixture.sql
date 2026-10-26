-- Disposable CI source data, never production events or an execution runtime.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM human_accounts WHERE email='qc@example.test') THEN
  RAISE EXCEPTION 'Trace fixture requires QC account hash source';
 END IF;
END $$;
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
SELECT ('50900000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'QC Trace '||surface,'qc-trace-'||surface,'ACTIVE',now(),now()
FROM (VALUES(1,'native'),(2,'desktop'),(3,'tablet'),(4,'mobile'),(5,'foreign')) s(n,surface);
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'trace-'||surface||'-'||kind||'@example.test',
 'Trace '||surface||' '||kind,a.password_hash
FROM human_accounts a CROSS JOIN (VALUES('native'),('desktop'),('tablet'),('mobile')) s(surface)
CROSS JOIN (VALUES('admin'),('member'),('other')) r(kind) WHERE a.email='qc@example.test';
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT a.id,o.id,CASE WHEN a.email LIKE '%-member@%' THEN 'MEMBER' ELSE 'ADMIN' END
FROM organizations o JOIN human_accounts a ON a.email LIKE 'trace-'||substring(o.slug from 10)||'-%@example.test'
WHERE o.slug IN ('qc-trace-native','qc-trace-desktop','qc-trace-tablet','qc-trace-mobile');
INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
SELECT ('50910000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('50900000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 'Trace Team','Recorded activity context','ACTIVE','MANUAL',10,1000,'USD',now(),now()
FROM generate_series(1,5) n;
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)
SELECT ('50920000-0000-4000-8000-'||lpad((n*100+m)::text,12,'0'))::uuid,
 ('50900000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 CASE WHEN m=3 THEN NULL ELSE ('50910000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid END,
 'Trace Mission '||m,'Stored mission','DRAFT','NORMAL',now(),now()
FROM generate_series(1,5) n CROSS JOIN generate_series(1,3) m;
INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status)
SELECT ('50930000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 ('50900000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 ('50910000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid,
 ('50920000-0000-4000-8000-'||lpad((n*100+t)::text,12,'0'))::uuid,
 'Trace Task '||t,'DRAFT'
FROM generate_series(1,5) n CROSS JOIN generate_series(1,2) t;
INSERT INTO audit_events(id,organization_id,mission_id,task_id,actor_type,event_type,payload,created_at)
SELECT ('50940000-0000-4000-8000-'||lpad((n*1000+e)::text,12,'0'))::uuid,
 ('50900000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,
 CASE e WHEN 31 THEN NULL WHEN 32 THEN ('50920000-0000-4000-8000-'||lpad((n*100+3)::text,12,'0'))::uuid
 WHEN 33 THEN NULL WHEN 36 THEN ('50920000-0000-4000-8000-'||lpad((n*100+2)::text,12,'0'))::uuid
 WHEN 37 THEN '50920000-0000-4000-8000-000000000501'::uuid
 ELSE ('50920000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid END,
 CASE WHEN e IN (32,33,37,38) THEN NULL WHEN e=34 THEN '50930000-0000-4000-8000-000000000501'::uuid
 WHEN e=35 THEN '50930000-0000-4000-8000-999999999999'::uuid
 ELSE ('50930000-0000-4000-8000-'||lpad((n*100+1)::text,12,'0'))::uuid END,
 CASE WHEN e=38 THEN 'OPAQUE<script>' ELSE 'HUMAN' END,
 CASE WHEN e=38 THEN E'literal%_!\\<script>' WHEN e<=30 THEN 'TraceEvent '||lpad(e::text,2,'0') ELSE 'SpecialEvent '||e END,
 '{"secret":"TRACE_PAYLOAD_MUST_NEVER_LEAK","token":"never-return-this"}',
 '2026-10-01T12:00:00Z'::timestamptz
FROM generate_series(1,4) n CROSS JOIN generate_series(1,38) e;
COMMIT;

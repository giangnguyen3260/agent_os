-- Disposable recorded-output acceptance data; not a runtime/provider execution claim.
BEGIN;
INSERT INTO human_accounts(id,email,display_name,password_hash)
SELECT gen_random_uuid(),'run-review-admin@example.test','Run Review QC',password_hash
FROM human_accounts WHERE email='qc@example.test';
INSERT INTO organizations(id,name,slug,status,created_at,updated_at)
VALUES ('81818181-8181-4181-8181-818181818181','Run Review QC','run-review-qc','ACTIVE',now(),now());
INSERT INTO organization_memberships(account_id,organization_id,role)
SELECT id,'81818181-8181-4181-8181-818181818181','ADMIN' FROM human_accounts WHERE email='run-review-admin@example.test';
INSERT INTO teams(id,organization_id,name,status,created_at,updated_at)
VALUES ('81000000-0000-4000-8000-000000000001','81818181-8181-4181-8181-818181818181','Run QC','ACTIVE',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
VALUES ('81000000-0000-4000-8000-000000000002','81818181-8181-4181-8181-818181818181','81000000-0000-4000-8000-000000000001','Reviewer','Reviewer','MID','ACTIVE','SUGGEST',now(),now());
INSERT INTO missions(id,organization_id,team_id,title,goal,status,priority,created_at,updated_at)
VALUES ('81000000-0000-4000-8000-000000000003','81818181-8181-4181-8181-818181818181','81000000-0000-4000-8000-000000000001','Run review mission','Review recorded output','RUNNING','NORMAL',now(),now());
INSERT INTO accepted_mission_plans(organization_id,team_id,mission_id,plan_revision,accepted_by)
SELECT '81818181-8181-4181-8181-818181818181','81000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000003',1,id FROM human_accounts WHERE email='run-review-admin@example.test';
INSERT INTO mission_execution_controls(organization_id,team_id,mission_id,state,revision)
VALUES ('81818181-8181-4181-8181-818181818181','81000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000003','OPEN',1);
DO $$
DECLARE n integer; t uuid; r uuid; a uuid;
  o uuid := '81818181-8181-4181-8181-818181818181';
  team uuid := '81000000-0000-4000-8000-000000000001';
  m uuid := '81000000-0000-4000-8000-000000000003';
  content text := '<script>window.privateOutputExecuted=true</script>' || chr(10) || 'Recorded review evidence';
  hash text;
BEGIN
  hash := encode(sha256(convert_to(content,'UTF8')),'hex');
  FOR n IN 1..6 LOOP
    t := ('82000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid;
    r := ('83000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid;
    a := ('84000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid;
    INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status) VALUES(t,o,team,m,'Run review task '||n,'IN_REVIEW');
    INSERT INTO accepted_mission_plan_tasks(organization_id,team_id,mission_id,plan_revision,task_id,owner_agent_id,task_edit_revision,task_owner_revision,dependency_revision)
    VALUES(o,team,m,1,t,'81000000-0000-4000-8000-000000000002',0,0,0);
    INSERT INTO execution_runs(id,organization_id,team_id,mission_id,task_id,plan_revision,status,revision,max_reworks) VALUES(r,o,team,m,t,1,'IN_REVIEW',2,1);
    INSERT INTO execution_attempts(id,run_id,attempt_number,status,outcome_slot) VALUES(a,r,1,'SUCCEEDED',1);
    INSERT INTO execution_attempt_reconciliations(run_id,attempt_id,outcome,evidence_sha256,output_sha256,reconciled_at) VALUES(r,a,'SUCCEEDED',repeat('b',64),hash,now());
    INSERT INTO execution_reconciled_review_submissions(run_id,attempt_id,source_run_revision,submitted_at) VALUES(r,a,1,now());
    INSERT INTO execution_verified_output_content(run_id,attempt_id,output_sha256,content,stored_at) VALUES(r,a,hash,content,now());
  END LOOP;
END $$;
COMMIT;

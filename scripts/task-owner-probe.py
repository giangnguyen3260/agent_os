"""Task owner real HTTP/CSRF/PostgreSQL QC on isolated prefix550 fixtures.
No application imports, synthetic business successes or production seeds.
"""
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
import re
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
BASE=os.environ.get('E2E_BASE_URL','http://127.0.0.1:18080')
PSQL=['docker','compose','exec','-T','postgres','sh','-c','psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']

def ident(prefix, n):
    return f'{prefix}-0000-4000-8000-{n:012d}'

ORG,TEAM,MISSION,TASK=(ident(p,n) for p,n in [('55000000',1),('55010000',101),('55020000',101),('55030000',1001)])
TASK_ROOT=f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/tasks/{TASK}'
ROOT=TASK_ROOT+'/draft-owner'
CONTEXT=set('organizationId teamId missionId taskId checkedAt revision fingerprint teamName missionTitle taskTitle taskStatus owner canEdit blockers'.split())
RECEIPT=set('organizationId teamId missionId taskId eventId revision committedAt before after'.split())
CANDIDATE=set('id name level status fingerprint'.split())
AGENT=set('id name level status'.split())
PAGE=set('organizationId teamId missionId taskId page pageSize total query items'.split())

def sql(query):
    return subprocess.check_output(PSQL, input=query, text=True, timeout=20).strip()

def rows(table, where='true'):
    assert re.fullmatch('[a-z_]+', table)
    return json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY row_to_json(x)::text),'[]') FROM {table} x WHERE {where};"))

def snapshot():
    # Compare every persisted business table, including all other organizations,
    # history, runtime/grants and receipts. Authentication tables alone are excluded.
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','human_accounts','organization_memberships','authentication_audit','authentication_attempts') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
    entries = [f"'{table}',(SELECT md5(coalesce(string_agg(row_to_json(x)::text,'|' ORDER BY row_to_json(x)::text),'')) FROM {table} x)" for table in tables]
    return json.loads(sql('SELECT json_build_object(' + ','.join(entries) + ');'))

class Client:
    def __init__(self, kind='admin'):
        self.kind=kind
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'}
        assert self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'task-owner-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
        }).encode(), headers)[0] == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        self.headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/json'}

    def request(self, path, body=None, headers=None):
        try:
            response = self.opener.open(urllib.request.Request(BASE + path, data=body, headers=headers or {}), timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                result = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                result = {'nonJson': True}  # HTML errors must not leak bodies in assertion output.
            return response.status, result

    def save(self, body, key=None, path=ROOT):
        return self.request(path, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key or str(uuid.uuid4())}))

def instant(value):
    assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value)
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))

def reject(action, status, code=None):
    before = snapshot()
    result = action()
    assert result[0] == status, (result[0], status)
    if code:
        assert result[1]['error']['code'] == code, result[1].get('error', {}).get('code')
    assert snapshot() == before, 'Read/rejection/replay mutated business data'
    return result

def race(actions):
    barrier = threading.Barrier(len(actions))
    def run(action):
        barrier.wait(timeout=5)
        return action()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(actions)) as pool:
        return list(pool.map(run, actions))

def lock_wait(client, mutation, body):
    proc = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        proc.stdin.write(f"BEGIN; SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE; SELECT pg_backend_pid();\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == ORG
        pid = int(proc.stdout.readline().strip())
        wait_query=f"SELECT pid FROM pg_stat_activity WHERE {pid}=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock' AND query ~* 'select id from organizations where id=.*for update';"
        assert sql(wait_query)=='', 'Unexpected preexisting organization lock waiter'
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(client.save, body)
            try:
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    waiting=sql(wait_query).splitlines()
                    if waiting:
                        assert len(waiting)==1 and not future.done(), 'Ambiguous wait attribution'
                        assert sql(wait_query).splitlines()==waiting and not future.done()
                        break
                    time.sleep(.05)
                else:
                    raise AssertionError('No observed real HTTP command blocked by organization row lock')
            finally:
                proc.stdin.write(mutation + ' COMMIT;\n'); proc.stdin.flush()
            assert future.result(timeout=27)[0] in (401, 403)
    finally:
        if proc.poll() is None:
            proc.stdin.write('ROLLBACK;\n\\q\n'); proc.stdin.flush()
        proc.communicate(timeout=10)


def task_id(n): return ident('55030000',1000+n)
def agent_id(n): return ident('55040000',1000+n)
def path(n=1):
    team=ident('55010000',102 if n==8 else 101)
    mission=ident('55020000',100+({6:2,7:3,8:4}.get(n,1)))
    return f'/api/v1/organizations/{ORG}/teams/{team}/missions/{mission}/tasks/{task_id(n)}/draft-owner'
def task(n=1): return rows('tasks',f"id='{task_id(n)}'")[0]
def safe_text(v,limit,blank=False):
    return isinstance(v,str) and len(v)<=limit and (blank or bool(v.strip())) and not any(ord(c)<32 or 127<=ord(c)<=159 or 0xd800<=ord(c)<=0xdfff for c in v)
def safe_agent(a):
    assert set(a)==AGENT
    assert re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}',a['id'])
    assert safe_text(a['name'],255) and safe_text(a['level'],50) and safe_text(a['status'],50)
def safe_owner(o,after=False):
    assert set(o)=={'state','agent'}
    assert o['state'] in (('ASSIGNED','UNASSIGNED') if after else ('ASSIGNED','UNASSIGNED','UNAVAILABLE'))
    if o['state']=='ASSIGNED':
        safe_agent(o['agent'])
        if after: assert o['agent']['status'] in ('DRAFT','ACTIVE')
    else: assert o['agent'] is None

def context(c,n=1):
    value=reject(lambda:c.request(path(n)),200)[1]['data']
    assert set(value)==CONTEXT and value['taskId']==task_id(n) and value['organizationId']==ORG
    assert type(value['revision']) is int and 0<=value['revision']<=2147483647
    assert re.fullmatch('[0-9a-f]{64}',value['fingerprint']);instant(value['checkedAt'])
    safe_owner(value['owner'])
    raw=task(n)
    assert [value[k] for k in ('teamId','missionId','taskStatus')]==[raw[k] for k in ('team_id','mission_id','status')]
    resolved=rows('agents',f"id='{raw['owner_agent_id']}' AND organization_id='{ORG}' AND team_id='{raw['team_id']}'") if raw['owner_agent_id'] else []
    expected={'state':'UNASSIGNED','agent':None} if raw['owner_agent_id'] is None else ({'state':'ASSIGNED','agent':{k:resolved[0][k] for k in ('id','name','level','status')}} if resolved else {'state':'UNAVAILABLE','agent':None})
    assert value['owner']==expected
    assert safe_text(value['teamName'],255) and safe_text(value['missionTitle'],255) and safe_text(value['taskTitle'],255,True)
    assert value['canEdit']==(value['blockers']==[]) and len(set(value['blockers']))==len(value['blockers'])
    return value

def candidate(c,a,n=1):
    value=reject(lambda:c.request(path(n)+'/eligible-owners/'+agent_id(a)),200)[1]['data']
    assert set(value)==CANDIDATE
    safe_agent({k:v for k,v in value.items() if k!='fingerprint'})
    raw=rows('agents',f"id='{agent_id(a)}' AND organization_id='{ORG}' AND team_id='{context(c,n)['teamId']}'")[0]
    assert {k:v for k,v in value.items() if k!='fingerprint'}=={k:raw[k] for k in ('id','name','level','status')}
    assert value['id']==agent_id(a) and value['status'] in ('DRAFT','ACTIVE') and re.fullmatch('[0-9a-f]{64}',value['fingerprint'])
    return value

def body(source,chosen):
    return {'expectedRevision':source['revision'],'expectedFingerprint':source['fingerprint'],
            'ownerAgentId':chosen['id'] if chosen else None,'ownerFingerprint':chosen['fingerprint'] if chosen else None}

def before(n=1):
    return {'task':task(n),'otherTasks':rows('tasks',f"id<>'{task_id(n)}'"),
            'protected':{t:rows(t) for t in ('teams','missions','agents','task_dependencies')},'hashes':snapshot(),
            'history':{t:rows(t) for t in ('task_owner_events','task_owner_receipts','audit_events')}}

def effect(c,old,source,chosen,key,result,n=1):
    assert result[0]==201,result[0]
    r=result[1]['data'];assert set(r)==RECEIPT
    assert [r[k] for k in ('organizationId','teamId','missionId','taskId')]==[source[k] for k in ('organizationId','teamId','missionId','taskId')]
    assert type(r['revision']) is int and r['revision']==source['revision']+1
    instant(r['committedAt']);safe_owner(r['before']);safe_owner(r['after'],True)
    assert r['before']==source['owner']
    assert r['after']==({'state':'ASSIGNED','agent':{k:v for k,v in chosen.items() if k!='fingerprint'}} if chosen else {'state':'UNASSIGNED','agent':None})
    now=task(n);allowed={'owner_agent_id','task_owner_revision','updated_at'}
    assert {k:v for k,v in now.items() if k not in allowed}=={k:v for k,v in old['task'].items() if k not in allowed}
    assert now['owner_agent_id']==(chosen['id'] if chosen else None) and now['task_owner_revision']==r['revision']
    assert dt.datetime.fromisoformat(now['updated_at'])==instant(r['committedAt']) and now['updated_at']!=old['task']['updated_at']
    assert rows('tasks',f"id<>'{task_id(n)}'")==old['otherTasks']
    assert {t:rows(t) for t in old['protected']}==old['protected']
    delta=snapshot();assert {t for t,v in old['hashes'].items() if delta[t]!=v}=={'tasks','task_owner_events','task_owner_receipts','audit_events'}
    for table,previous in old['history'].items():
        current=rows(table)
        assert len(current)==len(previous)+1
        if table=='task_owner_events': retained=[x for x in current if x['id']!=r['eventId']]
        elif table=='task_owner_receipts': retained=[x for x in current if not (x['organization_id']==source['organizationId'] and x['idempotency_key']==key and x['task_id']==source['taskId'])]
        else: retained=[x for x in current if not (x['organization_id']==source['organizationId'] and x['task_id']==source['taskId'] and x['event_type']=='TASK_OWNER_UPDATED' and json.loads(x['payload']).get('eventId')==r['eventId'])]
        assert retained==previous, 'Preexisting history/receipt/audit changed'
    stored=rows('task_owner_receipts',f"organization_id='{ORG}' AND idempotency_key='{key}'")
    assert len(stored)==1 and json.loads(stored[0]['original_response'])==r
    assert stored[0]['event_id']==r['eventId'] and re.fullmatch('[0-9a-f]{64}',stored[0]['body_sha256'])
    event=rows('task_owner_events',f"id='{r['eventId']}'")[0]
    assert re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}',r['eventId'])
    for item in (event,stored[0]): assert [item[k] for k in ('organization_id','team_id','mission_id','task_id')]==[r[k] for k in ('organizationId','teamId','missionId','taskId')]
    assert event['revision']==r['revision'] and json.loads(event['before_json'])==r['before'] and json.loads(event['after_json'])==r['after']
    assert event['before_owner_agent_id']==old['task']['owner_agent_id'] and event['after_owner_agent_id']==now['owner_agent_id']
    actor=sql(f"SELECT id FROM human_accounts WHERE email='task-owner-native-{c.kind}@example.test';")
    assert event['actor_id']==stored[0]['actor_id']==actor
    for item in (event,stored[0]): assert dt.datetime.fromisoformat(item['created_at'])==instant(r['committedAt'])
    audit=rows('audit_events',f"organization_id='{ORG}' AND task_id='{task_id(n)}' AND event_type='TASK_OWNER_UPDATED'")
    audit=next(x for x in audit if json.loads(x['payload'])['eventId']==r['eventId'])
    assert json.loads(audit['payload'])=={'eventId':r['eventId'],'revision':r['revision']}
    assert audit['actor_type']=='HUMAN' and audit['actor_id']==actor and audit['mission_id']==source['missionId']
    assert dt.datetime.fromisoformat(audit['created_at'])==instant(r['committedAt'])
    return r,audit

def change(c,target,n=1):
    source=context(c,n);chosen=candidate(c,target,n) if target else None
    command=body(source,chosen);key=str(uuid.uuid4());old=before(n)
    result=c.save(command,key,path(n));r,audit=effect(c,old,source,chosen,key,result,n)
    return r,key,command,audit

def main():
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('Owner probe exceeded299seconds')));signal.alarm(299)
    a,a2,b,member,foreign=Client(),Client(),Client('admin2'),Client('member'),Client('other')
    assert context(a)['owner']=={'state':'UNASSIGNED','agent':None}
    first=reject(lambda:a.request(ROOT+'/eligible-owners?page=0&query='),200)[1]['data']
    second=reject(lambda:a.request(ROOT+'/eligible-owners?page=1&query='),200)[1]['data']
    assert set(first)==set(second)==PAGE and first['total']==second['total']==25
    assert len(first['items'])==20 and len(second['items'])==5
    assert not {x['id'] for x in first['items']} & {x['id'] for x in second['items']}
    assert all(set(x)==CANDIDATE and x['status'] in ('DRAFT','ACTIVE') for x in first['items']+second['items'])
    expected=rows('agents',f"organization_id='{ORG}' AND team_id='{TEAM}' AND status IN ('DRAFT','ACTIVE')")
    expected.sort(key=lambda x:(x['name'],x['id']))
    for page_no,page in enumerate((first,second)):
        assert [page[k] for k in ('organizationId','teamId','missionId','taskId')]==[ORG,TEAM,MISSION,TASK]
        assert (page['page'],page['pageSize'],page['query'])==(page_no,20,'')
        for item,raw in zip(page['items'],expected[page_no*20:(page_no+1)*20],strict=True):
            safe_agent({k:v for k,v in item.items() if k!='fingerprint'})
            assert re.fullmatch('[0-9a-f]{64}',item['fingerprint'])
            assert {k:v for k,v in item.items() if k!='fingerprint'}=={k:raw[k] for k in ('id','name','level','status')}
            assert reject(lambda item=item:a.request(ROOT+'/eligible-owners/'+item['id']),200)[1]['data']==item
    for q in ('%','PRIVATE'):
        page=reject(lambda q=q:a.request(ROOT+'/eligible-owners?query='+urllib.parse.quote(q)),200)[1]['data'];assert page['total']==0 and page['items']==[]
    reject(lambda:a.request(ROOT+'/eligible-owners/'+agent_id(26)),404)
    reject(lambda:a.request(ROOT+'/eligible-owners/'+ident('55040000',5001)),404)
    reject(lambda:a.request(ROOT+'/eligible-owners/'+agent_id(27)),409,'TASK_OWNER_NOT_ELIGIBLE')
    for c in (member,foreign):
        reject(lambda c=c:c.request(ROOT),403)
        reject(lambda c=c:c.save(body(context(a),None)),403)
    reject(lambda:a.request(ROOT.replace(TEAM,ident('55010000',103))),404)
    reject(lambda:a.request(ROOT.replace(MISSION,ident('55020000',501))),404)
    reject(lambda:a.request(ROOT.replace(ORG,ident('55000000',5))),403)
    r,key,command,audit=change(a,1)
    assert reject(lambda:a.save(command,key),200)[1]['data']==r
    assert reject(lambda:a.request(ROOT+'/receipts/'+key),200)[1]['data']=={'receipt':r}
    assert reject(lambda:b.request(ROOT+'/receipts/'+key),200)[1]['data']=={'receipt':None}
    reject(lambda:a.save(dict(command,ownerAgentId=None,ownerFingerprint=None),key),409,'IDEMPOTENCY_CONFLICT')
    trace=reject(lambda:a.request(f"/api/v1/organizations/{ORG}/trace-events/{audit['id']}"),200)[1]['data']['event']
    assert set(trace)==set('id eventType actorType createdAt team mission task'.split()) and trace['task']['id']==TASK and trace['mission']['id']==MISSION and trace['eventType']=='TASK_OWNER_UPDATED'
    reject(lambda:a.save(body(context(a),candidate(a,1))),409,'TASK_OWNER_NO_CHANGE')
    # Genuine current ineligible and cross-org unavailable pointers are safe to clear.
    unavailable=context(a,3);assert unavailable['owner']=={'state':'UNAVAILABLE','agent':None}
    assert 'PRIVATE_FOREIGN_OWNER' not in json.dumps(unavailable) and ident('55040000',5001) not in json.dumps(unavailable)
    cleared,_,_,_=change(a,None,3);assert cleared['before']['state']=='UNAVAILABLE' and cleared['after']['state']=='UNASSIGNED'
    ineligible=context(a,4);assert ineligible['owner']['state']=='ASSIGNED' and ineligible['owner']['agent']['status']=='SUSPENDED'
    change(a,2,4)
    for n,reason in ((5,'TASK_NOT_DRAFT'),(6,'INTAKE_PAUSED'),(7,'MISSION_NOT_EDITABLE'),(8,'TEAM_ARCHIVED'),(9,'REVISION_EXHAUSTED')):
        c=context(a,n);assert not c['canEdit'] and reason in c['blockers']
        reject(lambda n=n,c=c:a.save(body(c,None),path=path(n)),409,'TASK_OWNER_BLOCKED')
    # Legacy nullable input and unrelated exhausted counters are preserved.
    sql(f"UPDATE missions SET task_intake_version=9223372036854775807 WHERE id='{MISSION}'; UPDATE tasks SET task_edit_revision=2147483647 WHERE id='{task_id(10)}';")
    assert context(a,10)['canEdit'];change(a,3,10)
    assert task(10)['description'] is None and task(10)['acceptance_criteria'] is None and task(10)['priority'] is None
    sql(f"UPDATE missions SET task_intake_version=7 WHERE id='{MISSION}';")
    # Actual input edit invalidates owner source; owner ABA invalidates old input editor even with restored timestamp.
    old=context(a);chosen=candidate(a,4)
    edit=TASK_ROOT+'/draft-settings';draft=a.request(edit)[1]['data']
    edited=dict(draft['values'],expectedRevision=draft['revision'],expectedFingerprint=draft['fingerprint']);edited['description']='Changed actual input'
    assert a.save(edited,path=edit)[0]==201
    reject(lambda:a.save(body(old,chosen)),409,'TASK_OWNER_CONFLICT')
    draft=a.request(edit)[1]['data'];old_task=task();change(a,2);change(a,1)
    sql(f"UPDATE tasks SET updated_at='{old_task['updated_at']}' WHERE id='{TASK}';")
    edited=dict(draft['values'],expectedRevision=draft['revision'],expectedFingerprint=draft['fingerprint']);edited['title']='Stale ABA edit'
    reject(lambda:a.save(edited,path=edit),409,'TASK_EDIT_CONFLICT')
    # Candidate metadata/membership changes cannot silently alter reviewed choice.
    source=context(a);selected=candidate(a,17);agent=rows('agents',f"id='{agent_id(17)}'")[0]
    sql(f"UPDATE agents SET name='Changed candidate name' WHERE id='{agent_id(17)}';")
    reject(lambda:a.save(body(source,selected)),409,'TASK_OWNER_CANDIDATE_CONFLICT')
    sql(f"UPDATE agents SET name='{agent['name']}' WHERE id='{agent_id(17)}';")
    source=context(a);selected=candidate(a,18)
    sql(f"UPDATE agents SET team_id='{ident('55010000',103)}' WHERE id='{agent_id(18)}';")
    reject(lambda:a.save(body(source,selected)),409,'TASK_OWNER_NOT_ELIGIBLE')
    sql(f"UPDATE agents SET team_id='{TEAM}' WHERE id='{agent_id(18)}';")
    # Exact same-key race; then competing keys sharing the same source.
    source=context(a);chosen=candidate(a,5);cmd=body(source,chosen);k=str(uuid.uuid4());old=before()
    result=race([lambda:a.save(cmd,k),lambda:a2.save(cmd,k)])
    assert sorted(x[0] for x in result)==[200,201] and result[0][1]['data']==result[1][1]['data']
    effect(a,old,source,chosen,k,next(x for x in result if x[0]==201))
    source=context(a);choices=[candidate(a,6),candidate(a,7)];keys=[str(uuid.uuid4()),str(uuid.uuid4())];old=before()
    result=race([lambda:a.save(body(source,choices[0]),keys[0]),lambda:a2.save(body(source,choices[1]),keys[1])])
    assert sorted(x[0] for x in result)==[201,409];winner=next(i for i,x in enumerate(result) if x[0]==201)
    assert result[1-winner][1]['error']['code']=='TASK_OWNER_CONFLICT'
    effect(a,old,source,choices[winner],keys[winner],result[winner])
    assert reject(lambda:a.request(ROOT+'/receipts/'+keys[1-winner]),200)[1]['data']=={'receipt':None}
    # Corrupt receipt fails closed for both own lookup and matching replay, without private JSON leakage.
    stored=rows('task_owner_receipts',f"idempotency_key='{key}' AND organization_id='{ORG}'")[0]
    sql(f"UPDATE task_owner_receipts SET original_response='PRIVATE_CORRUPT_OWNER' WHERE organization_id='{ORG}' AND idempotency_key='{key}';")
    try:
        for action in (lambda:a.request(ROOT+'/receipts/'+key),lambda:a.save(command,key)):
            result=reject(action,500,'TASK_OWNER_UNAVAILABLE');assert 'PRIVATE_CORRUPT_OWNER' not in json.dumps(result[1])
    finally:
        encoded=stored['original_response'].replace("'","''");sql(f"UPDATE task_owner_receipts SET original_response='{encoded}' WHERE organization_id='{ORG}' AND idempotency_key='{key}';")
    # Structurally valid but impossible or event-inconsistent stored receipts also fail closed.
    for corrupt in (
        dict(r,before={'state':'UNASSIGNED','agent':None},after={'state':'UNASSIGNED','agent':None}),
        dict(r,revision=r['revision']+1),
        dict(r,committedAt='2001-01-01T00:00:00Z'),
    ):
        encoded=json.dumps(corrupt).replace("'","''")
        sql(f"UPDATE task_owner_receipts SET original_response='{encoded}' WHERE organization_id='{ORG}' AND idempotency_key='{key}';")
        try:
            for action in (lambda:a.request(ROOT+'/receipts/'+key),lambda:a.save(command,key)):
                reject(action,500,'TASK_OWNER_UNAVAILABLE')
        finally:
            encoded=stored['original_response'].replace("'","''")
            sql(f"UPDATE task_owner_receipts SET original_response='{encoded}' WHERE organization_id='{ORG}' AND idempotency_key='{key}';")
    # Three independent actual insert failures must roll back the entire write.
    for table in ('task_owner_events','task_owner_receipts','audit_events'):
        condition=f"NEW.organization_id='{ORG}'"+(" AND NEW.event_type='TASK_OWNER_UPDATED'" if table=='audit_events' else '')
        try:
            sql(f"CREATE FUNCTION task_owner_qc_reject() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF {condition} THEN RAISE EXCEPTION 'QC forced owner insert failure'; END IF; RETURN NEW; END$$; CREATE TRIGGER task_owner_qc_reject BEFORE INSERT ON {table} FOR EACH ROW EXECUTE FUNCTION task_owner_qc_reject();")
            reject(lambda:a.save(body(context(a),candidate(a,19))),500,'TASK_OWNER_UNAVAILABLE')
        finally: sql(f"DROP TRIGGER IF EXISTS task_owner_qc_reject ON {table}; DROP FUNCTION IF EXISTS task_owner_qc_reject();")
    # Strict real HTTP input without any business changes.
    valid=body(context(a),candidate(a,20));wire=json.dumps(valid).encode();headers=dict(a.headers,**{'Idempotency-Key':str(uuid.uuid4())})
    for raw in (b'{}',wire[:-1]+b',"extra":1}',wire[:-1]+b',"ownerAgentId":null}',wire+b'{}',wire+b' '*4096,b'\xff',json.dumps(dict(valid,expectedRevision='0')).encode(),json.dumps(dict(valid,ownerAgentId=None)).encode()):
        reject(lambda raw=raw:a.request(ROOT,raw,headers),400)
    for suffix in ('?x=1','?page=0'):
        reject(lambda suffix=suffix:a.request(ROOT+suffix),400)
    for suffix in ('?page=-1','?page=01','?page=0&page=1','?x=1'):
        reject(lambda suffix=suffix:a.request(ROOT+'/eligible-owners'+suffix),400)
    for target in (ROOT,ROOT+'/receipts/'+key,ROOT+'/eligible-owners'):
        old=snapshot()
        try: response=a.opener.open(urllib.request.Request(BASE+target,data=b'{}',method='GET'),timeout=25)
        except urllib.error.HTTPError as error: response=error
        with response: assert response.status==400
        assert snapshot()==old
    # Historical original result survives later intake/lifecycle/Agent move, but current role still gates it.
    sql(f"UPDATE teams SET status='ARCHIVED' WHERE id='{TEAM}'; UPDATE missions SET status='COMPLETED',task_intake_paused=true WHERE id='{MISSION}'; UPDATE agents SET team_id='{ident('55010000',103)}' WHERE id='{agent_id(1)}';")
    assert reject(lambda:a.save(command,key),200)[1]['data']==r
    assert reject(lambda:a.request(ROOT+'/receipts/'+key),200)[1]['data']=={'receipt':r}
    sql(f"UPDATE teams SET status='ACTIVE' WHERE id='{TEAM}'; UPDATE missions SET status='CREATED',task_intake_paused=false WHERE id='{MISSION}'; UPDATE agents SET team_id='{TEAM}' WHERE id='{agent_id(1)}';")
    # A real row-lock wait is observed before revoking current role, enabled flag or credentials.
    actor=sql("SELECT id FROM human_accounts WHERE email='task-owner-native-admin2@example.test';")
    own=f"organization_id='{ORG}' AND account_id='{actor}'"
    for mutation in (f"UPDATE organization_memberships SET role='MEMBER' WHERE {own};",f"UPDATE human_accounts SET enabled=false WHERE id='{actor}';",f"UPDATE human_accounts SET credential_version=credential_version+1 WHERE id='{actor}';"):
        viewer=Client('admin2');account=rows('human_accounts',f"id='{actor}'")[0];cmd=body(context(viewer),candidate(viewer,22));old=snapshot()
        try: lock_wait(viewer,mutation,cmd);assert snapshot()==old
        finally: sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE {own}; UPDATE human_accounts SET enabled=true,credential_version={account['credential_version']} WHERE id='{actor}';")
    actor=sql("SELECT id FROM human_accounts WHERE email='task-owner-native-admin@example.test';")
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='{ORG}' AND account_id='{actor}';")
    try:
        reject(lambda:a.request(ROOT+'/receipts/'+key),403)
        reject(lambda:a.save(command,key),403)
    finally: sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='{ORG}' AND account_id='{actor}';")
    print('PASS: task owner actual persistence/clear/privacy/candidates/ABA/replay/races/rollback/strict wire/authority and protected business data')


if __name__=='__main__': main()

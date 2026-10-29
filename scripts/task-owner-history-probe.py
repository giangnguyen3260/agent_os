"""Actual owner-history HTTP/PostgreSQL read boundary on prefix560 fixtures.
Only real owner API commands create history; no synthetic events or successful DTO mocks.
"""
import datetime as dt
import http.cookiejar
import json
import os
import re
import signal
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE=os.environ.get('E2E_BASE_URL','http://127.0.0.1:18080')
PSQL=['docker','compose','exec','-T','postgres','sh','-c','psql -X -q -t -A -v ON_ERROR_STOP=1 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"']

def ident(prefix, n):
    return f'{prefix}-0000-4000-8000-{n:012d}'

ORG,TEAM,MISSION,TASK=(ident(p,n) for p,n in [('56000000',1),('56010000',101),('56020000',101),('56030000',1001)])
ROOT=f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/tasks/{TASK}/draft-owner'
HISTORY=ROOT+'/history'

def sql(query):
    return subprocess.check_output(PSQL, input=query, text=True, timeout=20).strip()

def rows(table, where='true'):
    assert re.fullmatch('[a-z_]+', table)
    return json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY row_to_json(x)::text),'[]') FROM {table} x WHERE {where};"))

def snapshot():
    # Compare every persisted business table, including all other organizations,
    # identity/membership, history, runtime/grants and receipts. Include tuple
    # identity to detect same-value UPDATEs; exclude only session/auth audit state.
    # This observes final committed effects, not rolled-back SQL attempts.
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','authentication_audit','authentication_attempts') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
    entries = [f"'{table}',(SELECT md5(coalesce(string_agg(row_to_json(x)::text||'@'||x.xmin::text||':'||x.ctid::text,'|' ORDER BY row_to_json(x)::text,x.ctid),'')) FROM {table} x)" for table in tables]
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
            'username': f'owner-history-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
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
            self.last_raw = raw  # Retained for internal privacy validation, never assertion output.
            self.last_content_type = response.headers.get('Content-Type','').split(';')[0].lower()
            try:
                result = json.loads(raw.decode('utf-8'))
            except (ValueError, UnicodeDecodeError):
                raise AssertionError('API returned non-JSON or invalid UTF8; raw body withheld') from None
            if response.status>=400:
                assert b'PRIVATE_' not in raw and ident('56040000',5001).encode() not in raw, 'Private error marker; body withheld'
            envelope(result,response.status,path,self.last_content_type)
            return response.status, result

    def save(self, body, key=None, path=ROOT):
        return self.request(path, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key or str(uuid.uuid4())}))

def envelope(value,status,path,content_type):
    assert content_type=='application/json', 'Unexpected API media type; raw body withheld'
    assert isinstance(value,dict), 'Unexpected API envelope; raw body withheld'
    if status==405 and set(value)=={'timestamp','status','error','path'}:
        assert value['status']==405 and value['error']=='Method Not Allowed' and value['path']==path
        assert isinstance(value['timestamp'],str) and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)',value['timestamp'])
        dt.datetime.fromisoformat(value['timestamp'].replace('Z','+00:00'))
        return
    assert set(value)==({'error','meta'} if status>=400 else {'data','meta'}), 'Unexpected envelope keys'
    meta=value['meta'];assert isinstance(meta,dict) and set(meta)=={'requestId'}
    assert isinstance(meta['requestId'],str) and re.fullmatch(UUID_RE,meta['requestId'])
    if status>=400:
        error=value['error'];assert isinstance(error,dict) and set(error)=={'code','message','details'}
        allowed={400:{'BAD_REQUEST'},401:{'UNAUTHORIZED'},403:{'FORBIDDEN'},404:{'NOT_FOUND'},405:{'METHOD_NOT_ALLOWED'},500:{'TASK_OWNER_HISTORY_UNAVAILABLE'}}
        assert error['code'] in allowed.get(status,set()), 'Unexpected safe error code'
        assert error['details']=={}, 'Unexpected error details; raw body withheld'
        assert error['message'] in {'Request denied','Authentication required','Request could not be completed','Request cannot be completed','Saved history is unavailable'}, 'Unexpected error message; raw body withheld'

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

FIELDS=set('organizationId teamId missionId taskId teamName missionTitle taskTitle page pageSize total items'.split())
ITEM=set('eventId revision committedAt actorName before after'.split())
AGENT=set('id name level status'.split())
UUID_RE=r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}'

def literal(v): return "'"+str(v).replace("'","''")+"'"
def safe(v,n,blank=False):
    return isinstance(v,str) and len(v)<=n and (blank or bool(v.strip())) and not any(ord(c)<32 or 127<=ord(c)<=159 or 0xd800<=ord(c)<=0xdfff or ord(c) in (0x2028,0x2029) for c in v)
def owner(v,after=False):
    assert set(v)=={'state','agent'}
    assert v['state'] in (('UNASSIGNED','ASSIGNED') if after else ('UNASSIGNED','ASSIGNED','UNAVAILABLE'))
    if v['state']=='ASSIGNED':
        a=v['agent'];assert set(a)==AGENT and re.fullmatch(UUID_RE,a['id'])
        assert safe(a['name'],255) and safe(a['level'],50) and safe(a['status'],50)
        if after: assert a['status'] in ('DRAFT','ACTIVE')
    else: assert v['agent'] is None

def command(c,target):
    source=c.request(ROOT);assert source[0]==200
    source=source[1]['data'];selected=None
    if target:
        status,response=c.request(ROOT+'/eligible-owners/'+ident('56040000',1000+target));assert status==200
        selected=response['data']
    body={'expectedRevision':source['revision'],'expectedFingerprint':source['fingerprint'],
          'ownerAgentId':selected['id'] if selected else None,'ownerFingerprint':selected['fingerprint'] if selected else None}
    status,response=c.save(body);assert status==201,status
    return response['data']

def history(c,page=0,path=HISTORY,expected=21):
    result=reject(lambda:c.request(path+'?page='+str(page)),200)[1]['data']
    assert set(result)==FIELDS
    parts=path.split('/')
    assert [result[k] for k in ('organizationId','teamId','missionId','taskId')]==[parts[i] for i in (4,6,8,10)]
    assert result['teamName']==rows('teams',f"id='{result['teamId']}'")[0]['name']
    assert result['missionTitle']==rows('missions',f"id='{result['missionId']}'")[0]['title']
    assert result['taskTitle']==rows('tasks',f"id='{result['taskId']}'")[0]['title']
    assert type(result['page']) is int and result['page']==page and result['pageSize']==20
    assert type(result['total']) is int and result['total']==expected
    assert len(result['items'])==min(20,max(expected-page*20,0))
    assert safe(result['teamName'],255) and safe(result['missionTitle'],255) and safe(result['taskTitle'],255,True)
    events=rows('task_owner_events',f"organization_id='{result['organizationId']}' AND team_id='{result['teamId']}' AND mission_id='{result['missionId']}' AND task_id='{result['taskId']}'")
    events.sort(key=lambda x:x['revision'],reverse=True)
    assert len(events)==expected
    names=json.loads(sql(f"SELECT coalesce(json_object_agg(a.id::text,a.display_name),'{{}}') FROM organization_memberships m JOIN human_accounts a ON a.id=m.account_id WHERE m.organization_id='{ORG}';"))
    ids=set();previous=2147483648
    for item,event in zip(result['items'],events[page*20:(page+1)*20],strict=True):
        assert set(item)==ITEM and re.fullmatch(UUID_RE,item['eventId']) and item['eventId'] not in ids
        assert type(item['revision']) is int and 0<item['revision']<previous
        ids.add(item['eventId']);previous=item['revision']
        instant(item['committedAt']);owner(item['before']);owner(item['after'],True)
        assert item['eventId']==event['id'] and item['revision']==event['revision']
        assert instant(item['committedAt'])==dt.datetime.fromisoformat(event['created_at'])
        assert item['before']==json.loads(event['before_json']) and item['after']==json.loads(event['after_json'])
        assert event['before_owner_agent_id']!=event['after_owner_agent_id']
        for side in ('before','after'):
            raw=event[side+'_owner_agent_id'];value=item[side]
            if value['state']=='UNASSIGNED': assert raw is None
            elif value['state']=='ASSIGNED': assert raw==value['agent']['id']
            else: assert raw is not None and value['agent'] is None
        actual=names.get(event['actor_id'])
        assert item['actorName']==actual and (actual is None or safe(actual,255))
    encoded=json.dumps(result)
    assert 'PRIVATE_OWNER_HISTORY_FOREIGN' not in encoded and ident('56040000',5001) not in encoded
    for secret in ('actor_id','before_owner_agent_id','after_owner_agent_id','body_sha256','idempotency_key','original_response','fingerprint'):
        assert secret not in encoded
    return result

def main():
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('History probe exceeded299seconds')));signal.alarm(299)
    a,b,member,other=Client(),Client('admin2'),Client('member'),Client('other')
    assert history(a,expected=0)['items']==[]
    second=HISTORY.replace(TASK,ident('56030000',1002))
    assert history(a,path=second,expected=0)['items']==[]
    receipts=[]
    for n in range(21): receipts.append(command(a if n%2==0 else b,None if n==0 else (1 if n%2 else 2)))
    first=history(a);last=history(a,1)
    assert [x['revision'] for x in first['items']]==list(range(21,1,-1))
    assert [x['revision'] for x in last['items']]==[1]
    assert last['items'][0]['before']=={'state':'UNAVAILABLE','agent':None}
    assert history(a,100000)['items']==[]
    assert [x['eventId'] for x in first['items']+last['items']]==[x['eventId'] for x in reversed(receipts)]
    # Current member name can change; historical owner snapshots cannot.
    actor=sql("SELECT id FROM human_accounts WHERE email='owner-history-native-admin2@example.test';")
    account=rows('human_accounts',f"id='{actor}'")[0]
    try:
        sql(f"UPDATE human_accounts SET display_name='Renamed owner history actor' WHERE id='{actor}';")
        renamed=history(a)
        assert any(x['actorName']=='Renamed owner history actor' for x in renamed['items'])
        assert [(x['before'],x['after']) for x in renamed['items']]==[(x['before'],x['after']) for x in first['items']]
        sql(f"DELETE FROM organization_memberships WHERE organization_id='{ORG}' AND account_id='{actor}'; INSERT INTO organization_memberships(account_id,organization_id,role) VALUES ('{actor}','{ident('56000000',5)}','MEMBER'); UPDATE human_accounts SET display_name='PRIVATE_FORMER_ACTOR_OTHER_ORG' WHERE id='{actor}';")
        removed=history(a);assert any(x['actorName'] is None for x in removed['items'])
        assert 'PRIVATE_FORMER_ACTOR_OTHER_ORG' not in json.dumps(removed)
        for item in removed['items']:
            event=rows('task_owner_events',f"id='{item['eventId']}'")[0]
            if event['actor_id']==actor: assert item['actorName'] is None
    finally:
        sql(f"DELETE FROM organization_memberships WHERE organization_id='{ident('56000000',5)}' AND account_id='{actor}'; INSERT INTO organization_memberships(account_id,organization_id,role) VALUES ('{actor}','{ORG}','ADMIN') ON CONFLICT(account_id,organization_id) DO UPDATE SET role='ADMIN'; UPDATE human_accounts SET display_name={literal(account['display_name'])} WHERE id='{actor}';")
    agent=rows('agents',f"id='{ident('56040000',1001)}'")[0]
    try:
        sql(f"UPDATE agents SET name='PRIVATE_CURRENT_AGENT_MOVED',team_id='{ident('56010000',102)}',status='SUSPENDED',updated_at=clock_timestamp() WHERE id='{agent['id']}';")
        moved=history(a);assert moved==first
        assert 'PRIVATE_CURRENT_AGENT_MOVED' not in json.dumps(moved)
    finally:
        sql(f"UPDATE agents SET name={literal(agent['name'])},team_id='{agent['team_id']}',status={literal(agent['status'])},updated_at={literal(agent['updated_at'])} WHERE id='{agent['id']}';")
    task=rows('tasks',f"id='{TASK}'")[0]
    try:
        sql(f"UPDATE teams SET status='ARCHIVED' WHERE id='{TEAM}'; UPDATE missions SET status='COMPLETED',task_intake_paused=true WHERE id='{MISSION}'; UPDATE tasks SET status='COMPLETED',task_owner_revision=2147483647 WHERE id='{TASK}';")
        assert history(a)==first
    finally:
        sql(f"UPDATE teams SET status='ACTIVE' WHERE id='{TEAM}'; UPDATE missions SET status='CREATED',task_intake_paused=false WHERE id='{MISSION}'; UPDATE tasks SET status={literal(task['status'])},task_owner_revision={task['task_owner_revision']} WHERE id='{TASK}';")
    # Corruption is decoded only on the requested page; no repair side effects.
    oldest=rows('task_owner_events',f"organization_id='{ORG}' AND task_id='{TASK}' AND revision=1")[0]
    invalid=['PRIVATE_CORRUPT_HISTORY',oldest['before_json']+'{}',oldest['before_json'][:-1]+',"extra":"PRIVATE"}',json.dumps({'state':'UNASSIGNED','agent':None})]
    for bad in invalid:
        try:
            sql(f"UPDATE task_owner_events SET before_json={literal(bad)} WHERE id='{oldest['id']}';")
            assert history(a)==first
            result=reject(lambda:a.request(HISTORY+'?page=1'),500,'TASK_OWNER_HISTORY_UNAVAILABLE')
            assert 'PRIVATE_CORRUPT_HISTORY' not in json.dumps(result) and 'before_owner_agent_id' not in json.dumps(result)
            assert history(a,100000)['items']==[]
        finally: sql(f"UPDATE task_owner_events SET before_json={literal(oldest['before_json'])} WHERE id='{oldest['id']}';")
    # Scoped authority and strict real wire; every read/rejection preserves all business tables.
    for viewer in (member,other):
        reject(lambda viewer=viewer:viewer.request(HISTORY),403)
        reject(lambda viewer=viewer:viewer.request(HISTORY+'?page=bad&extra=1'),403)
    reject(lambda:a.request(HISTORY.replace(ORG,ident('56000000',5))),403)
    for path in (HISTORY.replace(TEAM,ident('56010000',102)),HISTORY.replace(MISSION,ident('56020000',102)),HISTORY.replace(TASK,ident('56030000',5001))): reject(lambda path=path:a.request(path),404)
    for query in ('page=-1','page=01','page=+0','page=1.0','page=1e0','page=100001','page=0&page=1','page=0&extra=1','query='):
        reject(lambda query=query:a.request(HISTORY+'?'+query),400)
    before=snapshot()
    try: response=a.opener.open(urllib.request.Request(BASE+HISTORY,data=b'{}',method='GET'),timeout=25)
    except urllib.error.HTTPError as error: response=error
    with response:
        assert response.status==400
        raw=response.read()
        try: error_body=json.loads(raw.decode('utf-8'))
        except (ValueError,UnicodeDecodeError): raise AssertionError('GET-body rejection is not safe JSON; body withheld') from None
        envelope(error_body,response.status,HISTORY,response.headers.get('Content-Type','').split(';')[0].lower())
    assert snapshot()==before
    old=snapshot();unsupported=a.request(HISTORY,b'{}',a.headers)
    assert unsupported[0] in (403,405), 'Unsupported POST unexpectedly accepted'
    assert snapshot()==old
    assert history(a)==first and history(a,1)==last
    print('PASS: owner history actual21commands/revision pages/current scoped actor/null/historical snapshots/foreign privacy/lifecycle/strict wire/selected corruption and unchanged committed business values/tuple identities')

if __name__=='__main__': main()

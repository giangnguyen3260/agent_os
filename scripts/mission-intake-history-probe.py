"""Mission intake history real HTTP/PostgreSQL QC; disposable fixture only.
No application imports, fabricated history or mocked successful business calls.
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

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
CONTEXT = {'organizationId', 'teamId', 'missionId', 'checkedAt', 'teamName', 'missionTitle', 'teamStatus', 'missionStatus', 'intakePaused', 'intakeVersion', 'fingerprint', 'canChange', 'blockers'}


def ident(prefix, n):
    return f'{prefix}-0000-4000-8000-{n:012d}'

ORG, TEAM, MISSION = (ident(p, n) for p, n in (
    ('54000000', 1), ('54010000', 101), ('54020000', 101)))
MISSION_ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}'
ROOT = MISSION_ROOT + '/task-intake'
HISTORY = ROOT + '/history'
PAGE = {'organizationId','teamId','missionId','teamName','missionTitle','page','pageSize','total','items'}
ITEM = {'eventId','versionBefore','versionAfter','pausedBefore','pausedAfter','committedAt','actorName'}

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
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'}
        assert self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'intake-history-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
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

    def context(self, path=ROOT):
        status, body = self.request(path)
        assert status == 200, status
        context = body['data']
        assert set(context) == CONTEXT
        assert re.fullmatch('[0-9a-f]{64}', context['fingerprint'])
        instant(context['checkedAt'])
        assert isinstance(context['intakeVersion'], str) and re.fullmatch(r'0|[1-9][0-9]*', context['intakeVersion'])
        assert int(context['intakeVersion']) <= 9223372036854775807
        assert context['canChange'] == (context['blockers'] == [])
        assert len(context['blockers']) == len(set(context['blockers']))
        return context

    def save(self, body, key=None, path=ROOT):
        return self.request(path, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key or str(uuid.uuid4())}))

def instant(value):
    assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value)
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))

def desired(context, paused=None):
    return {'expectedVersion': context['intakeVersion'], 'expectedFingerprint': context['fingerprint'],
            'paused': not context['intakePaused'] if paused is None else paused}

def mission():
    return rows('missions', f"id='{MISSION}'")[0]

def reject(action, status, code=None):
    before = snapshot()
    result = action()
    assert result[0] == status, (result[0], status)
    if code:
        assert result[1]['error']['code'] == code, result[1].get('error', {}).get('code')
    assert snapshot() == before, 'Read/rejection/replay mutated business data'
    return result


def instant_key(value):
    instant(value)  # Validate calendar/time semantics; preserve all9 digits for order.
    seconds=value[:19]
    fraction=value[19:-1].removeprefix('.').ljust(9,'0')
    return seconds+'.'+fraction


def safe_name(value):
    return (isinstance(value,str) and bool(value.strip()) and len(value)<=255
            and not any(ord(c)<32 or 127<=ord(c)<=159 or 0xd800<=ord(c)<=0xdfff for c in value))


def order(items):
    keys=[(instant_key(x['committedAt']),x['eventId']) for x in items]
    assert keys==sorted(keys,reverse=True)
    assert len({x['eventId'] for x in items})==len(items)


def history(client, page=0, path=HISTORY):
    value = reject(lambda: client.request(path + '?page=' + str(page)), 200)[1]['data']
    assert set(value) == PAGE
    assert [value[k] for k in ('organizationId','teamId','missionId')] == [ORG,TEAM,MISSION]
    assert safe_name(value['teamName']) and safe_name(value['missionTitle'])
    assert type(value['page']) is int and value['page'] == page and value['pageSize'] == 20
    assert type(value['total']) is int and 0 <= value['total'] <= 9007199254740991
    assert len(value['items']) == min(20, max(0, value['total']-page*20))
    assert value['teamName'] == sql(f"SELECT name FROM teams WHERE id='{TEAM}';")
    assert value['missionTitle'] == sql(f"SELECT title FROM missions WHERE id='{MISSION}';")
    keys=[]
    for item in value['items']:
        assert set(item) == ITEM
        assert re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}',item['eventId'])
        assert all(isinstance(item[k],str) and re.fullmatch(r'0|[1-9][0-9]*',item[k]) for k in ('versionBefore','versionAfter'))
        assert 0 <= int(item['versionBefore']) < 9223372036854775807
        assert int(item['versionAfter']) == int(item['versionBefore'])+1
        assert isinstance(item['pausedBefore'],bool) and isinstance(item['pausedAfter'],bool)
        assert item['pausedBefore'] != item['pausedAfter']
        keys.append((instant_key(item['committedAt']),item['eventId']))
        assert item['actorName'] is None or safe_name(item['actorName'])
    assert keys == sorted(keys,reverse=True) and len(set(k[1] for k in keys))==len(keys)
    return value


def flip(client):
    source=client.context(); body=desired(source); key=str(uuid.uuid4())
    status,value=client.save(body,key)
    assert status==201,status
    receipt=value['data']
    assert receipt['versionBefore']==source['intakeVersion']
    assert receipt['versionAfter']==str(int(source['intakeVersion'])+1)
    assert client.context()['intakePaused']==receipt['pausedAfter']
    return receipt,key,body


def main():
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('History probe exceeded 239 seconds')))
    signal.alarm(239)
    assert instant_key('2026-01-01T00:00:00.123456789Z') > instant_key('2026-01-01T00:00:00.123456788Z')
    assert not safe_name('unsafe\u0085name') and safe_name('Tên hiện tại 😀')
    a,b,member,foreign=Client(),Client('admin2'),Client('member'),Client('other')
    assert history(a)['total']==0
    accepted=[flip(a),flip(b)]
    # Task creation legitimately advances the shared version without an intake receipt.
    source=a.context(); assert not source['intakePaused']
    task_body={'title':'History gap task','description':'Actual input','acceptanceCriteria':'Actual acceptance',
               'priority':'NORMAL','ownerAgentId':None,'dependencyIds':[], 'expectedIntakeVersion':int(source['intakeVersion'])}
    status,_=a.save(task_body,path=MISSION_ROOT+'/tasks');assert status==201,status
    assert history(a)['total']==2
    for n in range(22): accepted.append(flip(a if n%2==0 else b))
    # Controlled legacy-counter fixture creates >JS-safe-integer versions; changes themselves are real POSTs.
    sql(f"UPDATE missions SET task_intake_version=9223372036854775805 WHERE id='{MISSION}';")
    accepted.extend([flip(a),flip(b)])
    assert a.context()['intakeVersion']=='9223372036854775807'
    first,second=history(a),history(a,1)
    assert first['total']==second['total']==26 and len(second['items'])==6
    assert not {x['eventId'] for x in first['items']} & {x['eventId'] for x in second['items']}
    assert history(a,100000)['items']==[]
    all_items=first['items']+second['items']
    order(all_items)
    expected={r['eventId']:r for r,_,_ in accepted}
    assert set(expected)=={x['eventId'] for x in all_items}
    assert {'9223372036854775806','9223372036854775807'} <= {x['versionAfter'] for x in all_items}
    for x in all_items:
        assert {k:v for k,v in x.items() if k!='actorName'}=={k:v for k,v in expected[x['eventId']].items() if k not in ('organizationId','teamId','missionId')}
    before_replay=history(a)
    r,key,body=accepted[0]
    assert reject(lambda:a.save(body,key),200)[1]['data']==r
    assert history(a)==before_replay
    assert history(b)==first  # ADMIN history is cross-actor, unlike own receipt recovery.
    actor=sql("SELECT id FROM human_accounts WHERE email='intake-history-native-admin2@example.test';")
    account=rows('human_accounts',f"id='{actor}'")[0]
    def actor_items(): return [x for x in history(a)['items'] if x['eventId'] in actor_events]
    actor_events={r['event_id'] for r in rows('mission_intake_receipts',f"actor_id='{actor}' AND organization_id='{ORG}'")}
    sql(f"UPDATE human_accounts SET display_name='<b>Current same-org name</b>' WHERE id='{actor}';")
    assert actor_items() and all(x['actorName']=='<b>Current same-org name</b>' for x in actor_items())
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='{ORG}' AND account_id='{actor}';")
    assert all(x['actorName']=='<b>Current same-org name</b>' for x in actor_items())
    reject(lambda:b.request(HISTORY),403)
    sql(f"UPDATE human_accounts SET enabled=false WHERE id='{actor}';")
    assert all(x['actorName']=='<b>Current same-org name</b>' for x in actor_items())
    sql(f"UPDATE human_accounts SET enabled=true WHERE id='{actor}';")
    sql(f"INSERT INTO organization_memberships(organization_id,account_id,role) VALUES('{ident('54000000',5)}','{actor}','MEMBER'); DELETE FROM organization_memberships WHERE organization_id='{ORG}' AND account_id='{actor}';")
    assert all(x['actorName'] is None for x in actor_items()) and history(a)['total']==26
    sql(f"INSERT INTO organization_memberships(organization_id,account_id,role) VALUES('{ORG}','{actor}','ADMIN'); DELETE FROM organization_memberships WHERE organization_id='{ident('54000000',5)}' AND account_id='{actor}';")
    escaped=account['display_name'].replace("'","''")
    sql(f"UPDATE human_accounts SET display_name='{escaped}' WHERE id='{actor}';")
    # Current lifecycle/parent policy never hides historical provenance.
    before_policy=history(a)
    sql(f"UPDATE teams SET status='ARCHIVED' WHERE id='{TEAM}'; UPDATE missions SET status='COMPLETED',task_intake_paused=true WHERE id='{MISSION}';")
    assert history(a)==before_policy
    sql(f"UPDATE teams SET status='ACTIVE' WHERE id='{TEAM}'; UPDATE missions SET status='CREATED' WHERE id='{MISSION}';")
    for client in (member,foreign):
        reject(lambda client=client:client.request(HISTORY+'?page=bad'),403)
    reject(lambda:a.request(HISTORY.replace(TEAM,ident('54010000',102))),404)
    reject(lambda:a.request(HISTORY.replace(MISSION,ident('54020000',501))),404)
    reject(lambda:a.request(HISTORY.replace(ORG,ident('54000000',5))),403)
    for query in ('?page=-1','?page=01','?page=','?page=1e0','?page=100001','?page=0&page=1','?unknown=0','?page=0&unknown=1'):
        reject(lambda query=query:a.request(HISTORY+query),400)
    before=snapshot()
    try:
        response=a.opener.open(urllib.request.Request(BASE+HISTORY,data=b'{}',method='GET'),timeout=25)
    except urllib.error.HTTPError as error: response=error
    with response: assert response.status==400
    assert snapshot()==before
    # Tie-order fixture: preserve actual accepted events/versions, adjust only test timestamps consistently.
    stored=rows('mission_intake_receipts',f"organization_id='{ORG}' AND mission_id='{MISSION}'")
    tie='2026-10-20T12:00:00.000000Z'
    sql(f"UPDATE mission_intake_receipts SET created_at='{tie}',original_response=jsonb_set(original_response::jsonb,'{{committedAt}}',to_jsonb('{tie}'::text))::text WHERE organization_id='{ORG}' AND mission_id='{MISSION}';")
    tied=history(a)['items']+history(a,1)['items']
    order(tied)
    assert [x['eventId'] for x in tied]==sorted(expected,reverse=True)
    for row in stored:
        encoded=row['original_response'].replace("'","''")
        sql(f"UPDATE mission_intake_receipts SET original_response='{encoded}',created_at='{row['created_at']}' WHERE event_id='{row['event_id']}';")
    # Selected-page corruption fails safely; unselected corruption is not globally validated.
    corrupt_event=history(a,1)['items'][-1]['eventId']
    row=next(r for r in stored if r['event_id']==corrupt_event)
    original=row['original_response']; encoded=original.replace("'","''")
    for mutation in ("original_response='PRIVATE_HISTORY_CORRUPTION'", "created_at=created_at-interval '1 second'"):
        sql(f"UPDATE mission_intake_receipts SET {mutation} WHERE event_id='{corrupt_event}';")
        history(a,0)
        result=reject(lambda:a.request(HISTORY+'?page=1'),500,'MISSION_INTAKE_HISTORY_UNAVAILABLE')
        assert 'PRIVATE_HISTORY_CORRUPTION' not in json.dumps(result[1])
        sql(f"UPDATE mission_intake_receipts SET original_response='{encoded}',created_at='{row['created_at']}' WHERE event_id='{corrupt_event}';")
    # Fresh viewer account-state/credential checks, not historical author policy.
    for mutation in ('enabled=false','credential_version=credential_version+1'):
        viewer=Client('admin2'); old=rows('human_accounts',f"id='{actor}'")[0]
        sql(f"UPDATE human_accounts SET {mutation} WHERE id='{actor}';")
        before=snapshot(); status,value=viewer.request(HISTORY)
        assert status in (401,403) and 'data' not in value
        assert snapshot()==before
        sql(f"UPDATE human_accounts SET enabled=true,credential_version={old['credential_version']} WHERE id='{actor}';")
    assert history(a)['total']==26
    print('PASS: intake history actual receipts/paging/ties/gaps/int64/current actor privacy/lifecycle/strict wire/corruption/authority and no business writes')


if __name__=='__main__': main()

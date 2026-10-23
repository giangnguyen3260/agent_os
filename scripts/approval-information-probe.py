"""Native PostgreSQL exact-persistence, revocation, concurrent and no-execution probes.
Run after approval-information-ci-fixture.sql against disposable Docker CI only.
"""
import concurrent.futures
from datetime import datetime
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import select
import time
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORG = '40404040-4040-4040-8040-404040404040'
FOREIGN = '41414141-4141-4141-8141-414141414141'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ROOT = f'/api/v1/organizations/{ORG}/approvals/'


def ident(prefix, n):
    return f'{prefix}0000-0000-4000-8000-{n:012d}'


def sql(query, timeout=30):
    return subprocess.check_output(
        ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
         'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        cwd=Path(__file__).resolve().parents[1], input=query, text=True, timeout=timeout).strip()


class Client:
    def __init__(self, role):
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.csrf = body['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'approval-information-{role}@example.test',
            'password': os.environ['E2E_PASSWORD']}).encode(), content_type='application/x-www-form-urlencoded')
        assert status == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.csrf = body['data']

    def request(self, path, body=None, key=None, content_type='application/json', csrf=True):
        headers = {'Content-Type': content_type}
        if key:
            headers['Idempotency-Key'] = key
        if body is not None and csrf:
            headers[self.csrf['headerName']] = self.csrf['token']
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        try:
            response = self.http.open(urllib.request.Request(BASE + path, body, headers), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            assert 'FOREIGN_INFORMATION_PRIVATE' not in raw and 'password_hash' not in raw
            return response.status, json.loads(raw) if raw else None


    def information(self,n):
        code,body=self.request(ROOT+ident('4050',n)+'/information')
        assert code==200,(code,body)
        return body['data']

    def mutate(self,n,operation,body,key=None,csrf=True):
        return self.request(ROOT+ident('4050',n)+'/'+operation,body,key or str(uuid.uuid4()),csrf=csrf)

    def decide(self,n,revision=0,key=None):
        code,body=self.request(ROOT+ident('4050',n))
        assert code==200
        snap=body['data']['snapshot']
        return self.mutate(n,'approve',{'snapshotVersion':snap['version'],'snapshotSha256':snap['payloadSha256'],'acknowledgement':True,'reason':None,'informationRevision':revision},key)


class Holder:
    """Controlled native transaction; every exit rolls back or ends its backend."""
    def __init__(self, statement):
        self.name = 'approval-information-probe-' + uuid.uuid4().hex
        self.pid = None
        self.process = subprocess.Popen(
            ['docker','compose','exec','-T','postgres','sh','-c',
             'psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
            cwd=Path(__file__).resolve().parents[1],stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            self.send(f"SET application_name='{self.name}';\n\\o /dev/null\nBEGIN; {statement};\n\\o\nSELECT 'LOCKED:'||pg_backend_pid();")
            assert select.select([self.process.stdout],[],[],15)[0], 'lock-holder marker timed out'
            marker = self.process.stdout.readline().strip()
            assert marker.startswith('LOCKED:'), marker
            self.pid = int(marker.split(':')[1])
        except BaseException:
            self.close()
            raise

    def send(self, statement):
        self.process.stdin.write(statement + '\n')
        self.process.stdin.flush()

    def expect_marker(self, expected):
        assert select.select([self.process.stdout],[],[],15)[0], 'transaction completion marker timed out'
        assert self.process.stdout.readline().strip() == expected, 'transaction failed before completion'

    def close(self):
        try:
            if self.process.poll() is None:
                try:
                    self.send('ROLLBACK;')
                except (BrokenPipeError, ValueError):
                    pass
                if not self.process.stdin.closed:
                    self.process.stdin.close()
                try:
                    self.process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    # Terminate this exact dedicated PG backend, never an application backend.
                    try:
                        sql(f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{self.name}' AND pid<>pg_backend_pid();", timeout=5)
                    finally:
                        self.process.terminate()
                        try:
                            self.process.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            self.process.kill()
                            self.process.wait(timeout=3)
        finally:
            for pipe in (self.process.stdin,self.process.stdout,self.process.stderr):
                if not pipe.closed:
                    try:
                        pipe.close()
                    except (BrokenPipeError, ValueError):
                        pass


def await_lock_wait(holder, relation):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        blocked = sql(f"SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query ILIKE '%{relation}%' AND {holder.pid}=ANY(pg_blocking_pids(pid));", timeout=max(0.1,min(3,deadline-time.monotonic())))
        if int(blocked) > 0:
            return
    raise AssertionError(f'No application waiter observed on {relation}')



def state():
    return sql("SELECT row_to_json(x)::text FROM approval_requests x WHERE organization_id='"+ORG+"' ORDER BY id;"+
        "SELECT row_to_json(x)::text FROM audit_events x WHERE organization_id='"+ORG+"' ORDER BY id;"+
        "SELECT row_to_json(x)::text FROM approval_information_entries x ORDER BY id;"+
        "SELECT row_to_json(x)::text FROM approval_information_receipts x ORDER BY organization_id,actor_id,idempotency_key;"+
        "SELECT row_to_json(x)::text FROM approval_decisions x ORDER BY id;")


def effects():
    return sql(''.join('SELECT row_to_json(x)::text FROM '+t+' x ORDER BY id;' for t in ('tasks','missions','agents','approval_snapshots','approval_action_heads')))


def capture_shared_state():
    accounts = ','.join("'"+ident('4080',n)+"'" for n in (1,2,3))
    grants = ','.join("'"+ident('4070',n)+"'" for n in (1,2))
    return {
        'accounts': json.loads(sql(f"SELECT json_agg(x) FROM (SELECT id,enabled,credential_version FROM human_accounts WHERE id IN ({accounts}) ORDER BY id) x;")),
        'memberships': json.loads(sql(f"SELECT json_agg(x) FROM (SELECT account_id,organization_id,role FROM organization_memberships WHERE account_id IN ({accounts}) AND organization_id='{ORG}' ORDER BY account_id) x;")),
        'grants': json.loads(sql(f"SELECT json_agg(x) FROM (SELECT * FROM approval_grants WHERE id IN ({grants}) ORDER BY id) x;")),
    }


def restore_shared_state(baseline):
    def literal(value):
        if value is None:
            return 'NULL'
        if isinstance(value,bool):
            return 'true' if value else 'false'
        return "'" + str(value).replace("'", "''") + "'"
    queries = ['BEGIN;']
    for account in baseline['accounts']:
        queries.append(f"UPDATE human_accounts SET enabled={literal(account['enabled'])},credential_version={literal(account['credential_version'])} WHERE id={literal(account['id'])};")
    for membership in baseline['memberships']:
        queries.append('INSERT INTO organization_memberships(account_id,organization_id,role) VALUES (' +
                       ','.join(literal(membership[k]) for k in ('account_id','organization_id','role')) +
                       ') ON CONFLICT (account_id,organization_id) DO UPDATE SET role=EXCLUDED.role;')
    for grant in baseline['grants']:
        fields = ('organization_id','team_id','account_id','action_type','risk_level','expires_at','revoked_at')
        queries.append('UPDATE approval_grants SET ' + ','.join(k+'='+literal(grant[k]) for k in fields) +
                       ' WHERE id='+literal(grant['id'])+';')
    queries.append('COMMIT;')
    sql(''.join(queries))
    assert capture_shared_state() == baseline, 'Shared browser authority was not restored exactly'



def main():
    admin,member,requester=(Client(role) for role in ('admin','member','requester'))
    unchanged=effects()
    def ask(n,client=admin,revision=0,key=None):
        return client.mutate(n,'request-information',{'revision':revision,'question':'  Explain scope  '},key)
    def answer(n,question,revision=1,key=None,client=requester):
        return client.mutate(n,'reply',{'revision':revision,'questionId':question,'answer':'  Scope confirmed  ','evidence':'  Internal evidence  '},key)
    for n,actor in ((901,admin),(902,member)):
        assert actor.request(ROOT+ident('4050',n)+'/information/receipts/'+str(uuid.uuid4()))[1]['data'] is None
        before=actor.information(n)
        assert before=={'approvalId':ident('4050',n),'revision':0,'canRequest':True,'canReply':False,'entries':[],'latestQuestionId':None},before
        key=str(uuid.uuid4())
        code,body=ask(n,actor,key=key); assert code==201,(code,body)
        first=body['data']; assert first['revision']==1 and first['status']=='NEEDS_INFORMATION'
        question=first['entryId']
        receipt_path=ROOT+ident('4050',n)+'/information/receipts/'+key
        frozen=state(); assert actor.request(receipt_path)[1]['data']==first; assert state()==frozen
        assert requester.request(receipt_path)[1]['data'] is None
        assert actor.request(ROOT+ident('4050',920)+'/information/receipts/'+key)[1]['data'] is None
        frozen=state(); replayed=ask(n,actor,key=key); assert replayed[0]==200 and replayed[1]['data']==first; assert state()==frozen
        assert answer(n,question,client=actor)[0]==403
        assert answer(n,str(uuid.uuid4()))[0]==409
        replykey=str(uuid.uuid4()); code,reply=answer(n,question,key=replykey); assert code==201,(code,reply)
        assert reply['data']['revision']==2 and reply['data']['status']=='PENDING'
        thread=actor.information(n)
        assert thread['revision']==len(thread['entries'])==2 and thread['latestQuestionId']==question
        assert [e['text'] for e in thread['entries']]==['Explain scope','Scope confirmed']
        assert thread['entries'][0]['evidence'] is None and thread['entries'][1]['evidence']=='Internal evidence'
        frozen=state(); replayed=ask(n,actor,key=key); assert replayed[0]==200 and replayed[1]['data']==first; replayed=answer(n,question,key=replykey); assert replayed[0]==200 and replayed[1]['data']==reply['data']; assert state()==frozen
        assert ask(n,actor,revision=2,key=key)[0]==409
        assert actor.decide(n,0)[0]==409
        assert actor.decide(n,2)[0]==201
        assert actor.information(n)['canRequest'] is False
        count=sql("SELECT count(*) FROM approval_information_entries WHERE approval_id='"+ident('4050',n)+"';")
        assert count=='2'
        assert sql("SELECT count(*) FROM approval_information_receipts WHERE approval_id='"+ident('4050',n)+"';")=='2'
        assert sql("SELECT count(*) FROM audit_events WHERE organization_id='"+ORG+"' AND event_type IN ('APPROVAL_INFORMATION_REQUESTED','APPROVAL_INFORMATION_REPLIED') AND payload LIKE '%"+ident('4050',n)+"%';")=='2'
        assert sql("SELECT count(*) FROM audit_events WHERE organization_id='"+ORG+"' AND (payload LIKE '%Explain scope%' OR payload LIKE '%Internal evidence%');")=='0'
    for n,expected in ((904,403),(905,403),(906,409),(907,409),(908,409),(909,409),(910,409),(912,409),(913,409),(914,403)):
        frozen=state(); code,body=ask(n); assert code==expected,(n,code,body); assert state()==frozen
    assert requester.information(909)['canReply'] is False
    for payload in ({'revision':'0','question':'text'},{'revision':0,'question':True},{'revision':0,'question':'text','unknown':1},{'revision':9007199254740992,'question':'text'},{'revision':0,'question':' '},{'revision':0,'question':'a'*2001}):
        assert admin.mutate(915,'request-information',payload)[0]==400
    assert admin.mutate(915,'request-information',b'{"revision":0,"revision":0,"question":"ok"}')[0]==400
    assert admin.mutate(915,'request-information',{'revision':0,'question':'ok'},csrf=False)[0]==403
    assert admin.mutate(9999,'request-information',{'revision':0,'question':'ok'})[0]==404
    assert admin.request('/api/v1/organizations/'+FOREIGN+'/approvals/'+ident('4050',9999)+'/information')[0]==403
    # Exact same-key native duplicate reply has one committed entry, then one replay.
    code,body=ask(916); assert code==201
    q=body['data']['entryId']; key=str(uuid.uuid4())
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(answer,916,q,1,key,Client('requester')) for _ in range(2)]
        results=[f.result(timeout=45) for f in futures]
    assert sorted(r[0] for r in results)==[200,201],results
    assert results[0][1]['data']==results[1][1]['data']
    assert requester.information(916)['revision']==2
    # Opposite ask/approve requests serialize on the same original revision.
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(ask,917,Client('admin'))
        b=pool.submit(Client('member').decide,917)
        results=[a.result(timeout=45)[0],b.result(timeout=45)[0]]
    assert sorted(results)==[201,409],results
    # PostgreSQL constraint failure must roll back every information write atomically.
    constraint='ai_probe_'+uuid.uuid4().hex
    sql("ALTER TABLE audit_events ADD CONSTRAINT "+constraint+" CHECK (event_type <> 'APPROVAL_INFORMATION_REQUESTED' OR organization_id <> '"+ORG+"');")
    try:
        frozen=state(); assert ask(918)[0]==500; assert state()==frozen
    finally:
        sql('ALTER TABLE audit_events DROP CONSTRAINT '+constraint+';')
    # Fresh grant authorization precedes replay, with exact state restoration.
    key=str(uuid.uuid4()); code,body=ask(919,member,key=key); assert code==201
    grant=ident('4070',2)
    try:
        sql("UPDATE approval_grants SET revoked_at=now() WHERE id='"+grant+"';")
        frozen=state(); assert ask(919,member,key=key)[0]==403; assert member.request(ROOT+ident('4050',919)+'/information/receipts/'+key)[0]==403; assert state()==frozen
    finally:
        sql("UPDATE approval_grants SET revoked_at=null WHERE id='"+grant+"';")
    account=ident('4080',3)
    try:
        sql("DELETE FROM organization_memberships WHERE organization_id='"+ORG+"' AND account_id='"+account+"';")
        frozen=state(); assert answer(919,body['data']['entryId'])[0] in (401,403); assert state()==frozen
    finally:
        sql("INSERT INTO organization_memberships VALUES ('"+account+"','"+ORG+"','MEMBER');")
    # Observe the actual grant lock wait before committing revocation (not a sleep inference).
    grant=ident('4070',2)
    original=json.loads(sql("SELECT row_to_json(g) FROM approval_grants g WHERE id='"+grant+"';"))
    holder=Holder("UPDATE approval_grants SET revoked_at=now() WHERE id='"+grant+"'")
    pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)
    try:
        frozen=state()
        pending=pool.submit(ask,921,Client('member'))
        await_lock_wait(holder,'approval_grants')
        holder.send("COMMIT; SELECT 'COMMITTED';")
        holder.expect_marker('COMMITTED')
        assert pending.result(timeout=30)[0]==403
        assert state()==frozen
    finally:
        holder.close()
        pool.shutdown(wait=True,cancel_futures=True)
        value="NULL" if original['revoked_at'] is None else "'"+original['revoked_at'].replace("'","''")+"'"
        sql("UPDATE approval_grants SET revoked_at="+value+" WHERE id='"+grant+"';")
    assert effects()==unchanged,'Information exchange changed runtime or sealed inputs'
    print('PASS: native PostgreSQL information thread/receipts/audit, ADMIN/MEMBER grant, exact requester, revision-bound decisions, replay, concurrency, rollback, no execution')


if __name__=='__main__':
    baseline=capture_shared_state()
    try:
        main()
    finally:
        restore_shared_state(baseline)

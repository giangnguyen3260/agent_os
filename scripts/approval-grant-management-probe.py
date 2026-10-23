"""Native PostgreSQL AGM persistence, lock-order, rollback and revoke-waiter proof.
Run once after approval-grant-management-ci-fixture.sql in a disposable database.
E2E_BASE_URL/E2E_PASSWORD match other probes. Optional AGM_PSQL_COMMAND is a JSON
argv array for native psql; default is the existing Docker Compose PostgreSQL service.
--vectors-only checks canonical vectors without a database; it is not a native gate.
"""
import concurrent.futures
from datetime import datetime, timezone, timedelta
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORG = '60606060-6060-4060-8060-606060606060'
FOREIGN = '61616161-6161-4161-8161-616161616161'
CROSS = '62626262-6262-4262-8262-626262626262'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
RESULT_FIELDS = {'operation','organizationId','accountId','actorId','actorName','idempotencyKey',
                 'grantId','eventId','revision','recordedAt','expiresAt','revokedAt'}


def ident(prefix, n):
    return f'{prefix}0000-0000-4000-8000-{n:012d}'


def root(n, org=ORG):
    return f'/api/v1/organizations/{org}/members/{ident("6080", n)}/approval-grants'


def psql_command():
    if os.environ.get('AGM_PSQL_COMMAND'):
        command = json.loads(os.environ['AGM_PSQL_COMMAND'])
        assert isinstance(command, list) and all(isinstance(x, str) for x in command)
        return command
    return ['docker','compose','exec','-T','postgres','sh','-c',
            'psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']


def sql(query, timeout=30):
    return subprocess.check_output(psql_command(), cwd=Path(__file__).resolve().parents[1],
                                   input=query, text=True, timeout=timeout).strip()


class Client:
    def __init__(self, role):
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.csrf = body['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'grant-management-{role}@example.test',
            'password': os.environ['E2E_PASSWORD']}).encode(),
            content_type='application/x-www-form-urlencoded')
        assert status == 200, ('login', role, status)
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
            body = json.dumps(body, ensure_ascii=False).encode()
        try:
            response = self.http.open(urllib.request.Request(BASE+path,body,headers),timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            assert 'password_hash' not in raw and 'FOREIGN_GRANT_TEAM_PRIVATE' not in raw
            return response.status, json.loads(raw) if raw else None

    def data(self, path):
        status, body = self.request(path)
        assert status == 200, (path,status,body)
        return body['data']


def issue(revision=0, team=1, risk='HIGH', hours=1, reason='  Native reason\t\n\r 😀  '):
    return {'expectedRevision':revision,'teamId':ident('6010',team),'actionType':'PUBLISH_PREVIEW',
            'riskLevel':risk,'durationHours':hours,'reason':reason,'acknowledgement':True}


def revoke(context):
    return {'expectedRevision':context['revision'],'grantFingerprint':context['grantFingerprint'],
            'reason':'Restricted native revocation','acknowledgement':True}


def digest(obj):
    return hashlib.sha256(json.dumps(obj,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()


def canonical_time(value):
    return datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ')


def grant_hash(g):
    values = {k:g[k] for k in ('id','organizationId','accountId','teamId','actionType','riskLevel','expiresAt','revokedAt')}
    for k in ('expiresAt','revokedAt'):
        values[k] = canonical_time(values[k]) if values[k] is not None else None
    return digest(values)


def vectors():
    command = dict(issue(reason='é\r\n😀'),domain='APPROVAL_GRANT_MANAGEMENT',version=1,
                   operation='ISSUE',organizationId=ORG,accountId=ident('6080',2))
    assert digest(command) == '9591223cde55490c848b8b19d2b02cf7f477a1ee9dc77889ee551b3d1ba9f51b'
    grant = dict(id=ident('6070',1),organizationId=ORG,accountId=ident('6080',2),teamId=ident('6010',1),
                 actionType='PUBLISH_PREVIEW',riskLevel='HIGH',expiresAt='2026-10-23T12:34:56.123456Z',revokedAt=None)
    assert grant_hash(grant) == '78abadebefb661edad3f9248816bac6d4e4e8031e449d0ba456cf3962d7f56e0'
    print('Canonical command/grant vectors passed')
class Holder:
    """Controlled native transaction; every exit rolls back or ends its backend."""
    def __init__(self, statement):
        self.name = 'approval-grant-management-probe-' + uuid.uuid4().hex
        self.pid = None
        self.process = subprocess.Popen(
            psql_command(),
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



def blocked_by(pid, relation, minimum=1):
    deadline = time.monotonic()+15
    while time.monotonic()<deadline:
        rows = sql(f"WITH RECURSIVE edges AS (SELECT pid,unnest(pg_blocking_pids(pid)) blocker FROM pg_stat_activity), chain AS (SELECT * FROM edges UNION SELECT c.pid,e.blocker FROM chain c JOIN edges e ON e.pid=c.blocker) SELECT coalesce(string_agg(DISTINCT a.pid::text,','),'') FROM chain c JOIN pg_stat_activity a ON a.pid=c.pid WHERE c.blocker={int(pid)} AND a.wait_event_type='Lock' AND a.query ILIKE '%{relation}%';", timeout=3)
        if rows and len(rows.split(','))>=minimum:
            return [int(p) for p in rows.split(',')]
    raise AssertionError(f'Expected {minimum} observed {relation} waiters blocked by {pid}')


def management_state():
    return sql(''.join(f"SELECT row_to_json(x)::text FROM {table} x WHERE organization_id IN ('{ORG}','{CROSS}') ORDER BY {order};"
                       for table,order in [('approval_grants','id'),('approval_grant_ledgers','organization_id,account_id'),
                         ('approval_grant_management_events','id'),('approval_grant_management_receipts','organization_id,actor_id,idempotency_key'),('audit_events','id')]))


def runtime_state():
    return sql(''.join(f'SELECT row_to_json(x)::text FROM {table} x ORDER BY id;'
                       for table in ('teams','agents','missions','tasks')))


def mutate_ok(client, target, body, grant=None, org=ORG, key=None):
    key = key or str(uuid.uuid4())
    path = root(target,org)+(f'/{grant}/revoke' if grant else '')
    status, envelope = client.request(path,body,key)
    assert status == 201, (path,status,envelope)
    result = envelope['data']
    assert set(result)==RESULT_FIELDS and result['idempotencyKey']==key
    assert result['accountId']==ident('6080',target) and result['organizationId']==org
    assert result['operation']==('REVOKE' if grant else 'ISSUE')
    assert 'reason' not in result
    return result,key


def main():
    vectors()
    admin, member = Client('admin'), Client('member')
    runtime = runtime_state()
    # Preserve baseline and viewport rows; all mutations below use native-only targets.
    before_baseline = sql(f"SELECT row_to_json(g)::text FROM approval_grants g WHERE account_id='{ident('6080',2)}' ORDER BY id;")
    assert admin.data(root(901)+'/management-context')['revision']==0
    assert sql(f"SELECT count(*) FROM approval_grant_ledgers WHERE organization_id='{ORG}';")=='0'
    assert admin.data(root(901)+'/management-context')['selection'] is None
    frozen=management_state()
    for target,body,expected in [(1,issue(),400),(6,issue(),400),(7,issue(),404),(99,issue(),404),
                                 (901,issue(team=99),404),(901,issue(team=3),400)]:
        assert admin.request(root(target),body,str(uuid.uuid4()))[0]==expected
    assert member.request(root(901),issue(),str(uuid.uuid4()))[0]==403
    assert admin.request(root(901),issue(),str(uuid.uuid4()),csrf=False)[0]==403
    assert management_state()==frozen
    # Actual durable issue, exact DTO, canonical fingerprint and idempotency hash.
    result,key=mutate_ok(admin,901,issue(team=2,hours=168))
    delta=datetime.fromisoformat(result['expiresAt'].replace('Z','+00:00'))-datetime.fromisoformat(result['recordedAt'].replace('Z','+00:00'))
    assert delta==timedelta(hours=168)
    assert sql(f"SELECT fingerprint FROM approval_grant_management_receipts WHERE organization_id='{ORG}' AND actor_id='{ident('6080',1)}' AND idempotency_key='{key}';")==digest(dict(issue(team=2,hours=168,reason='Native reason\t\n\r 😀'),domain='APPROVAL_GRANT_MANAGEMENT',version=1,operation='ISSUE',organizationId=ORG,accountId=ident('6080',901)))
    context=admin.data(root(901)+f'/{result["grantId"]}/revocation-context')
    assert context['grantFingerprint']==grant_hash(context['grant'])
    assert admin.request(root(901),issue(team=2,hours=168),key)[0]==200
    assert admin.data(root(901)+f'/management-receipts/{key}?operation=ISSUE')==result
    assert admin.data(root(902)+f'/management-receipts/{key}?operation=ISSUE') is None
    assert admin.data(root(901)+f'/management-receipts/{key}?operation=REVOKE') is None
    assert admin.request(root(902),issue(team=2,hours=168),key)[0]==409
    assert admin.request(root(901),issue(revision=1,team=2),str(uuid.uuid4()))[0]==409
    # Concurrent new keys must yield exactly one new live grant/revision.
    clients=[Client('admin'),Client('admin')]
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        results=list(pool.map(lambda c:c.request(root(902),issue(),str(uuid.uuid4()))[0],clients))
    assert sorted(results)==[201,409]
    assert admin.data(root(902)+'/management-context?teamId='+ident('6010',1)+'&riskLevel=HIGH')['selection']['unexpiredCount']==1
    # Inject the final audit failure; every grant/event/receipt/ledger write rolls back.
    sql(f"ALTER TABLE audit_events ADD CONSTRAINT agm_probe_rollback CHECK (organization_id<>'{ORG}' OR event_type<>'APPROVAL_GRANT_MANAGED') NOT VALID;")
    frozen=management_state()
    try:
        assert admin.request(root(903),issue(),str(uuid.uuid4()))[0]==500
        assert management_state()==frozen
    finally:
        sql('ALTER TABLE audit_events DROP CONSTRAINT agm_probe_rollback;')
    # Historical legacy data: expired/unsupported/disabled/removed/self all revocable.
    for target,grant in [(6,6),(7,7),(1,8)]:
        c=admin.data(root(target)+f'/{ident("6070",grant)}/revocation-context')
        assert c['canRevoke']
        mutate_ok(admin,target,revoke(c),ident('6070',grant))
    # These duplicate/unsupported legacy rows are native copies, not the read baseline.
    for n,action,risk,expiry in [(910,'PUBLISH_PREVIEW','HIGH','2099-01-01'),(911,'PUBLISH_PREVIEW','HIGH','2099-01-01'),(912,'LEGACY','CRITICAL','2000-01-01')]:
        sql(f"INSERT INTO approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at) VALUES ('{ident('6070',n)}','{ORG}','{ident('6080',910)}','{ident('6010',3)}','{action}','{risk}','{expiry}');")
    c=admin.data(root(910)+f'/{ident("6070",910)}/revocation-context')
    assert c['remainingScopeGrants']==1
    mutate_ok(admin,910,revoke(c),ident('6070',910))
    c=admin.data(root(910)+f'/{ident("6070",912)}/revocation-context')
    mutate_ok(admin,910,revoke(c),ident('6070',912))
    # Mandatory deterministic reversed global account order across different org mutexes.
    # Hold lower account. High actor -> low recipient MUST wait without holding high.
    low,high=Client('cross-low'),Client('cross-high')
    holder=Holder(f"SELECT id FROM human_accounts WHERE id='{ident('6080',91)}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(2)
    try:
        high_call=pool.submit(high.request,root(91,CROSS),issue(team=92),str(uuid.uuid4()))
        blocked_by(holder.pid,'human_accounts')
        holder.send(f"\\o /dev/null\nSET LOCAL lock_timeout='2s'; SELECT id FROM human_accounts WHERE id='{ident('6080',92)}' FOR UPDATE;\n\\o\nSELECT 'HIGH_AVAILABLE';")
        holder.expect_marker('HIGH_AVAILABLE')
        low_call=pool.submit(low.request,root(92),issue(),str(uuid.uuid4()))
        blocked_by(holder.pid,'human_accounts',minimum=2)
        holder.send("COMMIT; SELECT 'REVERSE_RELEASED';")
        holder.expect_marker('REVERSE_RELEASED')
        assert high_call.result(timeout=25)[0]==201
        assert low_call.result(timeout=25)[0]==201
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
    # AGM-R01: membership removed during actor-lock wait must yield fresh issuer 403.
    holder=Holder(f"SELECT id FROM human_accounts WHERE id='{ident('6080',1)}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(1)
    frozen=management_state()
    try:
        replay=pool.submit(admin.request,root(901),issue(team=2,hours=168),key)
        blocked_by(holder.pid,'human_accounts')
        holder.send(f"DELETE FROM organization_memberships WHERE organization_id='{ORG}' AND account_id='{ident('6080',1)}'; COMMIT; SELECT 'ISSUER_REMOVED';")
        holder.expect_marker('ISSUER_REMOVED')
        assert replay.result(timeout=25)[0]==403
        assert management_state()==frozen
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
        sql(f"INSERT INTO organization_memberships(account_id,organization_id,role) VALUES ('{ident('6080',1)}','{ORG}','ADMIN');")
    # Valid sealed pending approvals and grant-dependent MEMBER transport remain functional.
    recipient=Client('target-901')
    decisions=f'/api/v1/organizations/{ORG}/approvals/'
    detail=recipient.data(decisions+ident('6050',903))
    assert detail['eligibility']['canApprove'], detail['eligibility']
    snap=detail['snapshot']
    decision={'snapshotVersion':snap['version'],'snapshotSha256':snap['payloadSha256'],'acknowledgement':True,'reason':None}
    decision_key=str(uuid.uuid4())
    assert recipient.request(decisions+ident('6050',903)+'/approve',decision,decision_key)[0]==201
    # Actual management revoke holds org while blocked on target. Observe both later
    # question and decision waiting on that revoke writer, then release and require denial.
    c=admin.data(root(901)+f'/{result["grantId"]}/revocation-context')
    revoke_key=str(uuid.uuid4())
    holder=Holder(f"SELECT id FROM human_accounts WHERE id='{ident('6080',901)}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(3)
    try:
        revoking=pool.submit(admin.request,root(901)+f'/{result["grantId"]}/revoke',revoke(c),revoke_key)
        revoke_pid=blocked_by(holder.pid,'human_accounts')[0]
        question=pool.submit(Client('target-901').request,decisions+ident('6050',901)+'/request-information',{'revision':0,'question':'Question after revoke'},str(uuid.uuid4()))
        deciding=pool.submit(Client('target-901').request,decisions+ident('6050',902)+'/approve',dict(decision,snapshotSha256=recipient.data(decisions+ident('6050',902))['snapshot']['payloadSha256']),str(uuid.uuid4()))
        blocked_by(revoke_pid,'organizations',minimum=2)
        holder.send("COMMIT; SELECT 'REVOKE_TARGET_RELEASED';")
        holder.expect_marker('REVOKE_TARGET_RELEASED')
        revoke_status,revoke_reply=revoking.result(timeout=25)
        assert revoke_status==201
        assert question.result(timeout=25)[0]==403 and deciding.result(timeout=25)[0]==403
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
    assert recipient.request(decisions+ident('6050',903)+'/approve',decision,decision_key)[0]==403
    # Management replay remains historical after target removal/disable and Team archival.
    sql(f"DELETE FROM organization_memberships WHERE organization_id='{ORG}' AND account_id='{ident('6080',901)}'; UPDATE human_accounts SET enabled=false WHERE id='{ident('6080',901)}';")
    status,recovered=admin.request(root(901),issue(team=2,hours=168),key)
    assert status==200 and recovered['data']==result
    assert admin.request(root(901)+f'/{result["grantId"]}/revoke',revoke(c),revoke_key)[0]==200
    assert admin.data(root(901)+f'/management-receipts/{key}?operation=ISSUE')==result
    history=admin.data(root(901)+f'/{result["grantId"]}/management-history')
    assert history['total']==2 and history['size']==20
    assert history['items'][0]['operation']=='REVOKE' and history['items'][1]['reason']=='Native reason\t\n\r 😀'
    assert sql(f"SELECT count(*) FROM approval_information_entries WHERE approval_id IN ('{ident('6050',901)}','{ident('6050',902)}');")=='0'
    assert sql(f"SELECT count(*) FROM approval_decisions WHERE approval_id IN ('{ident('6050',901)}','{ident('6050',902)}');")=='0'
    assert sql(f"SELECT row_to_json(g)::text FROM approval_grants g WHERE account_id='{ident('6080',2)}' ORDER BY id;")==before_baseline
    assert runtime_state()==runtime
    print('Native PostgreSQL AGM: durable issue/revoke/replay, scoped legacy boundaries, concurrent issue, audit rollback, reversed-account barrier, fresh-issuer replay wait, observed post-revoke question+decision waiters and no runtime changes passed')


if __name__=='__main__':
    if sys.argv[1:]==['--vectors-only']:
        vectors()
    else:
        assert not sys.argv[1:], 'Only --vectors-only is supported'
        main()
